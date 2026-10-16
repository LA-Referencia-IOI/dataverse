package edu.harvard.iq.dataverse.dark;

/**
 * Pure decision rules of the dARK lifecycle (table 6.4 of the proposal), kept apart
 * from persistence and HTTP so they can be unit tested.
 */
public final class DarkPolicy {

    /** Prefix of {@code last_error} for ARKs whose status checks reached {@code max-attempts}. */
    public static final String STUCK_PREFIX = "STUCK: ";
    /** Prefix of {@code last_error} for failed tombstone requests (retried by the refresh process). */
    public static final String TOMBSTONE_PREFIX = "TOMBSTONE: ";
    /** Prefix of {@code last_error} when the Other ID could not be added to a published version yet. */
    public static final String OTHER_ID_PREFIX = "OTHER_ID: ";

    /** Consecutive automatic updatecurrent failures after which only a manual assign retries. */
    public static final int OTHER_ID_MAX_ATTEMPTS = 3;

    /**
     * @return true if an automatic process (publication hook, refresh, assign-all) may try
     *         {@code updatecurrent} to add the missing Other ID; a manual assign always may.
     */
    public static boolean mayAutoCurate(int otherIdAttempts) {
        return otherIdAttempts < OTHER_ID_MAX_ATTEMPTS;
    }

    /** {@code last_error} after an updatecurrent failure ({@code attempt} is 1-based). */
    public static String otherIdFailure(int attempt, String reason) {
        String base = OTHER_ID_PREFIX + "updatecurrent failed (" + attempt + "/" + OTHER_ID_MAX_ATTEMPTS + "): " + reason;
        return attempt >= OTHER_ID_MAX_ATTEMPTS ? base + "; no more automatic retries, use assign/{id}" : base;
    }

    private DarkPolicy() {
    }

    /** What to do with a local row when a version is published or curated. */
    public enum PublishAction {
        /** Send metadata ({@code PUT}): R → D, P → U. */
        PUT,
        /** D/U: the Minter is still processing; a PUT would return 409. The refresh resends later. */
        DEFER,
        /** T: tombstoned ARKs are never reused. */
        TOMBSTONED,
        /** No local row: the assignment process will reserve one. */
        NO_ROW
    }

    public static PublishAction onPublish(DarkState state) {
        if (state == null) {
            return PublishAction.NO_ROW;
        }
        return switch (state) {
            case RESERVED, PUBLISHED -> PublishAction.PUT;
            case DRAFT, UPDATE -> PublishAction.DEFER;
            case TOMBSTONE -> PublishAction.TOMBSTONED;
        };
    }

    /**
     * @return true if the latest published version differs from the one last sent
     *         ({@code sent_version} is null after a curation while D/U, which forces a resend).
     */
    public static boolean needsResend(String sentVersion, String latestPublishedVersion) {
        return latestPublishedVersion != null && !latestPublishedVersion.equals(sentVersion);
    }

    /** Result of applying a status reported by the Minter to a local row. */
    public record StatusUpdate(DarkState state, int attempts, String lastError, boolean changed) {
    }

    /**
     * Applies a status check (refresh) to a row in D/U.
     *
     * @param current     local state
     * @param attempts    status checks so far without reaching P
     * @param reported    state reported by the Minter
     * @param lastError   current {@code last_error}
     * @param maxAttempts {@code dataverse.dark.max-attempts}
     */
    public static StatusUpdate afterStatusCheck(DarkState current, int attempts, DarkState reported,
            String lastError, int maxAttempts) {
        if (reported == DarkState.PUBLISHED) {
            return new StatusUpdate(DarkState.PUBLISHED, 0, null, current != DarkState.PUBLISHED);
        }
        if (reported == DarkState.TOMBSTONE) {
            return new StatusUpdate(DarkState.TOMBSTONE, attempts, null, true);
        }
        int next = attempts + 1;
        DarkState state = reported == null ? current : reported;
        if (next >= maxAttempts) {
            String msg = STUCK_PREFIX + "still in " + state.getCode() + " after " + next
                    + " status checks; escalate to the Minter team (/worker/errors)";
            return new StatusUpdate(state, next, msg, true);
        }
        return new StatusUpdate(state, next, lastError, state != current);
    }

    /** Whether a destroyed dataset's ARK must be tombstoned. */
    public static boolean tombstoneOnDestroy(boolean wasPublished, boolean tombstoneUnpublishedDrafts) {
        return wasPublished || tombstoneUnpublishedDrafts;
    }

    /** @return true if the row is marked as stuck (status checks exhausted). */
    public static boolean isStuck(String lastError) {
        return lastError != null && lastError.startsWith(STUCK_PREFIX);
    }
}
