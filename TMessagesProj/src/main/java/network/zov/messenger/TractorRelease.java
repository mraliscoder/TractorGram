package network.zov.messenger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TractorRelease {
    static final String PAGE = "https://github.com/mraliscoder/TractorGram/releases";
    static final String CHANNEL = "github-stable";
    static final String METADATA = "update.json";
    static final String APK = "app.apk";
    private static final Pattern TAG = Pattern.compile("v([0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5})-([1-9][0-9]{0,9})");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final long DEFER_MILLIS = 7 * 24 * 60 * 60 * 1000L;

    public final int versionCode;
    public final String versionName;
    public final String pageUrl;
    public final String notes;

    private TractorRelease(int versionCode, String versionName, String tag, String notes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        pageUrl = PAGE + "/tag/" + tag;
        String text = notes.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "").trim();
        notes = text.codePointCount(0, text.length()) <= 450 ? text : text.substring(0, text.offsetByCodePoints(0, 450)).trim() + "…";
        this.notes = notes;
    }

    interface MetadataLoader {
        byte[] load(String url) throws IOException;
    }

    static TractorRelease find(JSONArray releases, String packageName, int installedCode, int sdk,
                               String[] deviceAbis, String signer, MetadataLoader loader) throws IOException {
        ArrayList<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || !Boolean.FALSE.equals(release.opt("draft")) ||
                    !Boolean.FALSE.equals(release.opt("prerelease"))) {
                continue;
            }
            String tag = release.optString("tag_name");
            Matcher matcher = TAG.matcher(tag);
            String commit = release.optString("target_commitish");
            if (!matcher.matches() || !COMMIT.matcher(commit).matches()) {
                continue;
            }
            long code = Long.parseLong(matcher.group(2));
            if (code <= installedCode || code > Integer.MAX_VALUE ||
                    !(PAGE + "/tag/" + tag).equals(release.optString("html_url"))) {
                continue;
            }
            String apkDigest = assetDigest(release.optJSONArray("assets"), tag, APK);
            String metadataDigest = assetDigest(release.optJSONArray("assets"), tag, METADATA);
            if (apkDigest != null && metadataDigest != null) {
                candidates.add(new Candidate((int) code, matcher.group(1), tag, commit, apkDigest,
                        metadataDigest, release.optString("body", "")));
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.code).reversed());
        for (Candidate candidate : candidates) {
            byte[] bytes = loader.load(assetUrl(candidate.tag, METADATA));
            if (bytes == null || !candidate.metadataDigest.equals(sha256(bytes))) {
                continue;
            }
            JSONObject metadata;
            try {
                metadata = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                continue;
            }
            int minSdk = integer(metadata, "minSdk");
            if (integer(metadata, "schemaVersion") == 1 && CHANNEL.equals(metadata.optString("channel")) &&
                    packageName.equals(metadata.optString("applicationId")) &&
                    candidate.code == integer(metadata, "versionCode") &&
                    candidate.name.equals(metadata.optString("versionName")) &&
                    candidate.commit.equals(metadata.optString("buildCommit")) &&
                    APK.equals(metadata.optString("apk")) &&
                    candidate.apkDigest.equals(metadata.optString("apkSha256")) &&
                    signer.equals(metadata.optString("signerSha256")) &&
                    minSdk > 0 && minSdk <= sdk && supportsAbi(metadata.optJSONArray("abis"), deviceAbis)) {
                return new TractorRelease(candidate.code, candidate.name, candidate.tag, candidate.notes);
            }
        }
        return null;
    }

    private static int integer(JSONObject json, String key) {
        Object value = json.opt(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return -1;
    }

    private static boolean supportsAbi(JSONArray abis, String[] deviceAbis) {
        if (abis == null) {
            return false;
        }
        for (int i = 0; i < abis.length(); i++) {
            for (String deviceAbi : deviceAbis) {
                if (deviceAbi.equals(abis.opt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String assetUrl(String tag, String name) {
        return PAGE + "/download/" + tag + "/" + name;
    }

    private static String assetDigest(JSONArray assets, String tag, String name) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name"))) {
                continue;
            }
            String digest = asset.optString("digest");
            if ("uploaded".equals(asset.optString("state")) && asset.optLong("size") > 0 &&
                    assetUrl(tag, name).equals(asset.optString("browser_download_url")) &&
                    digest.startsWith("sha256:") && SHA256.matcher(digest.substring(7)).matches()) {
                return digest.substring(7);
            }
        }
        return null;
    }

    static boolean offerAutomatically(int code, int deferredCode, long deferredAt, long now) {
        return code != deferredCode || now < deferredAt || now - deferredAt >= DEFER_MILLIS;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Candidate {
        final int code;
        final String name;
        final String tag;
        final String commit;
        final String apkDigest;
        final String metadataDigest;
        final String notes;

        Candidate(int code, String name, String tag, String commit, String apkDigest, String metadataDigest, String notes) {
            this.code = code;
            this.name = name;
            this.tag = tag;
            this.commit = commit;
            this.apkDigest = apkDigest;
            this.metadataDigest = metadataDigest;
            this.notes = notes;
        }
    }
}
