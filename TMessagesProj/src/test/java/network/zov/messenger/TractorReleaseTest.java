package network.zov.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class TractorReleaseTest {
    private static final String PACKAGE = "network.zov.messenger";
    private static final String APK_DIGEST = TractorRelease.sha256(new byte[]{1});
    private static final String SIGNER = TractorRelease.sha256(new byte[]{2});
    private static final String COMMIT = "21f7d109da6ab3506c9e9577f98c6a38771ab335";
    private static final int INSTALLED = 73031;
    private static final int UPDATE = 73041;
    private static final String[] DEVICE_ABIS = {"x86_64", "x86"};
    private final Map<String, byte[]> documents = new HashMap<>();

    @Test
    public void offersHighestCompatibleRelease() throws Exception {
        JSONObject higher = release(73051, metadata(73051).put("minSdk", 37));
        JSONObject lower = release(UPDATE, metadata(UPDATE));
        TractorRelease result = find(new JSONArray().put(lower).put(higher));
        assertNotNull(result);
        assertEquals(UPDATE, result.versionCode);
        assertEquals("13.2.3", result.versionName);
        assertEquals(TractorRelease.PAGE + "/tag/v13.2.3-73041", result.pageUrl);
        assertEquals("Bug fixes", result.notes);
    }

    @Test
    public void skipsOlderEqualDraftAndPrerelease() throws Exception {
        JSONArray releases = new JSONArray()
                .put(release(70381, metadata(70381)))
                .put(release(INSTALLED, metadata(INSTALLED)))
                .put(release(73081, metadata(73081)).put("draft", true))
                .put(release(73091, metadata(73091)).put("prerelease", true));
        assertNull(TractorRelease.find(releases, PACKAGE, INSTALLED, 36, DEVICE_ABIS, SIGNER, url -> {
            fail("Skipped releases must not download metadata");
            return null;
        }));
    }

    @Test
    public void rejectsIncompatibleAndUnboundMetadata() throws Exception {
        assertRejected(metadata(UPDATE).put("applicationId", PACKAGE + ".beta"));
        assertRejected(metadata(UPDATE).put("channel", "beta"));
        assertRejected(metadata(UPDATE).put("versionCode", INSTALLED));
        assertRejected(metadata(UPDATE).put("versionCode", "73041"));
        assertRejected(metadata(UPDATE).put("versionName", "99.0.0"));
        assertRejected(metadata(UPDATE).put("minSdk", 37));
        assertRejected(metadata(UPDATE).put("abis", new JSONArray().put("arm64-v8a")));
        assertRejected(metadata(UPDATE).put("apkSha256", SIGNER));
        assertRejected(metadata(UPDATE).put("signerSha256", APK_DIGEST));
        assertRejected(metadata(UPDATE).put("buildCommit", "1010101010101010101010101010101010101010"));
    }

    @Test
    public void skipsMissingOrTamperedAssetsAndForeignPages() throws Exception {
        JSONObject noApk = release(UPDATE, metadata(UPDATE));
        noApk.getJSONArray("assets").remove(0);
        assertNull(find(new JSONArray().put(noApk)));
        JSONObject release = release(UPDATE, metadata(UPDATE));
        documents.replaceAll((url, bytes) -> "{}".getBytes(StandardCharsets.UTF_8));
        assertNull(find(new JSONArray().put(release)));
        documents.clear();
        assertNull(find(new JSONArray().put(release)));
        JSONObject badPage = release(UPDATE, metadata(UPDATE)).put("html_url", "https://example.org");
        assertNull(find(new JSONArray().put(badPage)));
    }

    @Test(expected = IOException.class)
    public void preservesTransientNetworkFailure() throws Exception {
        JSONArray releases = new JSONArray().put(release(UPDATE, metadata(UPDATE)));
        TractorRelease.find(releases, PACKAGE, INSTALLED, 36, DEVICE_ABIS, SIGNER, url -> {
            throw new IOException("HTTP 429");
        });
    }

    @Test
    public void defersSameVersionAndRespectsAutomaticSchedule() {
        long now = 24 * 60 * 60 * 1000L;
        assertFalse(TractorRelease.offerAutomatically(UPDATE, UPDATE, now, now + 1000));
        assertTrue(TractorRelease.offerAutomatically(UPDATE + 10, UPDATE, now, now + 1000));
        assertTrue(TractorRelease.offerAutomatically(UPDATE, UPDATE, now, 8 * now));
        assertFalse(TractorUpdates.automaticCheckDue(false, 0, now));
        assertFalse(TractorUpdates.automaticCheckDue(true, 2 * now, now));
        assertTrue(TractorUpdates.automaticCheckDue(true, 2 * now, 2 * now));
        assertTrue(TractorUpdates.automaticCheckDue(true, 3 * now, now));
    }

    private void assertRejected(JSONObject metadata) throws Exception {
        assertNull(find(new JSONArray().put(release(UPDATE, metadata))));
    }

    private TractorRelease find(JSONArray releases) throws Exception {
        return TractorRelease.find(releases, PACKAGE, INSTALLED, 36, DEVICE_ABIS, SIGNER, documents::get);
    }

    private JSONObject metadata(int code) throws Exception {
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("channel", TractorRelease.CHANNEL)
                .put("applicationId", PACKAGE)
                .put("versionName", "13.2.3")
                .put("versionCode", code)
                .put("minSdk", 23)
                .put("abis", new JSONArray().put("x86_64"))
                .put("apk", TractorRelease.APK)
                .put("apkSha256", APK_DIGEST)
                .put("signerSha256", SIGNER)
                .put("buildCommit", COMMIT);
    }

    private JSONObject release(int code, JSONObject metadata) throws Exception {
        String tag = "v13.2.3-" + code;
        String downloads = TractorRelease.PAGE + "/download/" + tag + "/";
        byte[] bytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
        documents.put(downloads + TractorRelease.METADATA, bytes);
        return new JSONObject()
                .put("draft", false)
                .put("prerelease", false)
                .put("tag_name", tag)
                .put("target_commitish", COMMIT)
                .put("html_url", TractorRelease.PAGE + "/tag/" + tag)
                .put("body", "Bug fixes")
                .put("assets", new JSONArray()
                        .put(asset(downloads, TractorRelease.APK, APK_DIGEST))
                        .put(asset(downloads, TractorRelease.METADATA, TractorRelease.sha256(bytes))));
    }

    private JSONObject asset(String downloads, String name, String digest) throws Exception {
        return new JSONObject().put("name", name).put("state", "uploaded").put("size", 1)
                .put("browser_download_url", downloads + name).put("digest", "sha256:" + digest);
    }
}
