package edu.harvard.iq.dataverse.dark;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DarkJob keeps static state, so these tests must not run in parallel with each other. */
@Isolated
class DarkJobTest {

    private static final Instant T0 = Instant.parse("2026-10-02T12:00:00Z");

    @BeforeEach
    @AfterEach
    void reset() {
        DarkJob.reset();
    }

    private static Clock at(Duration offset) {
        return Clock.fixed(T0.plus(offset), ZoneOffset.UTC);
    }

    @Test
    void onlyOneJobAtATime() {
        DarkJob first = DarkJob.tryStart("refresh", at(Duration.ZERO));
        assertNotNull(first);
        assertNull(DarkJob.tryStart("assign-all", at(Duration.ofMinutes(5))), "first job is still active");
        assertSame(first, DarkJob.last());
    }

    @Test
    void finishedJobAllowsANewOne() {
        DarkJob first = DarkJob.tryStart("refresh", at(Duration.ZERO));
        first.done("ok");
        DarkJob second = DarkJob.tryStart("refresh", at(Duration.ofMinutes(1)));
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    void abandonedJobIsReplaced() {
        DarkJob hung = DarkJob.tryStart("refresh", at(Duration.ZERO));
        DarkJob next = DarkJob.tryStart("refresh", at(DarkJob.STALE_AFTER.plusMinutes(1)));

        assertNotNull(next, "a job without progress for longer than STALE_AFTER no longer blocks");
        assertEquals(DarkJob.State.FAILED, hung.getState());
        assertTrue(hung.toJson().build().getString("message").startsWith("abandoned"));
        assertSame(next, DarkJob.last());
    }

    @Test
    void progressKeepsALongJobAlive() {
        // The job's own clock drives lastActivity: progress at minute 25 keeps it alive at minute 40.
        DarkJob longJob = DarkJob.tryStart("assign-all", at(Duration.ofMinutes(25)));
        longJob.inc("reserved");
        assertNull(DarkJob.tryStart("refresh", at(Duration.ofMinutes(40))), "15 minutes since the last progress");
        assertEquals(DarkJob.State.RUNNING, longJob.getState());
    }

    @Test
    void lateCompletionDoesNotOverwriteAbandoned() {
        DarkJob hung = DarkJob.tryStart("refresh", at(Duration.ZERO));
        DarkJob.tryStart("refresh", at(DarkJob.STALE_AFTER.plusMinutes(1)));
        hung.done("woke up later");
        assertEquals(DarkJob.State.FAILED, hung.getState(), "the first outcome wins");
    }
}
