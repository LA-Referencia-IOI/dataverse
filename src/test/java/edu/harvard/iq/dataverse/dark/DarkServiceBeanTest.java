package edu.harvard.iq.dataverse.dark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.io.TempDir;
import edu.harvard.iq.dataverse.dark.DarkRepository.ArkRow;
import edu.harvard.iq.dataverse.dark.DarkRepository.OtherIdStatus;
import edu.harvard.iq.dataverse.dark.DarkRepository.PutPlan;
import edu.harvard.iq.dataverse.dark.DarkRepository.Reservation;
import edu.harvard.iq.dataverse.dark.DarkServiceBean.SendResult;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.json.JsonUtil;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import jakarta.ejb.EJBException;
import jakarta.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration of {@link DarkServiceBean} with the table ({@link DarkRepository}), the
 * Minter client and {@code updatecurrent} replaced by doubles.
 */
@Isolated // DarkJob keeps the current job in a static field
@LocalJvmSettings
@JvmSetting(key = JvmSettings.DARK_ENABLED, value = "true")
@JvmSetting(key = JvmSettings.DARK_MINTER_URL, value = "https://minter.test")
@JvmSetting(key = JvmSettings.DARK_AUTHORITY_ID, value = "auth-uuid")
@JvmSetting(key = JvmSettings.DARK_NAAN, value = "12345")
class DarkServiceBeanTest {

    private static final Long ROW = 1L;
    private static final Long DATASET = 7L;
    private static final String ARK = "ark:12345/a";
    private static final PutPlan PLAN = new PutPlan(ARK, "https://doi.org/10.5072/fk2/a",
            JsonUtil.createObjectBuilder().add("ark", ARK).build(), "<xml/>", "1.0", 10L, 3L);
    private static final JsonObject ROW_JSON = JsonUtil.createObjectBuilder().add("id", ROW).build();

    private static final String KEY_DIRECTORY_SETTING = JvmSettings.DARK_KEY_DIRECTORY.getScopedKey();

    @TempDir
    Path root;

    private DarkRepository rows;
    private DarkClient client;
    private Service service;

    /** The service with the Minter client and {@code updatecurrent} under the test's control. */
    private class Service extends DarkServiceBean {
        boolean curationResult = true;
        RuntimeException curationFailure;
        int curations;

        @Override
        DarkClient client() {
            return client;
        }

        @Override
        boolean curateWithOtherId(Long datasetId, String ark) {
            curations++;
            if (curationFailure != null) {
                throw curationFailure;
            }
            return curationResult;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path keys = root.resolve("keys").resolve("dark");
        DarkKeyStore.generate(keys, false);
        System.setProperty(KEY_DIRECTORY_SETTING, keys.toString());
        DarkJob.reset();
        rows = mock(DarkRepository.class);
        client = mock(DarkClient.class);
        service = new Service();
        service.rows = rows;
        service.report = mock(DarkReportBean.class);
        when(service.report.rowJson(ROW)).thenReturn(ROW_JSON);
        when(rows.findIdByDatasetId(DATASET)).thenReturn(ROW);
        when(client.authority()).thenReturn(new DarkClient.AuthorityInfo(true, List.of("12345")));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(KEY_DIRECTORY_SETTING);
        DarkJob.reset();
    }

    private static Dark row(DarkState state, String ark) {
        Dark row = new Dark();
        row.setStatus(state);
        row.setArk(ark);
        row.setClientItemId("doi:10.5072/fk2/a");
        return row;
    }

    private static OtherIdStatus released(boolean otherIdPresent, boolean blocked, int otherIdAttempts) {
        return new OtherIdStatus(DATASET, ARK, true, otherIdPresent, blocked, otherIdAttempts);
    }

