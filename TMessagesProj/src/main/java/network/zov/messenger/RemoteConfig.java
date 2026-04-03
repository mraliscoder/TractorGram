package network.zov.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RemoteConfig {

    private static final String PREFS_NAME = "zov_remote_config";
    public static final String KEY_BUILT_IN_ENABLED = "built_in_proxies_enabled";
    private static final String KEY_CACHED_PROXIES = "cached_proxies";
    private static final String KEY_PROXY_INDEX = "current_proxy_index";

    private static final String REMOTE_CONFIG_URL = "https://config.zov.network/remote_config";
    private static final String FREE_PROXIES_URL = "https://config.zov.network/free_proxies";
    private static final long PROXY_FAIL_TIMEOUT_MS = 30_000;

    public static volatile List<Long> checkmarks = new ArrayList<>();
    public static volatile String latestVersion = "";
    public static volatile String officialChannel = "";
    public static volatile List<FreeProxy> freeProxies = new ArrayList<>();

    /** Set by LaunchActivity to receive an update-available callback on the main thread. */
    public static Runnable onVersionOutdated;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static ScheduledExecutorService scheduler;

    private static volatile long proxyFailedSince = 0;
    private static volatile int currentProxyIndex = 0;

    private static final NotificationCenter.NotificationCenterDelegate connectionObserver =
            (id, account, args) -> {
                if (id == NotificationCenter.didUpdateConnectionState) {
                    int state = ConnectionsManager.getInstance(account).getConnectionState();
                    onConnectionStateChanged(state);
                }
            };

    // -------------------------------------------------------------------------
    // Public data class
    // -------------------------------------------------------------------------

    public static class FreeProxy {
        public final String id;
        public final String host;
        public final int port;
        public final String secret;

        public FreeProxy(String id, String host, int port, String secret) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.secret = secret;
        }

        public SharedConfig.ProxyInfo toProxyInfo() {
            return new SharedConfig.ProxyInfo(host, port, "", "", secret);
        }
    }

    // -------------------------------------------------------------------------
    // Preferences helpers
    // -------------------------------------------------------------------------

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isBuiltInProxiesEnabled() {
        return prefs().getBoolean(KEY_BUILT_IN_ENABLED, true);
    }

    /**
     * Toggle built-in proxies on/off. Persists the preference and applies or removes the proxy.
     */
    public static void setBuiltInProxiesEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_BUILT_IN_ENABLED, enabled).apply();
        if (enabled) {
            applyCurrentBuiltInProxy();
        } else {
            disableBuiltInProxy();
        }
    }

    /**
     * Persist the disabled state and clear currentProxy without triggering a connection change
     * or notifications. Use this when the caller will set up the proxy connection itself.
     */
    public static void disableBuiltInProxySilently() {
        prefs().edit().putBoolean(KEY_BUILT_IN_ENABLED, false).apply();
        SharedConfig.currentProxy = null;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public static void start() {
        currentProxyIndex = prefs().getInt(KEY_PROXY_INDEX, 0);

        // Apply cached proxy immediately so the app connects on restart.
        if (isBuiltInProxiesEnabled()) {
            List<FreeProxy> cached = loadCachedProxies();
            if (!cached.isEmpty()) {
                freeProxies = cached;
                applyCurrentBuiltInProxy();
            }
        }

        // Observe connection state for account 0 to detect proxy failures.
        NotificationCenter.getInstance(0).addObserver(connectionObserver, NotificationCenter.didUpdateConnectionState);

        fetchRemoteConfig();
        fetchFreeProxies();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ZovProxyPoller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(RemoteConfig::fetchFreeProxies, 10, 10, TimeUnit.MINUTES);
    }

    // -------------------------------------------------------------------------
    // Network fetches
    // -------------------------------------------------------------------------

    private static void fetchRemoteConfig() {
        new Thread(() -> {
            try {
                JSONObject json = fetchJson(REMOTE_CONFIG_URL);
                if (json == null) return;

                List<Long> newCheckmarks = new ArrayList<>();
                JSONArray arr = json.optJSONArray("checkmarks");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        newCheckmarks.add(arr.getLong(i));
                    }
                }
                checkmarks = newCheckmarks;
                latestVersion = json.optString("latestVersion", "");
                officialChannel = json.optString("officialChannel", "");

                if (isVersionOutdated()) {
                    mainHandler.post(() -> {
                        if (onVersionOutdated != null) {
                            onVersionOutdated.run();
                        }
                    });
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }, "ZovRemoteConfigFetch").start();
    }

    private static void fetchFreeProxies() {
        new Thread(() -> {
            try {
                JSONObject json = fetchJson(FREE_PROXIES_URL);
                if (json == null) return;

                List<FreeProxy> proxies = new ArrayList<>();
                JSONArray arr = json.optJSONArray("proxies");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.getJSONObject(i);
                        proxies.add(new FreeProxy(
                                p.optString("id"),
                                p.optString("host"),
                                p.optInt("port", 443),
                                p.optString("secret")));
                    }
                }
                if (!proxies.isEmpty()) {
                    freeProxies = proxies;
                    saveCachedProxies(proxies);
                    mainHandler.post(() -> {
                        if (isBuiltInProxiesEnabled()) {
                            applyCurrentBuiltInProxy();
                        }
                    });
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }, "ZovFreeProxiesFetch").start();
    }

    private static JSONObject fetchJson(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Proxy management
    // -------------------------------------------------------------------------

    /** Enables the current built-in proxy in the connection layer (does NOT persist the pref). */
    public static void applyCurrentBuiltInProxy() {
        List<FreeProxy> proxies = freeProxies;
        if (proxies.isEmpty()) return;
        int index = currentProxyIndex % proxies.size();
        FreeProxy fp = proxies.get(index);
        SharedConfig.ProxyInfo info = fp.toProxyInfo();
        SharedConfig.currentProxy = info;
        ConnectionsManager.setProxySettings(true, info.address, info.port, info.username, info.password, info.secret);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    /** Disables the proxy in the connection layer and clears currentProxy (does NOT persist the pref). */
    public static void disableBuiltInProxy() {
        SharedConfig.currentProxy = null;
        ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    // -------------------------------------------------------------------------
    // Proxy health monitoring (called on main thread via NotificationCenter)
    // -------------------------------------------------------------------------

    private static void onConnectionStateChanged(int state) {
        if (!isBuiltInProxiesEnabled()) {
            proxyFailedSince = 0;
            return;
        }
        List<FreeProxy> proxies = freeProxies;
        if (proxies.size() <= 1) {
            proxyFailedSince = 0;
            return;
        }

        boolean connected = state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating;

        if (!connected) {
            if (proxyFailedSince == 0) {
                proxyFailedSince = SystemClock.elapsedRealtime();
            } else if (SystemClock.elapsedRealtime() - proxyFailedSince >= PROXY_FAIL_TIMEOUT_MS) {
                proxyFailedSince = 0;
                currentProxyIndex = (currentProxyIndex + 1) % proxies.size();
                prefs().edit().putInt(KEY_PROXY_INDEX, currentProxyIndex).apply();
                applyCurrentBuiltInProxy();
            }
        } else {
            proxyFailedSince = 0;
        }
    }

    // -------------------------------------------------------------------------
    // Version check
    // -------------------------------------------------------------------------

    public static boolean isVersionOutdated() {
        if (latestVersion.isEmpty()) return false;
        try {
            return compareVersions(BuildVars.BUILD_VERSION_STRING, latestVersion) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int a = i < p1.length ? safeParseInt(p1[i]) : 0;
            int b = i < p2.length ? safeParseInt(p2[i]) : 0;
            if (a != b) return a - b;
        }
        return 0;
    }

    private static int safeParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Proxy cache (SharedPreferences)
    // -------------------------------------------------------------------------

    private static void saveCachedProxies(List<FreeProxy> proxies) {
        try {
            JSONArray arr = new JSONArray();
            for (FreeProxy p : proxies) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("host", p.host);
                o.put("port", p.port);
                o.put("secret", p.secret);
                arr.put(o);
            }
            prefs().edit().putString(KEY_CACHED_PROXIES, arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static List<FreeProxy> loadCachedProxies() {
        List<FreeProxy> result = new ArrayList<>();
        try {
            String json = prefs().getString(KEY_CACHED_PROXIES, null);
            if (json == null) return result;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new FreeProxy(
                        o.optString("id"),
                        o.optString("host"),
                        o.optInt("port", 443),
                        o.optString("secret")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }
}
