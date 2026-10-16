package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.dark.DarkPolicy.PublishAction;
import edu.harvard.iq.dataverse.dark.DarkPolicy.StatusUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decision table 6.4 of the proposal. */
class DarkPolicyTest {

    @Test
    void onPublishFollowsTable64() {
        assertEquals(PublishAction.NO_ROW, DarkPolicy.onPublish(null));
        assertEquals(PublishAction.PUT, DarkPolicy.onPublish(DarkState.RESERVED));
        assertEquals(PublishAction.PUT, DarkPolicy.onPublish(DarkState.PUBLISHED));
        assertEquals(PublishAction.DEFER, DarkPolicy.onPublish(DarkState.DRAFT), "PUT on D would return 409");
        assertEquals(PublishAction.DEFER, DarkPolicy.onPublish(DarkState.UPDATE), "PUT on U would return 409");
        assertEquals(PublishAction.TOMBSTONED, DarkPolicy.onPublish(DarkState.TOMBSTONE));
    }

    @Test
    void needsResendWhenLatestVersionWasNotSent() {
        assertTrue(DarkPolicy.needsResend("1.0", "2.0"));
        assertTrue(DarkPolicy.needsResend(null, "1.0"), "sent_version cleared after a curation while D/U");
        assertFalse(DarkPolicy.needsResend("2.0", "2.0"));
        assertFalse(DarkPolicy.needsResend("1.0", null), "no published version left");
    }

    @Test
    void publishedResetsAttemptsAndError() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.DRAFT, 7, DarkState.PUBLISHED, "old error", 20);
        assertEquals(DarkState.PUBLISHED, u.state());
        assertEquals(0, u.attempts());
        assertNull(u.lastError());
        assertTrue(u.changed());
    }

    @Test
    void stillPendingIncrementsAttempts() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.UPDATE, 3, DarkState.UPDATE, null, 20);
        assertEquals(DarkState.UPDATE, u.state());
        assertEquals(4, u.attempts());
        assertNull(u.lastError());
        assertFalse(u.changed());
    }

    @Test
    void reachingMaxAttemptsMarksStuck() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.DRAFT, 19, DarkState.DRAFT, null, 20);
        assertEquals(20, u.attempts());
        assertTrue(DarkPolicy.isStuck(u.lastError()), u.lastError());
        assertTrue(u.lastError().contains("/worker/errors"));
    }

    @Test
    void tombstoneReportedByMinterIsKept() {
        StatusUpdate u = DarkPolicy.afterStatusCheck(DarkState.DRAFT, 2, DarkState.TOMBSTONE, null, 20);
        assertEquals(DarkState.TOMBSTONE, u.state());
    }

    @Test
    void tombstoneOnDestroy() {
        assertTrue(DarkPolicy.tombstoneOnDestroy(true, false), "published datasets are always tombstoned");
        assertTrue(DarkPolicy.tombstoneOnDestroy(false, true));
        assertFalse(DarkPolicy.tombstoneOnDestroy(false, false), "tombstone-unpublished-drafts=false");
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