    private void minterAcceptsPut() throws DarkException {
        when(rows.preparePut(eq(ROW), any())).thenReturn(PLAN);
        when(client.put(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DarkClient.PutResult(new DarkArkResponse(ARK, DarkState.DRAFT, null, null, null, null, null), false));
    }

    private void minterRejectsPut(int httpStatus) throws DarkException {
        when(rows.preparePut(eq(ROW), any())).thenReturn(PLAN);
        when(client.put(any(), any(), any(), any(), any(), any())).thenThrow(new DarkException("not accepted", httpStatus));
    }

    private void oneTombstoneCandidate() throws DarkException {
        when(rows.tombstoneCandidates(anyInt())).thenReturn(List.of(new ArkRow(4L, "ark:12345/d")));
        when(rows.requestTombstone(4L)).thenReturn(true);
        when(client.tombstone("ark:12345/d")).thenReturn(true);
    }

    // ---- jobs: a failure never stops them ----

    @Test
    void refreshContinuesAfterARowFails() throws Exception {
        when(rows.idsNeedingResend(anyInt())).thenReturn(List.of(1L, 2L));
        when(rows.preparePut(eq(1L), any())).thenThrow(new IllegalStateException("boom"));
        when(rows.tombstoneCandidates(anyInt())).thenReturn(List.of(new ArkRow(3L, "ark:12345/c"), new ArkRow(4L, "ark:12345/d")));
        when(rows.requestTombstone(3L)).thenThrow(new EJBException(new IllegalArgumentException("row changed")));
        when(rows.requestTombstone(4L)).thenReturn(true);
        when(client.tombstone("ark:12345/d")).thenReturn(true);

        DarkJob job = DarkJob.tryStart("refresh");
        service.refreshAllAsync(job);

        assertEquals(DarkJob.State.DONE, job.getState());
        assertEquals(2, job.get("unexpectedErrors"));
        verify(rows).preparePut(eq(2L), any());
        assertEquals(1, job.get("tombstoned"), "later steps still ran");
        verify(rows).recordError(1L, "UNEXPECTED: IllegalStateException: boom");
        verify(rows).recordError(3L, "UNEXPECTED: IllegalArgumentException: row changed");
        verify(rows).recordTombstone(4L);
    }

    @Test
    void refreshContinuesWhenTheStatusCallFailsUnexpectedly() throws Exception {
        when(rows.pendingStatus(anyInt())).thenReturn(List.of(new ArkRow(1L, ARK), new ArkRow(2L, "ark:12345/b")));
        when(client.statusBatch(any())).thenThrow(new ClassCastException("results is not an array"));
        oneTombstoneCandidate();

        DarkJob job = DarkJob.tryStart("refresh");
        service.refreshAllAsync(job);

        assertEquals(DarkJob.State.DONE, job.getState());
        assertEquals(2, job.get("statusErrors"));
        verify(rows).recordError(1L, "ClassCastException: results is not an array");
        verify(rows).recordError(2L, "ClassCastException: results is not an array");
        assertEquals(1, job.get("tombstoned"), "later steps still ran");
    }

    @Test
    void refreshRecordsEachStatus() throws Exception {
        DarkClient.StatusResult published = new DarkClient.StatusResult(
                new DarkArkResponse(ARK, DarkState.PUBLISHED, null, null, null, null, null), null, null);
        DarkClient.StatusResult notFound = new DarkClient.StatusResult(null, "not_found", "no such ark");
        when(rows.pendingStatus(anyInt())).thenReturn(List.of(new ArkRow(1L, ARK), new ArkRow(2L, "ark:12345/b"), new ArkRow(3L, "ark:12345/c")));
        when(client.statusBatch(List.of(ARK, "ark:12345/b", "ark:12345/c"))).thenReturn(Map.of(ARK, published, "ark:12345/b", notFound));
        when(rows.recordStatus(eq(1L), eq(published), anyInt())).thenReturn(DarkState.PUBLISHED);
        when(rows.recordStatus(eq(2L), eq(notFound), anyInt())).thenReturn(DarkState.DRAFT);
        when(rows.recordStatus(eq(3L), isNull(), anyInt())).thenReturn(DarkState.UPDATE);

        DarkJob job = DarkJob.tryStart("refresh");
        service.refreshAllAsync(job);

        assertEquals(3, job.get("checked"));
        assertEquals(1, job.get("published"));
        assertEquals(2, job.get("statusErrors"), "an error result and a missing result");
    }

    @Test
    void refreshContinuesWhenAStepFails() throws Exception {
        when(rows.idsNeedingResend(anyInt())).thenThrow(new EJBException("query failed"));
        oneTombstoneCandidate();

        DarkJob job = DarkJob.tryStart("refresh");
        service.refreshAllAsync(job);

        assertEquals(DarkJob.State.DONE, job.getState());
        assertEquals(1, job.get("unexpectedErrors"));
        assertEquals(1, job.get("tombstoned"));
    }

    @Test
    void refreshSurvivesWhenTheErrorCannotBeStored() throws Exception {
        when(rows.idsNeedingResend(anyInt())).thenReturn(List.of(1L));
        when(rows.preparePut(eq(1L), any())).thenThrow(new IllegalStateException("boom"));
        doThrow(new EJBException("database down")).when(rows).recordError(anyLong(), anyString());

        DarkJob job = DarkJob.tryStart("refresh");
        service.refreshAllAsync(job);

        assertEquals(DarkJob.State.DONE, job.getState());
        assertEquals(1, job.get("unexpectedErrors"));
    }

    @Test
    void refreshSendsARowOnlyOncePerRun() throws Exception {
        when(rows.idsNeedingResend(anyInt())).thenReturn(List.of(ROW));
        when(rows.idsMissingOtherId(anyInt())).thenReturn(List.of(ROW));
        minterAcceptsPut();

        DarkJob job = DarkJob.tryStart("refresh");
        service.refreshAllAsync(job);

        assertEquals(1, job.get("resent"));
        assertEquals(0, job.get("otherIdMissing"));
        verify(client).put(any(), any(), any(), any(), any(), any());
    }

    @Test
    void assignAllContinuesAfterABatchFails() {
        when(rows.localDatasetsWithoutRow(anyInt(), eq(0L))).thenReturn(List.of(10L, 11L));
        when(rows.localDatasetsWithoutRow(anyInt(), eq(11L))).thenReturn(List.of(12L));
        when(rows.reserveCandidates(List.of(10L, 11L))).thenThrow(new EJBException("lock timeout"));

        DarkJob job = DarkJob.tryStart("assign-all");
        service.assignAllAsync(job, 0);

        assertEquals(DarkJob.State.DONE, job.getState());
        assertEquals(3, job.get("candidates"), "the second batch was still read");
        assertEquals(1, job.get("unexpectedErrors"));
        verify(rows).reserveCandidates(List.of(12L));
    }

    @Test
    void assignAllStopsAtItsLimit() {
        when(rows.localDatasetsWithoutRow(eq(2), eq(0L))).thenReturn(List.of(10L, 11L));

        DarkJob job = DarkJob.tryStart("assign-all");
        service.assignAllAsync(job, 2);

        assertEquals(2, job.get("candidates"));
        verify(rows, never()).localDatasetsWithoutRow(anyInt(), eq(11L));
    }

    @Test
    void aJobThatCannotListItsWorkIsMarkedFailed() {
        when(rows.localDatasetsWithoutRow(anyInt(), anyLong())).thenThrow(new EJBException(new IllegalStateException("database down")));

        DarkJob job = DarkJob.tryStart("assign-all");
        service.assignAllAsync(job, 0);

        assertEquals(DarkJob.State.FAILED, job.getState());
    }

    // ---- metadata ----

    @Test
    void acceptedMetadataIsRecorded() throws Exception {
        minterAcceptsPut();
        assertEquals(SendResult.SENT, service.sendMetadata(ROW));
        verify(rows).recordPut(eq(ROW), any(), eq(PLAN));
    }

    @Test
    void a409MeansTheMinterIsStillProcessing() throws Exception {
        when(rows.preparePut(eq(ROW), any())).thenReturn(PLAN);
        when(client.put(any(), any(), any(), any(), any(), any())).thenReturn(new DarkClient.PutResult(null, true));
        assertEquals(SendResult.IN_PROGRESS, service.sendMetadata(ROW));
    }

    @Test
    void nothingIsSentWithoutAPlan() throws Exception {
        assertEquals(SendResult.NOTHING_TO_SEND, service.sendMetadata(ROW));
        verifyNoInteractions(client);
    }

    @Test
    void aRejectionCountsTowardsMaxAttempts() throws Exception {
        minterRejectsPut(422);
        assertEquals(SendResult.FAILED, service.sendMetadata(ROW));
        verify(rows).recordRejection(ROW, "HTTP 422: not accepted");
        verify(rows, never()).recordError(anyLong(), anyString());
    }

    @Test
    void aFailingOrUnreachableMinterDoesNotCountTowardsMaxAttempts() throws Exception {
        minterRejectsPut(503);
        assertEquals(SendResult.FAILED, service.sendMetadata(ROW));
        verify(rows).recordError(ROW, "HTTP 503: not accepted");

        doThrow(new DarkException("Minter unreachable")).when(client).put(any(), any(), any(), any(), any(), any());
        assertEquals(SendResult.FAILED, service.sendMetadata(ROW));
        verify(rows).recordError(ROW, "Minter unreachable");
        verify(rows, never()).recordRejection(anyLong(), anyString());
    }

    @Test
    void aRefusedSignatureDoesNotCountAgainstTheRow() throws Exception {
        minterRejectsPut(401);
        assertEquals(SendResult.FAILED, service.sendMetadata(ROW));
        verify(rows).recordError(ROW, "HTTP 401: not accepted");
        verify(rows, never()).recordRejection(anyLong(), anyString());
    }

    @Test
    void metadataThatCannotBeBuiltIsAFailureOfThatRow() throws Exception {
        when(rows.preparePut(eq(ROW), any())).thenThrow(new DarkException("Could not build oai_datacite metadata: boom"));
        assertEquals(SendResult.FAILED, service.sendMetadata(ROW));
        verify(rows).recordError(ROW, "Could not build oai_datacite metadata: boom");
        verifyNoInteractions(client);
    }

    // ---- tombstones ----

    @Test
    void tombstoneIsRecordedWhenTheMinterConfirms() throws Exception {
        when(rows.requestTombstone(ROW)).thenReturn(true);
        when(client.tombstone(ARK)).thenReturn(true);
        assertTrue(service.tombstone(ROW, ARK));
        verify(rows).recordTombstone(ROW);
    }

    @Test
    void aLockedArkKeepsItsTombstonePending() throws Exception {
        when(rows.requestTombstone(ROW)).thenReturn(true);
        when(client.tombstone(ARK)).thenReturn(false);
        assertFalse(service.tombstone(ROW, ARK));
        verify(rows).recordError(eq(ROW), startsWith("TOMBSTONE: HTTP 409"));
        verify(rows, never()).recordTombstone(anyLong());
    }

    @Test
    void aRejectedTombstoneCountsTowardsMaxAttempts() throws Exception {
        when(rows.requestTombstone(ROW)).thenReturn(true);
        when(client.tombstone(ARK)).thenThrow(new DarkException("ARK not found", 404));
        assertFalse(service.tombstone(ROW, ARK));
        verify(rows).recordRejection(ROW, "TOMBSTONE: HTTP 404: ARK not found");
    }

    @Test
    void anAlreadyTombstonedRowIsNotSentAgain() {
        assertFalse(service.tombstone(ROW, ARK));
        verifyNoInteractions(client);
    }

    @Test
    void aDestroyedDatasetIsTombstoned() throws Exception {
        when(rows.requestTombstoneOfDestroyed("doi:10.5072/fk2/a")).thenReturn(new ArkRow(ROW, ARK));
        when(rows.requestTombstone(ROW)).thenReturn(true);
        when(client.tombstone(ARK)).thenReturn(true);

        service.tombstoneDestroyedAsync("doi:10.5072/fk2/a");

        verify(rows).recordTombstone(ROW);
    }

    // ---- released version: Other ID, then metadata ----

    @Test
    void anUnreleasedDatasetSendsNothing() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(new OtherIdStatus(DATASET, ARK, false, false, false, 0));
        service.publishAsync(DATASET, false);
        verify(rows, never()).preparePut(anyLong(), any());
        assertEquals(0, service.curations);
    }

