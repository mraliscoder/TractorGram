package network.zov.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

public final class TractorUpdates {
    private static final String API = "https://api.github.com/repos/mraliscoder/TractorGram/releases?per_page=20";
    private static final long RETRY_MILLIS = 60 * 60 * 1000L;
    private static final long CHECK_INTERVAL_MILLIS = 24 * RETRY_MILLIS;
    private static final long MANUAL_INTERVAL_MILLIS = 30_000;
    private static final int TIMEOUT_MILLIS = 8_000;
    private static final String AUTO = "automatic";
    private static final String NEXT_CHECK = "nextCheck";
    private static final String DEFERRED_CODE = "deferredCode";
    private static final String DEFERRED_AT = "deferredAt";

    // Check and UI state are confined to the main thread. The worker only handles the request.
    private static boolean checking;
    private static long lastRequestElapsed = -MANUAL_INTERVAL_MILLIS;
    private static boolean lastCheckFailed;
    private static Callback waitingManualCheck;
    private static TractorRelease latest;
    private static int lastOfferedCode;

    private TractorUpdates() {
    }

    public interface Callback {
        void completed(TractorRelease release, boolean failed);
    }

    public static boolean isEnabled() {
        ApplicationLoader app = ApplicationLoader.applicationLoaderInstance;
        return app != null && app.usesTractorGramUpdates();
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tractorgram_updates", Context.MODE_PRIVATE);
    }

    public static boolean isAutomatic() {
        return preferences().getBoolean(AUTO, true);
    }

    public static void setAutomatic(boolean enabled) {
        preferences().edit().putBoolean(AUTO, enabled).apply();
    }

    public static TractorRelease automaticOffer() {
        if (latest == null || !isAutomatic() || latest.versionCode == lastOfferedCode) {
            return null;
        }
        SharedPreferences prefs = preferences();
        return TractorRelease.offerAutomatically(latest.versionCode, prefs.getInt(DEFERRED_CODE, 0),
                prefs.getLong(DEFERRED_AT, 0), System.currentTimeMillis()) ? latest : null;
    }

    public static void offered(TractorRelease release) {
        lastOfferedCode = release.versionCode;
    }

    public static void defer(TractorRelease release) {
        long now = System.currentTimeMillis();
        preferences().edit().putInt(DEFERRED_CODE, release.versionCode).putLong(DEFERRED_AT, now).apply();
    }

    public static void check(boolean manual, Callback callback) {
        if (!isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = preferences().getLong(NEXT_CHECK, 0);
        if (!manual && !automaticCheckDue(isAutomatic(), next, now)) {
            return;
        }
        long elapsedNow = SystemClock.elapsedRealtime();
        if (checking) {
            if (manual) {
                waitingManualCheck = callback;
            }
            return;
        }
        if (elapsedNow - lastRequestElapsed < MANUAL_INTERVAL_MILLIS) {
            if (manual) {
                callback.completed(lastCheckFailed ? null : latest, lastCheckFailed);
            }
            return;
        }

        checking = true;
        lastRequestElapsed = elapsedNow;
        preferences().edit().putLong(NEXT_CHECK, now + RETRY_MILLIS).apply();
        new Thread(() -> {
            TractorRelease release = null;
            boolean failed = false;
            try {
                release = findRelease();
            } catch (IOException | JSONException | PackageManager.NameNotFoundException e) {
                failed = true;
            }
            TractorRelease result = release;
            boolean error = failed;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                lastCheckFailed = error;
                if (!error) {
                    latest = result;
                    preferences().edit().putLong(NEXT_CHECK, System.currentTimeMillis() + CHECK_INTERVAL_MILLIS).apply();
                }
                Callback queued = waitingManualCheck;
                waitingManualCheck = null;
                if (queued != null) {
                    queued.completed(result, error);
                }
                callback.completed(result, error);
            });
        }, "TractorGramReleaseCheck").start();
    }

    static boolean automaticCheckDue(boolean enabled, long next, long now) {
        return enabled && (next <= now || next - now > CHECK_INTERVAL_MILLIS);
    }

    @SuppressWarnings("deprecation")
    private static TractorRelease findRelease() throws IOException, JSONException, PackageManager.NameNotFoundException {
        Context context = ApplicationLoader.applicationContext;
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(),
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        Signature[] signers = Build.VERSION.SDK_INT >= 28
                ? installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners()
                : installed.signatures;
        if (signers == null || signers.length != 1) {
            return null;
        }
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        byte[] body = fetch(new URL(API), 2 * 1024 * 1024, false, deadline);
        JSONArray releases = new JSONArray(new String(body, StandardCharsets.UTF_8));
        return TractorRelease.find(releases, context.getPackageName(), installed.versionCode, Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS, TractorRelease.sha256(signers[0].toByteArray()),
                url -> fetch(new URL(url), 16 * 1024, true, deadline));
    }

    private static byte[] fetch(URL url, int limit, boolean asset, long deadline) throws IOException {
        for (int redirects = 0; redirects <= 2; redirects++) {
            int timeout = requestTimeout(deadline);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setRequestProperty("User-Agent", "TractorGram");
                if (!asset) {
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                }
                int status = connection.getResponseCode();
                if (asset && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        return null;
                    }
                    url = new URL(url, location);
                    String host = url.getHost();
                    if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1 ||
                            !("release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host))) {
                        return null;
                    }
                    continue;
                }
                if (asset && (status == 404 || status == 410)) {
                    return null;
                }
                if (status != 200) {
                    throw new IOException("Release check HTTP " + status);
                }
                if (connection.getContentLength() > limit) {
                    throw new IOException("Release response too large");
                }
                connection.setReadTimeout(requestTimeout(deadline));
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (output.size() + length > limit || SystemClock.elapsedRealtime() >= deadline) {
                            throw new IOException("Release response exceeded limits");
                        }
                        output.write(buffer, 0, length);
                    }
                    return output.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }

    private static int requestTimeout(long deadline) throws IOException {
        int timeout = (int) Math.min(TIMEOUT_MILLIS, deadline - SystemClock.elapsedRealtime());
        if (timeout <= 0) {
            throw new IOException("Release check timed out");
        }
        return timeout;
    }
}
