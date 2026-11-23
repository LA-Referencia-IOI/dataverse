package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Snapshot of the {@code dataverse.dark.*} settings. Defaults live here; the connection
 * values (URL, authority, NAAN, key directory) have none and must be configured.
 */
public record DarkConfig(
        boolean enabled,
        String minterUrl,
        String authorityId,
        String naan,
        String keyDirectory,
        boolean allowHttp,
        int batchSize,
        String otherIdAgency,
        Target target,
        int maxAttempts,
        int httpTimeoutSeconds,
        String publisher) {

    /** Maximum number of items per batch call accepted by the Minter. */
    public static final int MINTER_MAX_BATCH = 100;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Logs a configuration warning the first time it occurs: settings are read on every operation. */
    static void warnOnce(String message) {
        if (WARNED.add(message)) {
            Logger.getLogger(DarkConfig.class.getCanonicalName()).warning(message);
        }
    }

    public enum Target {
        /**
         * Resolver URL of the dataset's own PID: {@code https://doi.org/<doi>},
         * {@code https://hdl.handle.net/<handle>} or the PermaLink base URL. Does not depend on
         * this installation's hostname. Configured as {@code pid}.
         */
        PID,
        /** Landing page of the dataset in this installation ({@code dataverse.siteUrl}). */
        LANDING;

        static Target parse(String value) {
            String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            switch (v) {
                case "landing":
                    return LANDING;
                case "pid":
                case "":
                    return PID;
                default:
                    warnOnce("dataverse.dark.target='" + value + "' is not 'pid' or 'landing'; using 'pid'");
                    return PID;
            }
        }
    }

    public DarkConfig {
        minterUrl = normalizeMinterUrl(minterUrl);
        keyDirectory = notBlank(keyDirectory) ? keyDirectory.trim() : null;
        batchSize = Math.max(1, Math.min(batchSize, MINTER_MAX_BATCH));
        if (!notBlank(otherIdAgency)) {
            otherIdAgency = "dARK";
        }
        if (target == null) {
            target = Target.PID;
        }
        maxAttempts = Math.max(1, maxAttempts);
        httpTimeoutSeconds = Math.max(1, httpTimeoutSeconds);
        publisher = notBlank(publisher) ? publisher.trim() : null;
    }

    /** Reads the current configuration from MicroProfile Config. */
    public static DarkConfig fromSettings() {
        return new DarkConfig(
                JvmSettings.DARK_ENABLED.lookupOptional(Boolean.class).orElse(false),
                JvmSettings.DARK_MINTER_URL.lookupOptional().orElse(null),
                JvmSettings.DARK_AUTHORITY_ID.lookupOptional().orElse(null),
                JvmSettings.DARK_NAAN.lookupOptional().orElse(null),
                JvmSettings.DARK_KEY_DIRECTORY.lookupOptional().orElse(null),
                JvmSettings.DARK_ALLOW_HTTP.lookupOptional(Boolean.class).orElse(false),
                JvmSettings.DARK_BATCH_SIZE.lookupOptional(Integer.class).orElse(MINTER_MAX_BATCH),
                JvmSettings.DARK_OTHER_ID_AGENCY.lookupOptional().orElse(null),
                Target.parse(JvmSettings.DARK_TARGET.lookupOptional().orElse(null)),
                JvmSettings.DARK_MAX_ATTEMPTS.lookupOptional(Integer.class).orElse(20),
                JvmSettings.DARK_HTTP_TIMEOUT_SECONDS.lookupOptional(Integer.class).orElse(30),
                JvmSettings.DARK_PUBLISHER.lookupOptional().orElse(null));
    }

    /**
     * Publisher sent in Level 1: {@code dataverse.dark.publisher} if set, otherwise the
     * name of the root collection.
     */
    public String resolvePublisher(String rootCollectionName) {
        return publisher != null ? publisher : (notBlank(rootCollectionName) ? rootCollectionName.trim() : null);
    }

    /** @return true when the integration is enabled and everything it needs is in place. */
    public boolean isUsable() {
        return enabled && connectionProblem() == null && keyPath() != null && DarkKeyStore.exists(keyPath());
    }

    /**
     * @return why dARK stays inactive although {@code dataverse.dark.enabled=true}, or null if
     *         it is not enabled or nothing is wrong
     */
    public String inactiveReason() {
        if (!enabled || isUsable()) {
            return null;
        }
        String problem = connectionProblem();
        if (problem == null) {
            problem = keyPath() == null ? "dataverse.dark.key-directory is missing"
                    : "there is no key in " + keyPath() + "; generate it with POST /api/admin/dark/key";
        }
        return "dataverse.dark.enabled=true but " + problem + "; dARK is inactive";
    }

    /** @return what is wrong with the Minter connection settings, or null. */
    public String connectionProblem() {
        if (!notBlank(minterUrl) || !notBlank(authorityId) || !notBlank(naan)) {
            return "minter-url, authority-id or naan is missing";
        }
        URI url;
        try {
            url = URI.create(minterUrl);
        } catch (IllegalArgumentException e) {
            return "minter-url is not a valid URL";
        }
        if (url.getHost() == null || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            return "minter-url must have a host and no credentials, query or fragment";
        }
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        boolean http = "http".equalsIgnoreCase(url.getScheme());
        if (!https && !(http && (allowHttp || LOOPBACK_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))))) {
            return "minter-url must be HTTPS (HTTP is only accepted for localhost, or with dataverse.dark.allow-http=true in development)";
        }
        return null;
    }

    /** @return the key directory, or null if it is not configured. */
    public Path keyPath() {
        return keyDirectory == null ? null : Path.of(keyDirectory);
    }

    /** Strips trailing slashes and appends {@code /api/v1} if missing. */
    static String normalizeMinterUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.endsWith("/api/v1") ? u : u + "/api/v1";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
