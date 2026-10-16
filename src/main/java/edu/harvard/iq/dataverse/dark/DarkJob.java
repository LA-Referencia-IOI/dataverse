package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.json.JsonObjectBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Status of the last long-running dARK operation (refresh, assign-all), exposed by
 * {@code GET /api/admin/dark/job}. One operation at a time per application instance.
 * <p>
 * A job that stays {@code RUNNING} without any progress for {@link #STALE_AFTER} is
 * considered abandoned (e.g. its thread hung): it is marked {@code FAILED} and a new job
 * may start, so a single hang can no longer block the cron until the server restarts.
 * Progress is any counter update, so a long but active assign-all is never cut short.
 */
public final class DarkJob {

    private static final Logger logger = Logger.getLogger(DarkJob.class.getCanonicalName());

    public enum State { RUNNING, DONE, FAILED }

    /** Inactivity after which a RUNNING job is considered abandoned. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private static final AtomicReference<DarkJob> CURRENT = new AtomicReference<>();

    private final String name;
    private final Clock clock;
    private final Instant startedAt;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private volatile State state = State.RUNNING;
    private volatile Instant lastActivity;
    private volatile Instant finishedAt;
    private volatile String message;

    private DarkJob(String name, Clock clock) {
        this.name = name;
        this.clock = clock;
        this.startedAt = clock.instant();
        this.lastActivity = startedAt;
    }

    /** @return a new running job, or null if another one is still running (and not abandoned). */
    public static DarkJob tryStart(String name) {
        return tryStart(name, Clock.systemUTC());
    }

    static DarkJob tryStart(String name, Clock clock) {
        DarkJob job = new DarkJob(name, clock);
        while (true) {
            DarkJob current = CURRENT.get();
            if (current != null && current.state == State.RUNNING) {
                if (!current.isStale(clock.instant())) {
                    return null;
                }
                logger.warning("dARK: job '" + current.name + "' started at " + current.startedAt
                        + " made no progress since " + current.lastActivity + "; considered abandoned");
                current.failed("abandoned: no progress for " + STALE_AFTER.toMinutes() + " minutes");
            }
            if (CURRENT.compareAndSet(current, job)) {
                return job;
            }
        }
    }

    /** @return the last (or running) job, or null if none ran since startup. */
    public static DarkJob last() {
        return CURRENT.get();
    }

    /** For tests: forget the current job. */
    static void reset() {
        CURRENT.set(null);
    }

    boolean isStale(Instant now) {
        return state == State.RUNNING && Duration.between(lastActivity, now).compareTo(STALE_AFTER) > 0;
    }

    public String getName() {
        return name;
    }

    public State getState() {
        return state;
    }

    public synchronized void add(String counter, int delta) {
        counts.merge(counter, delta, Integer::sum);
        lastActivity = clock.instant();
    }

    public void inc(String counter) {
        add(counter, 1);
    }

    public synchronized int get(String counter) {
        return counts.getOrDefault(counter, 0);
    }

    public void done(String msg) {
        finish(State.DONE, msg);
    }

    public void failed(String msg) {
        finish(State.FAILED, msg);
    }

    /** The first outcome wins: a hung thread that wakes up later cannot overwrite "abandoned". */
    private synchronized void finish(State outcome, String msg) {
        if (state != State.RUNNING) {
            return;
        }
        this.message = msg;
        this.finishedAt = clock.instant();
        this.state = outcome;
    }

    public synchronized JsonObjectBuilder toJson() {
        JsonObjectBuilder c = JsonUtil.createObjectBuilder();
        counts.forEach(c::add);
        JsonObjectBuilder b = JsonUtil.createObjectBuilder()
                .add("name", name)
                .add("state", state.name())
                .add("startedAt", startedAt.toString())
                .add("lastActivity", lastActivity.toString())
                .add("stale", isStale(clock.instant()))
                .add("counts", c);
        if (finishedAt != null) {
            b.add("finishedAt", finishedAt.toString());
        }
        if (message != null) {
            b.add("message", message);
        }
        return b;
    }
}
