package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.dark.DarkPolicy.PublishAction;
import edu.harvard.iq.dataverse.dark.DarkPolicy.StatusUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decision rules of {@link DarkPolicy}. */
class DarkPolicyTest {

    @Test
    void onPublishDependsOnTheState() {
        assertEquals(PublishAction.PUT, DarkPolicy.onPublish(DarkState.RESERVED));
        assertEquals(PublishAction.PUT, DarkPolicy.onPublish(DarkState.PUBLISHED));
        assertEquals(PublishAction.DEFER, DarkPolicy.onPublish(DarkState.DRAFT), "PUT on D would return 409");
        assertEquals(PublishAction.DEFER, DarkPolicy.onPublish(DarkState.UPDATE), "PUT on U would return 409");
        assertEquals(PublishAction.TOMBSTONED, DarkPolicy.onPublish(DarkState.TOMBSTONE));
    }

    @Test
    void publishedResetsAttemptsAndError() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.DRAFT, 7, DarkState.PUBLISHED, "old error", 20);
        assertEquals(DarkState.PUBLISHED, u.state());
        assertEquals(0, u.attempts());
        assertNull(u.lastError());
    }

    @Test
    void stillPendingIncrementsAttempts() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.UPDATE, 3, DarkState.UPDATE, null, 20);
        assertEquals(DarkState.UPDATE, u.state());
        assertEquals(4, u.attempts());
        assertNull(u.lastError());
    }

    @Test
    void reachingMaxAttemptsMarksStuck() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.DRAFT, 19, DarkState.DRAFT, null, 20);
        assertEquals(20, u.attempts());
        assertTrue(u.lastError().startsWith(DarkPolicy.STUCK_PREFIX), u.lastError());
        assertTrue(u.lastError().contains("/worker/errors"));
    }

    @Test
    void aRowIsStuckOnceItReachesMaxAttempts() {
        assertFalse(DarkPolicy.isStuck(DarkState.DRAFT, 19, 20));
        assertTrue(DarkPolicy.isStuck(DarkState.DRAFT, 20, 20), "status checks that never reached P");
        assertTrue(DarkPolicy.isStuck(DarkState.RESERVED, 25, 20), "a PUT the Minter keeps rejecting");
        assertFalse(DarkPolicy.isStuck(DarkState.TOMBSTONE, 25, 20), "nothing left to retry");
    }

    @Test
    void exhaustedOtherIdRetriesPointToAssign() {
        String error = DarkPolicy.otherIdExhausted(3);
        assertTrue(error.startsWith(DarkPolicy.OTHER_ID_PREFIX), error);
        assertTrue(error.contains("3/3") && error.contains("assign/{id}"), error);
    }

    @Test
    void tombstoneReportedByMinterIsKept() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.DRAFT, 2, DarkState.TOMBSTONE, null, 20);
        assertEquals(DarkState.TOMBSTONE, u.state());
    }

    @Test
    void otherIdRetriesStopAfterThreeFailures() {
        assertTrue(DarkPolicy.mayAutoCurate(0));
        assertTrue(DarkPolicy.mayAutoCurate(2));
        assertFalse(DarkPolicy.mayAutoCurate(3), "only a manual assign retries");
        assertTrue(DarkPolicy.otherIdFailure(1, "x").startsWith(DarkPolicy.OTHER_ID_PREFIX + "updatecurrent failed (1/3)"));
        assertFalse(DarkPolicy.otherIdFailure(2, "x").contains("assign/{id}"));
        assertTrue(DarkPolicy.otherIdFailure(3, "x").contains("assign/{id}"));
    }

    @Test
    void stateCodes() {
        assertEquals(DarkState.RESERVED, DarkState.fromCode("r"));
        assertEquals(DarkState.UPDATE, DarkState.fromCode("UPDATE"));
        assertTrue(DarkState.DRAFT.isInProgress());
        assertFalse(DarkState.PUBLISHED.isInProgress());
        assertEquals("T", new DarkState.DbConverter().convertToDatabaseColumn(DarkState.TOMBSTONE));
    }
}