    @Test
    void metadataIsSentWhenTheOtherIdIsPresent() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(true, false, 2));
        minterAcceptsPut();

        service.publishAsync(DATASET, false);

        verify(rows).recordPut(eq(ROW), any(), eq(PLAN));
        verify(rows).resetOtherIdAttempts(ROW);
        assertEquals(0, service.curations);
    }

    @Test
    void aMissingOtherIdIsAddedAndItsHookSendsTheMetadata() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, 0));

        service.publishAsync(DATASET, false);

        assertEquals(1, service.curations);
        verify(rows).resetOtherIdAttempts(ROW);
        verify(rows, never()).preparePut(anyLong(), any());
    }

    @Test
    void aDraftOrLockIsNeverTouchedAndIsNotAnError() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, true, 0));
        minterAcceptsPut();

        service.publishAsync(DATASET, false);

        assertEquals(0, service.curations);
        verify(rows).recordPut(eq(ROW), any(), eq(PLAN));
        verify(rows, never()).recordError(anyLong(), anyString());
    }

    @Test
    void aCurationNeverCuratesAgain() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, 0));
        minterAcceptsPut();

        service.publishAsync(DATASET, true);

        assertEquals(0, service.curations);
        verify(rows).recordPut(eq(ROW), any(), eq(PLAN));
    }

    @Test
    void metadataIsSentWhenThereWasNothingToCurate() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, 0));
        service.curationResult = false;
        minterAcceptsPut();

        service.publishAsync(DATASET, false);

        verify(rows).recordPut(eq(ROW), any(), eq(PLAN));
        verify(rows, never()).resetOtherIdAttempts(anyLong());
    }

    @Test
    void aFailedCurationIsCountedAndTheMetadataStillSent() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, 1));
        service.curationFailure = new IllegalStateException("validation failed");
        minterAcceptsPut();

        service.publishAsync(DATASET, false);

        verify(rows).recordPut(eq(ROW), any(), eq(PLAN));
        verify(rows).recordOtherIdFailure(ROW, "IllegalStateException: validation failed", false);
    }

    @Test
    void anOtherIdProblemNeverHidesAFailedPut() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, 1));
        service.curationFailure = new IllegalStateException("validation failed");
        minterRejectsPut(422);

        service.publishAsync(DATASET, false);

        verify(rows).recordRejection(ROW, "HTTP 422: not accepted");
        verify(rows).recordOtherIdFailure(ROW, "IllegalStateException: validation failed", true);
    }

    @Test
    void exhaustedRetriesAreReportedAfterThePut() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, DarkPolicy.OTHER_ID_MAX_ATTEMPTS));
        minterAcceptsPut();

        service.publishAsync(DATASET, false);

        assertEquals(0, service.curations);
        verify(rows).recordError(ROW, DarkPolicy.otherIdExhausted(DarkPolicy.OTHER_ID_MAX_ATTEMPTS));
    }

    @Test
    void exhaustedRetriesDoNotHideAFailedPut() throws Exception {
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, DarkPolicy.OTHER_ID_MAX_ATTEMPTS));
        minterRejectsPut(503);

        service.publishAsync(DATASET, false);

        verify(rows).recordError(ROW, "HTTP 503: not accepted");
        verify(rows, never()).recordError(eq(ROW), startsWith(DarkPolicy.OTHER_ID_PREFIX));
    }

    // ---- one dataset: assign, refresh, tombstone ----

    @Test
    void aManualAssignCuratesEvenWithExhaustedRetries() throws Exception {
        when(rows.find(ROW)).thenReturn(row(DarkState.PUBLISHED, ARK));
        when(rows.otherIdStatus(ROW)).thenReturn(released(false, false, DarkPolicy.OTHER_ID_MAX_ATTEMPTS));

        assertSame(ROW_JSON, service.assign(DATASET));

        assertEquals(1, service.curations);
        verify(rows, atLeastOnce()).resetOtherIdAttempts(ROW);
    }

    @Test
    void assignReservesWhenTheDatasetHasNoRow() throws Exception {
        Reservation claim = new Reservation(ROW, "doi:10.5072/fk2/a", "token");
        DarkArkResponse reserved = new DarkArkResponse(ARK, DarkState.RESERVED, null, "doi:10.5072/fk2/a", null, null, null);
        when(rows.findIdByDatasetId(DATASET)).thenReturn(null);
        when(rows.reserveCandidates(List.of(DATASET))).thenReturn(List.of(new Reservation(ROW, "doi:10.5072/fk2/a", null)));
        when(rows.claimReservation(eq(ROW), anyInt())).thenReturn(claim);
        when(client.reserveBatch(List.of("doi:10.5072/fk2/a"))).thenReturn(new DarkClient.ReserveResult(List.of(reserved), List.of()));
        when(rows.confirmReservation(claim, reserved)).thenReturn(ROW);
        when(rows.find(ROW)).thenReturn(row(DarkState.RESERVED, ARK));

        assertSame(ROW_JSON, service.assign(DATASET));

        verify(rows).releaseReservation(claim);
    }

    @Test
    void assignFailsWhenTheMinterDoesNotReserve() throws Exception {
        Reservation claim = new Reservation(ROW, "doi:10.5072/fk2/a", "token");
        when(rows.findIdByDatasetId(DATASET)).thenReturn(null);
        when(rows.reserveCandidates(List.of(DATASET))).thenReturn(List.of(new Reservation(ROW, "doi:10.5072/fk2/a", null)));
        when(rows.claimReservation(eq(ROW), anyInt())).thenReturn(claim);
        when(client.reserveBatch(any())).thenThrow(new DarkException("blockchain unavailable", 503));

        DarkException e = assertThrows(DarkException.class, () -> service.assign(DATASET));

        assertEquals(502, e.getHttpStatus());
        verify(rows).recordError(ROW, "HTTP 503: blockchain unavailable");
        verify(rows).releaseReservation(claim);
    }

    @Test
    void aReservationTheMinterRejectsCountsTowardsMaxAttempts() throws Exception {
        Reservation claim = new Reservation(ROW, "doi:10.5072/fk2/a", "token");
        when(rows.reserveCandidates(List.of(DATASET))).thenReturn(List.of(new Reservation(ROW, "doi:10.5072/fk2/a", null)));
        when(rows.claimReservation(eq(ROW), anyInt())).thenReturn(claim);
        when(client.reserveBatch(any())).thenReturn(new DarkClient.ReserveResult(List.of(),
                List.of(new DarkClient.ItemError("doi:10.5072/fk2/a", "invalid client_item_id"))));

        assertTrue(service.reserveDatasets(List.of(DATASET), null).isEmpty());

        verify(rows).recordRejection(ROW, "invalid client_item_id");
        verify(rows, never()).confirmReservation(any(), any());
    }

    @Test
    void aRowClaimedByAnotherWorkerIsNotReserved() throws Exception {
        when(rows.reserveCandidates(List.of(DATASET))).thenReturn(List.of(new Reservation(ROW, "doi:10.5072/fk2/a", null)));

        assertTrue(service.reserveDatasets(List.of(DATASET), null).isEmpty());

        verifyNoInteractions(client);
    }

    @Test
    void assignRejectsADatasetThatCannotHaveADark() {
        when(rows.assignabilityProblem(DATASET)).thenReturn("Dataset 7 is harvested");
        DarkException e = assertThrows(DarkException.class, () -> service.assign(DATASET));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void assignRejectsATombstonedArk() {
        when(rows.find(ROW)).thenReturn(row(DarkState.TOMBSTONE, ARK));
        DarkException e = assertThrows(DarkException.class, () -> service.assign(DATASET));
        assertEquals(409, e.getHttpStatus());
        assertEquals(0, service.curations);
    }

    @Test
    void refreshOfADatasetWithoutRowIsNotFound() {
        when(rows.findIdByDatasetId(DATASET)).thenReturn(null);
        DarkException e = assertThrows(DarkException.class, () -> service.refreshDataset(DATASET));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void refreshOfOneDatasetChecksItsStatusIgnoringMaxAttempts() throws Exception {
        DarkClient.StatusResult published = new DarkClient.StatusResult(
                new DarkArkResponse(ARK, DarkState.PUBLISHED, null, null, null, null, null), null, null);
        when(rows.find(ROW)).thenReturn(row(DarkState.DRAFT, ARK));
        when(client.statusBatch(List.of(ARK))).thenReturn(Map.of(ARK, published));
        when(rows.otherIdStatus(ROW)).thenReturn(released(true, false, 0));

        assertSame(ROW_JSON, service.refreshDataset(DATASET));

        verify(rows).recordStatus(ROW, published, Integer.MAX_VALUE);
        verify(rows).preparePut(eq(ROW), any());
    }

    @Test
    void refreshOfOneDatasetRetriesItsPendingTombstone() throws Exception {
        Dark pending = row(DarkState.PUBLISHED, ARK);
        pending.setTombstoneRequested(true);
        when(rows.find(ROW)).thenReturn(pending);
        when(rows.requestTombstone(ROW)).thenReturn(true);
        when(client.tombstone(ARK)).thenReturn(true);

        service.refreshDataset(DATASET);

        verify(rows).recordTombstone(ROW);
        verify(rows, never()).otherIdStatus(anyLong());
    }

    @Test
    void aFailedManualTombstoneIsAnError() throws Exception {
        when(rows.find(ROW)).thenReturn(row(DarkState.PUBLISHED, ARK));
        when(rows.requestTombstone(ROW)).thenReturn(true);
        when(client.tombstone(ARK)).thenReturn(false);

        DarkException e = assertThrows(DarkException.class, () -> service.tombstoneDataset(DATASET));

        assertEquals(502, e.getHttpStatus());
    }

    // ---- signing key ----

    @Test
    void generatingAKeyReturnsOnlyItsPublicHalf() throws Exception {
        Path fresh = root.resolve("keys").resolve("fresh");
        System.setProperty(KEY_DIRECTORY_SETTING, fresh.toString());

        JsonObject json = service.generateKey(false);

        assertEquals(DarkKeyStore.load(fresh).publicKey(), json.getString("publicKey"));
        assertEquals(DarkKeyStore.load(fresh).keyId(), json.getString("keyId"));
        assertEquals("auth-uuid", json.getString("authorityId"));
        assertEquals("https://minter.test/api/v1", json.getString("minterUrl"));
        String privateKey = Base64.getEncoder().encodeToString(Files.readAllBytes(fresh.resolve("private.pk8")));
        assertFalse(json.toString().contains(privateKey));
        verifyNoInteractions(client);
    }

    @Test
    void anExistingKeyIsNotOverwritten() {
        assertEquals(409, assertThrows(DarkException.class, () -> service.generateKey(false)).getHttpStatus());
    }

    @Test
    void theKeyIsNotReplacedWhileDarkIsEnabled() throws Exception {
        String before = service.key().getString("keyId");
        assertEquals(409, assertThrows(DarkException.class, () -> service.generateKey(true)).getHttpStatus());
        assertEquals(before, service.key().getString("keyId"));
    }

    @Test
    @JvmSetting(key = JvmSettings.DARK_ENABLED, value = "false")
    void theKeyIsReplacedWhileDarkIsDisabled() throws Exception {
        String before = service.key().getString("keyId");
        String after = service.generateKey(true).getString("keyId");
        assertFalse(before.equals(after));
        assertEquals(after, service.key().getString("keyId"));
    }

    @Test
    void keyStatusReportsWhatTheMinterSaysAboutTheAuthority() throws Exception {
        when(client.authority()).thenReturn(new DarkClient.AuthorityInfo(true, List.of("99999", "12345")));

        JsonObject minter = service.key().getJsonObject("minter");

        assertTrue(minter.getBoolean("authorityActive"));
        assertTrue(minter.getBoolean("naanAuthorized"));
    }

    @Test
    void keyStatusReportsAnUnauthorizedNaan() throws Exception {
        when(client.authority()).thenReturn(new DarkClient.AuthorityInfo(true, List.of("99999")));
        assertFalse(service.key().getJsonObject("minter").getBoolean("naanAuthorized"));
    }

    @Test
    void keyStatusStillShowsTheKeyWhenTheMinterCannotBeAsked() throws Exception {
        when(client.authority()).thenThrow(new DarkException("Minter unreachable"));

        JsonObject json = service.key();

        assertEquals(64, json.getString("keyId").length());
        assertEquals("Minter unreachable", json.getJsonObject("minter").getString("error"));
    }

    @Test
    void keyStatusWithoutAKeyOrADirectory() {
        System.setProperty(KEY_DIRECTORY_SETTING, root.resolve("nothing-here").toString());
        assertEquals(404, assertThrows(DarkException.class, () -> service.key()).getHttpStatus());

        System.clearProperty(KEY_DIRECTORY_SETTING);
        assertEquals(400, assertThrows(DarkException.class, () -> service.key()).getHttpStatus());
        assertEquals(400, assertThrows(DarkException.class, () -> service.generateKey(false)).getHttpStatus());
    }

    @Test
    void darkIsInactiveWithoutAKey() {
        System.setProperty(KEY_DIRECTORY_SETTING, root.resolve("nothing-here").toString());
        assertFalse(service.isEnabled());
        assertEquals(503, assertThrows(DarkException.class, () -> service.assign(DATASET)).getHttpStatus());
    }

    @Test
    @JvmSetting(key = JvmSettings.DARK_ENABLED, value = "false")
    void operationsAreRefusedWhileDisabled() {
        assertFalse(service.isEnabled());
        assertEquals(503, assertThrows(DarkException.class, () -> service.assign(DATASET)).getHttpStatus());
        assertEquals(503, assertThrows(DarkException.class, () -> service.refreshDataset(DATASET)).getHttpStatus());
        assertEquals(503, assertThrows(DarkException.class, () -> service.tombstoneDataset(DATASET)).getHttpStatus());
        assertEquals(503, assertThrows(DarkException.class, () -> service.startRefresh()).getHttpStatus());
        assertEquals(503, assertThrows(DarkException.class, () -> service.startAssignAll(0)).getHttpStatus());
        verify(rows, never()).recordOtherIdFailure(anyLong(), anyString(), anyBoolean());
        verifyNoInteractions(client);
    }
}
