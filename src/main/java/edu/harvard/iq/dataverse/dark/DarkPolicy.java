package edu.harvard.iq.dataverse.dark;

import java.time.Duration;

/**
 * Pure decision rules of the dARK lifecycle, kept apart
 * from persistence and HTTP so they can be unit tested.
 */
public final class DarkPolicy {

    /** Prefix of {@code last_error} for ARKs whose status checks reached {@code max-attempts}. */
    public static final String STUCK_PREFIX = "STUCK: ";
    /** Prefix of {@code last_error} for failed tombstone requests (retried by the refresh process). */
    public static final String TOMBSTONE_PREFIX = "TOMBSTONE: ";
    /** Prefix of {@code last_error} when the Other ID could not be added to a published version yet. */
    public static final String OTHER_ID_PREFIX = "OTHER_ID: ";
    /** Prefix of {@code last_error} for a failure a job did not expect on that row (see the server log). */
    public static final String UNEXPECTED_PREFIX = "UNEXPECTED: ";

    /**
     * A row that reached {@code max-attempts} is retried by the jobs only once per this
     * interval instead of on every run, until an operation on it succeeds.
     */
    public static final Duration SLOW_RETRY_INTERVAL = Duration.ofDays(1);

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

    /** {@code last_error} once the automatic updatecurrent retries are used up. */
    public static String otherIdExhausted(int otherIdAttempts) {
        return OTHER_ID_PREFIX + "missing from the released version; automatic retries exhausted ("
                + otherIdAttempts + "/" + OTHER_ID_MAX_ATTEMPTS + "), use assign/{id}";
    }

    /**
     * @return true if the row reached {@code max-attempts}: status checks that did not reach P,
     *         or operations the Minter rejected. Such a row is retried once per
     *         {@link #SLOW_RETRY_INTERVAL}.
     */
    public static boolean isStuck(DarkState state, int attempts, int maxAttempts) {
        return state != DarkState.TOMBSTONE && attempts >= maxAttempts;
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
        TOMBSTONED
    }

    public static PublishAction onPublish(DarkState state) {
        return switch (state) {
            case RESERVED, PUBLISHED -> PublishAction.PUT;
            case DRAFT, UPDATE -> PublishAction.DEFER;
            case TOMBSTONE -> PublishAction.TOMBSTONED;
        };
    }

    /** Result of applying a status reported by the Minter to a local row. */
    public record StatusUpdate(DarkState state, int attempts, String lastError) {
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
            return new StatusUpdate(DarkState.PUBLISHED, 0, null);
        }
        if (reported == DarkState.TOMBSTONE) {
            return new StatusUpdate(DarkState.TOMBSTONE, attempts, null);
        }
        int next = attempts + 1;
        DarkState state = reported == null ? current : reported;
        if (next >= maxAttempts) {
            String msg = STUCK_PREFIX + "still in " + state.getCode() + " after " + next
                    + " status checks; escalate to the Minter team (/worker/errors)";
            return new StatusUpdate(state, next, msg);
        }
        return new StatusUpdate(state, next, lastError);
    }
}
