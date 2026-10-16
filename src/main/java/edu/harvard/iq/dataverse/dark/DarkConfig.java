package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.settings.JvmSettings;

/**
 * Snapshot of the {@code dataverse.dark.*} settings. Defaults live here; connection
 * values (URL, authority, NAAN, token) never have defaults and must be configured.
 */
public record DarkConfig(
        boolean enabled,
        String minterUrl,
        String authorityId,
        String naan,
        String apiToken,
        boolean authorityHeaderEnabled,
        String authorityHeader,
        int batchSize,
        String otherIdAgency,
        Target target,
        int maxAttempts,
        int httpTimeoutSeconds,
        boolean tombstoneUnpublishedDrafts,
        String publisher) {

    /** Maximum number of items per batch call accepted by the Minter. */
    public static final int MINTER_MAX_BATCH = 100;

    public enum Target {
        /**
         * Resolver URL of the dataset's own PID: {@code https://doi.org/<doi>},
         * {@code https://hdl.handle.net/<handle>} or the PermaLink base URL. Does not depend on
         * this installation's hostname. Configured as {@code pid} (or {@code doi}, kept for
         * compatibility).
         */
        PID,
        /** Landing page of the dataset in this installation ({@code dataverse.siteUrl}). */
        LANDING;

        static Target parse(String value) {
            String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            switch (v) {
                case "landing":
                    return LANDING;
                case "pid":
                case "doi":
                case "":
                    return PID;
                default:
                    java.util.logging.Logger.getLogger(DarkConfig.class.getCanonicalName())
                            .warning("dataverse.dark.target='" + value + "' is not 'pid' or 'landing'; using 'pid'");
                    return PID;
            }
        }
    }

    public DarkConfig {
        minterUrl = normalizeMinterUrl(minterUrl);
        batchSize = Math.max(1, Math.min(batchSize, MINTER_MAX_BATCH));
        if (authorityHeader == null || authorityHeader.isBlank()) {
            authorityHeader = "X-Authority-Id";
        }
        if (otherIdAgency == null || otherIdAgency.isBlank()) {
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
                JvmSettings.DARK_API_TOKEN.lookupOptional().orElse(null),
                JvmSettings.DARK_AUTHORITY_HEADER_ENABLED.lookupOptional(Boolean.class).orElse(true),
                JvmSettings.DARK_AUTHORITY_HEADER.lookupOptional().orElse("X-Authority-Id"),
                JvmSettings.DARK_BATCH_SIZE.lookupOptional(Integer.class).orElse(MINTER_MAX_BATCH),
                JvmSettings.DARK_OTHER_ID_AGENCY.lookupOptional().orElse("dARK"),
                Target.parse(JvmSettings.DARK_TARGET.lookupOptional().orElse("pid")),
                JvmSettings.DARK_MAX_ATTEMPTS.lookupOptional(Integer.class).orElse(20),
                JvmSettings.DARK_HTTP_TIMEOUT_SECONDS.lookupOptional(Integer.class).orElse(30),
                JvmSettings.DARK_TOMBSTONE_UNPUBLISHED_DRAFTS.lookupOptional(Boolean.class).orElse(true),
                JvmSettings.DARK_PUBLISHER.lookupOptional().orElse(null));
    }

    /**
     * Publisher sent in Level 1: {@code dataverse.dark.publisher} if set, otherwise the
     * name of the root collection (decision 14.6).
     */
    public String resolvePublisher(String rootCollectionName) {
        return publisher != null ? publisher : (notBlank(rootCollectionName) ? rootCollectionName.trim() : null);
    }

    /** @return true when the integration is enabled and the Minter connection is fully configured. */
    public boolean isUsable() {
        return enabled && notBlank(minterUrl) && notBlank(authorityId) && notBlank(naan);
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

    @Override
    public String toString() {
        // Never print the token.
        return "DarkConfig[enabled=" + enabled + ", minterUrl=" + minterUrl + ", authorityId=" + authorityId
                + ", naan=" + naan + ", apiToken=" + (notBlank(apiToken) ? "***" : "none") + "]";
    }
}
