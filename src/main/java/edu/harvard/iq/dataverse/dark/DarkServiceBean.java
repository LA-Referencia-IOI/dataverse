package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldServiceBean;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.engine.command.impl.DeleteDatasetVersionCommand;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.EjbDataverseEngine;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.CuratePublishedDatasetVersionCommand;
import edu.harvard.iq.dataverse.engine.command.impl.UpdateDatasetVersionCommand;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.annotation.Resource;
import jakarta.ejb.Asynchronous;
import jakarta.ejb.EJB;
import jakarta.ejb.SessionContext;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Named;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * dARK integration service: local persistence of the {@code dark} table and the
 * lifecycle operations against the Minter.
 * <p>
 * Rules:
 * <ul>
 * <li>No HTTP inside a command transaction. Hooks schedule work after the commit
 * ({@link #afterCommit}) and the work runs {@code @Asynchronous}.</li>
 * <li>HTTP runs outside any transaction ({@code NOT_SUPPORTED}); reads and writes of
 * the table go through short {@code REQUIRES_NEW} methods called on {@link #self()}.</li>
 * <li>A Minter failure never propagates: it is stored in {@code last_error} and logged.</li>
 * <li>Every local dataset gets a dARK, whatever its PID type (DOI, Handle, PermaLink,
 * plugins). Only harvested datasets are excluded everywhere; a PID longer than the
 * Minter's client_item_id limit cannot be registered and is reported as "notEligible".</li>
 * </ul>
 */
@Named
@Stateless
public class DarkServiceBean {

    private static final Logger logger = Logger.getLogger(DarkServiceBean.class.getCanonicalName());

    /** Maximum page size of the dashboard API. */
    public static final int MAX_PAGE_SIZE = 100;
    /** Maximum rows of the CSV export. */
    public static final int MAX_EXPORT_ROWS = 100_000;

    /** SQL length of the PID string ({@code protocol:authority<separator>identifier}). */
    private static final String PID_LENGTH_SQL =
            "length(o.protocol || ':' || coalesce(o.authority, '') || coalesce(o.separator, '') || o.identifier)";

    /**
     * Native SQL condition ({@code dataset ds JOIN dvobject o}) for datasets that must have a
     * dARK: every local (non-harvested) dataset with a PID of any type (DOI, Handle,
     * PermaLink, plugins) whose string fits the Minter's {@code client_item_id} limit.
     */
    private static final String ELIGIBLE_LOCAL_DATASET =
            "ds.harvestingclient_id IS NULL AND o.protocol IS NOT NULL AND o.identifier IS NOT NULL"
            + " AND " + PID_LENGTH_SQL + " <= " + DarkIds.CLIENT_ITEM_ID_MAX_LENGTH;

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;

    @Resource
    private SessionContext sessionContext;

    @Resource
    private TransactionSynchronizationRegistry txRegistry;

    @EJB
    DatasetServiceBean datasetService;

    @EJB
    DatasetFieldServiceBean fieldService;

    @EJB
    DataverseServiceBean dataverseService;

    @EJB
    AuthenticationServiceBean authService;

    @EJB
    EjbDataverseEngine engine;

    // =====================================================================
    // Configuration
    // =====================================================================

    public DarkConfig getConfig() {
        return DarkConfig.fromSettings();
    }

    /** @return true when {@code dataverse.dark.enabled=true} and the Minter connection is configured. */
    public boolean isEnabled() {
        DarkConfig config = getConfig();
        if (config.enabled() && !config.isUsable()) {
            logger.warning("dataverse.dark.enabled=true but minter-url, authority-id or naan is missing; dARK is inactive");
        }
        return config.isUsable();
    }

    public DarkClient newClient() {
        return new DarkClient(getConfig());
    }

    private DarkServiceBean self() {
        return sessionContext.getBusinessObject(DarkServiceBean.class);
    }

    // =====================================================================
    // Hook entry points (called by DarkHooks from command onSuccess)
    // =====================================================================

    /** Dataset created: reserve an ARK after the commit. */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void onDatasetCreated(Long datasetId) {
        afterCommit("reserve dataset " + datasetId, me -> me.reserveAsync(datasetId));
    }

    /** Version published or curated: send metadata after the commit. */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void onDatasetPublished(Long datasetId, boolean curated) {
        afterCommit("publish dataset " + datasetId, me -> me.publishAsync(datasetId, curated));
    }

    /** Version deaccessioned: tombstone if no published version remains. */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void onDatasetDeaccessioned(Long datasetId) {
        afterCommit("deaccession dataset " + datasetId, me -> me.tombstoneDeaccessionedAsync(datasetId));
    }

    /** Dataset destroyed (includes deleting a never-published dataset). */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void onDatasetDestroyed(String clientItemId, boolean wasPublished) {
        afterCommit("destroy " + clientItemId, me -> me.tombstoneDestroyedAsync(clientItemId, wasPublished));
    }

    /**
     * Called inside {@code FinalizeDatasetPublicationCommand}, in its transaction: adds the
     * dARK Other ID to the version being published, reading the ARK from the local table.
     * Database only, no HTTP. Never throws (a runtime exception here would roll back the
     * publication).
     */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void addOtherIdForPublication(Dataset dataset) {
        try {
            if (dataset.getId() == null) {
                return;
            }
            List<Dark> rows = em.createNamedQuery("Dark.findByDatasetId", Dark.class)
                    .setParameter("datasetId", dataset.getId())
                    .setFlushMode(FlushModeType.COMMIT)
                    .getResultList();
            if (rows.isEmpty() || rows.get(0).getArk() == null || rows.get(0).isTombstoneRequested()
                    || rows.get(0).getStatus() == DarkState.TOMBSTONE) {
                return;
            }
            DatasetFieldType otherIdType = fieldService.findByNameOpt(DatasetFieldConstant.otherId);
            if (otherIdType == null) {
                logger.warning("dARK: field type otherId not found; Other ID not written for dataset " + dataset.getId());
                return;
            }
            if (DarkOtherId.put(dataset.getLatestVersion(), otherIdType, getConfig().otherIdAgency(), rows.get(0).getArk())) {
                logger.fine("dARK: Other ID " + rows.get(0).getArk() + " added to dataset " + dataset.getId());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: could not add Other ID to dataset " + dataset.getId(), e);
        }
    }

    /**
     * Runs {@code action} after the current transaction commits (never on rollback), or
     * right away when there is no transaction. Failures are logged, never propagated.
     */
    private void afterCommit(String what, Consumer<DarkServiceBean> asyncCall) {
        Runnable action;
        try {
            DarkServiceBean me = self();
            action = () -> asyncCall.accept(me);
            int status = txRegistry.getTransactionStatus();
            if (status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK) {
                txRegistry.registerInterposedSynchronization(new Synchronization() {
                    @Override
                    public void beforeCompletion() {
                    }

                    @Override
                    public void afterCompletion(int result) {
                        if (result == Status.STATUS_COMMITTED) {
                            runSafely(what, () -> asyncCall.accept(me));
                        }
                    }
                });
                return;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: could not register '" + what + "' after commit; the refresh process will catch up"
                    + " (a dataset that got no reservation at all appears in the 'missing' view: use assign or assign-all)", e);
            return;
        }
        runSafely(what, action);
    }

    private static void runSafely(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: could not start '" + what + "'", e);
        }
    }

    // =====================================================================
    // Asynchronous lifecycle operations
    // =====================================================================

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void reserveAsync(Long datasetId) {
        reserveDatasets(List.of(datasetId), null);
    }

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void publishAsync(Long datasetId, boolean curated) {
        try {
            Long darkId = self().findIdByDatasetId(datasetId);
            if (darkId == null) {
                logger.fine("dARK: dataset " + datasetId + " published without a local ARK; the assignment process will handle it");
                return;
            }
            // The published DatasetVersion revision is the durable pending marker.
            // It changes on updatecurrent even when the public version number does not.
            // If the reservation finished after beforePublication (or an updatecurrent removed
            // it), the published version lacks the Other ID: add it first. A curation never
            // curates again (its own hook would loop).
            ensureOtherIdAndSend(darkId, null, curated ? OtherIdMode.CURATE_HOOK : OtherIdMode.PUBLISH_HOOK);
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: publish handling failed for dataset " + datasetId, e);
        }
    }

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void tombstoneDeaccessionedAsync(Long datasetId) {
        if (!isEnabled()) {
            return;
        }
        try {
            Object[] target = self().tombstoneTargetForDataset(datasetId);
            if (target != null) {
                tombstone((Long) target[0], (String) target[1]);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: deaccession handling failed for dataset " + datasetId, e);
        }
    }

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void tombstoneDestroyedAsync(String clientItemId, boolean wasPublished) {
        try {
            if (!isEnabled()) {
                return;
            }
            Object[] target = self().tombstoneTargetForClientItemId(clientItemId, wasPublished);
            if (target != null) {
                tombstone((Long) target[0], (String) target[1]);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: destroy handling failed for " + clientItemId, e);
        }
    }

    /** Cron process (see {@link #refreshAll(DarkJob)}), in the background. */
    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void refreshAllAsync(DarkJob job) {
        refreshAll(job);
    }

    /** Initial load (see {@link #assignAll(DarkJob, int)}), in the background. */
    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void assignAllAsync(DarkJob job, int limit) {
        assignAll(job, limit);
    }

    // =====================================================================
    // Operations (no transaction; HTTP allowed)
    // =====================================================================

    /**
     * Reserves ARKs ({@code POST /arks/batch}) for local datasets that have no ARK yet,
     * and creates the rows.
     *
     * @return ids of the rows created (or found) for those datasets
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<Long> reserveDatasets(List<Long> datasetIds, DarkJob job) {
        List<Long> created = new ArrayList<>();
        if (!isEnabled() || datasetIds.isEmpty()) {
            return created;
        }
        return reserveRows(self().reserveCandidates(datasetIds), job);
    }

    /** Each candidate is [durable row id, client_item_id], persisted BEFORE HTTP. */
    private List<Long> reserveRows(List<String[]> candidates, DarkJob job) {
        List<Long> created = new ArrayList<>();
        if (!isEnabled() || candidates.isEmpty()) {
            return created;
        }
        // A short durable lease prevents two workers reserving the same intent concurrently.
        List<String[]> claimed = new ArrayList<>();
        for (String[] candidate : candidates) {
            String[] claim = self().claimReservation(Long.valueOf(candidate[0]));
            if (claim != null) {
                claimed.add(claim);
            }
        }
        if (claimed.isEmpty()) {
            return created;
        }
        try {
            return reserveClaimedRows(claimed, job);
        } finally {
            for (String[] claim : claimed) {
                self().releaseReservation(Long.valueOf(claim[0]), claim[2]);
            }
        }
    }

    private List<Long> reserveClaimedRows(List<String[]> candidates, DarkJob job) {
        List<Long> created = new ArrayList<>();
        List<String> clientItemIds = candidates.stream().map(c -> c[1]).toList();
        DarkClient.ReserveResult result;
        try {
            result = newClient().reserveBatch(clientItemIds);
        } catch (DarkException e) {
            logger.warning("dARK: reservation failed for " + clientItemIds.size() + " dataset(s): " + e.toLastError());
            for (String[] candidate : candidates) {
                self().recordError(Long.valueOf(candidate[0]), e.toLastError());
            }
            if (job != null) {
                job.add("reserveFailed", clientItemIds.size());
            }
            return created;
        }
        for (DarkClient.ItemError err : result.errors()) {
            logger.warning("dARK: reservation rejected for " + err.clientItemId() + ": " + err.error());
            for (String[] candidate : candidates) {
                if (candidate[1].equals(err.clientItemId())) {
                    self().recordError(Long.valueOf(candidate[0]), err.error());
                }
            }
            if (job != null) {
                job.inc("reserveFailed");
            }
        }
        for (DarkArkResponse r : result.results()) {
            String[] candidate = candidates.stream()
                    .filter(c -> c[1].equals(r.clientItemId()))
                    .findFirst()
                    .orElse(null);
            if (candidate == null || r.ark() == null) {
                // Never associate by position: partial successes can reorder results.
                logger.warning("dARK: reservation response has no matching client_item_id/ARK; intent retained for retry");
                continue;
            }
            try {
                Long id = self().createRow(Long.valueOf(candidate[0]), candidate[1], candidate[2], r);
                if (id != null) {
                    created.add(id);
                    if (job != null) {
                        job.inc("reserved");
                    }
                }
            } catch (Exception e) {
                self().recordError(Long.valueOf(candidate[0]), "Reservation association rejected: " + e.getMessage());
                logger.log(Level.WARNING, "dARK: could not store ARK " + r.ark() + " for row " + candidate[0], e);
            }
        }
        return created;
    }

    /**
     * Sends Level 1 + Level 2 metadata of the latest published version
     * ({@code PUT /arks/{ark}}) when the state allows it (table 6.4).
     *
     * @return true if the Minter accepted the metadata
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public boolean sendMetadata(Long darkId, boolean onlyIfPending) {
        if (!isEnabled()) {
            return false;
        }
        PutPlan plan = self().preparePut(darkId, onlyIfPending);
        if (plan == null) {
            return false;
        }
        try {
            DarkClient.PutResult res = newClient().put(plan.ark(), plan.target(), plan.level1(), plan.level2(),
                    DarkMetadataBuilder.LEVEL2_SCHEMA, DarkMetadataBuilder.LEVEL2_MEDIA_TYPE);
            self().recordPut(darkId, res, plan);
            return !res.alreadyInProgress();
        } catch (DarkException e) {
            logger.warning("dARK: PUT " + plan.ark() + " failed: " + e.toLastError());
            self().recordError(darkId, e.toLastError());
            return false;
        }
    }

    /** Tombstones an ARK ({@code DELETE}) */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public boolean tombstone(Long darkId, String ark) {
        if (!isEnabled() || !self().requestTombstone(darkId) || ark == null) {
            return false;
        }
        try {
            if (!newClient().tombstone(ark)) {
                // 409 while the ARK is locked (metadata being processed): not tombstoned yet.
                // tombstone_requested stays set, so the refresh process retries it.
                String msg = DarkPolicy.TOMBSTONE_PREFIX + "HTTP 409: ARK is locked by the Minter; tombstone will be retried";
                logger.warning("dARK: DELETE " + ark + " not applied yet: ARK is locked by the Minter");
                self().recordError(darkId, msg);
                return false;
            }
            self().recordTombstone(darkId);
            logger.info("dARK: " + ark + " tombstoned");
            return true;
        } catch (DarkException e) {
            logger.warning("dARK: DELETE " + ark + " failed: " + e.toLastError());
            self().recordError(darkId, DarkPolicy.TOMBSTONE_PREFIX + e.toLastError());
            return false;
        }
    }

    /**
     * Refresh (cron): pending reservation intents, status of D/U rows, pending versions,
     * R rows of published datasets and failed tombstones.
     * <p>
     * It never assigns dARKs to datasets that have no row at all (datasets that existed
     * before dARK was enabled): that initial load modifies published datasets
     * ({@code updatecurrent}) and is only done on purpose, with {@code assign-all}.
     * <p>
     * A row is sent (PUT or {@code updatecurrent}) at most once per run: an
     * {@code updatecurrent} already triggers a PUT through the curate hook, and a second
     * PUT in the same run would get a 409 and leave {@code sent_version} unset.
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void refreshAll(DarkJob job) {
        try {
            DarkConfig config = getConfig();
            if (!config.isUsable()) {
                job.failed("dARK is disabled or not configured");
                return;
            }
            DarkClient client = newClient();
            Set<Long> sentThisRun = new HashSet<>();

            // Recover reservation intents, including responses lost after a dataset was destroyed.
            for (Long id : reserveRows(self().pendingReservations(config.batchSize()), job)) {
                ensureOtherIdAndSend(id, job, OtherIdMode.AUTO);
                sentThisRun.add(id);
            }

            // 1. Status of D/U rows (status/batch, falls back to GET).
            List<Object[]> pending = self().pendingRows(config.maxAttempts());
            job.add("checked", pending.size());
            if (!pending.isEmpty()) {
                List<String> arks = pending.stream().map(p -> (String) p[1]).toList();
                try {
                    Map<String, DarkClient.StatusResult> statuses = client.statusBatch(arks);
                    for (Object[] p : pending) {
                        DarkClient.StatusResult s = statuses.get((String) p[1]);
                        DarkState newState = self().recordStatus((Long) p[0], s, config.maxAttempts());
                        if (newState == DarkState.PUBLISHED) {
                            job.inc("published");
                        } else if (s == null || s.isError()) {
                            job.inc("statusErrors");
                        } else {
                            job.inc("stillPending");
                        }
                    }
                } catch (DarkException e) {
                    job.add("statusErrors", pending.size());
                    logger.warning("dARK: status check failed: " + e.toLastError());
                    for (Object[] row : pending) {
                        self().recordError((Long) row[0], e.toLastError());
                    }
                }
            }

            // 2. P rows whose latest published version was not sent yet.
            for (Long id : self().idsNeedingResend()) {
                if (!sentThisRun.add(id)) {
                    continue;
                }
                if (sendMetadata(id, true)) {
                    job.inc("resent");
                } else {
                    job.inc("resendFailed");
                }
            }

            // 3. R rows of published datasets (failed PUT, or reserved after publication).
            for (Long id : self().idsReservedAndPublished()) {
                if (!sentThisRun.add(id)) {
                    continue;
                }
                if (ensureOtherIdAndSend(id, job, OtherIdMode.AUTO)) {
                    job.inc("sent");
                }
            }

            // 4. Published datasets whose latest published version lacks the dARK Other ID
            //    (reservation finished after the publication, or an updatecurrent removed it).
            for (Long id : self().idsMissingOtherId(config.batchSize())) {
                if (!sentThisRun.add(id)) {
                    continue;
                }
                job.inc("otherIdMissing");
                ensureOtherIdAndSend(id, job, OtherIdMode.AUTO);
            }

            // 5. Tombstones that failed or were never requested.
            for (Object[] t : self().tombstoneCandidates(config.tombstoneUnpublishedDrafts())) {
                if (tombstone((Long) t[0], (String) t[1])) {
                    job.inc("tombstoned");
                } else {
                    job.inc("tombstoneFailed");
                }
            }

            job.done("refresh finished");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "dARK: refresh failed", e);
            job.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Refresh of one dataset ({@code POST /api/admin/dark/refresh/{id}}). */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public JsonObject refreshDataset(Long datasetId) throws DarkException {
        requireEnabled();
        Long darkId = self().findIdByDatasetId(datasetId);
        if (darkId == null) {
            throw new DarkException("Dataset " + datasetId + " has no dARK", 404);
        }
        Dark row = self().find(darkId);
        DarkConfig config = getConfig();
        if (row.getArk() == null) {
            reserveRows(List.<String[]>of(new String[] { darkId.toString(), row.getClientItemId() }), null);
            row = self().find(darkId);
        }
        if (row.isTombstoneRequested()) {
            tombstone(darkId, row.getArk());
            return self().rowJson(darkId, config.maxAttempts());
        }
        if (row.getStatus().isInProgress()) {
            DarkClient.StatusResult s = newClient().statusBatch(List.of(row.getArk())).get(row.getArk());
            // A manual refresh ignores the attempts limit.
            self().recordStatus(darkId, s, Integer.MAX_VALUE);
        }
        Dark updated = self().find(darkId);
        if (updated.getStatus() == DarkState.PUBLISHED) {
            sendMetadata(darkId, true);
        } else if (updated.getStatus() == DarkState.RESERVED) {
            ensureOtherIdAndSend(darkId, null, OtherIdMode.AUTO);
        }
        return self().rowJson(darkId, config.maxAttempts());
    }

    /**
     * Assigns or retries one dataset ({@code POST /api/admin/dark/assign/{id}}):
     * reserve if needed; if the dataset is published, add the Other ID to the published
     * version ({@code updatecurrent}) and send metadata.
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public JsonObject assign(Long datasetId) throws DarkException {
        String problem = self().assignabilityProblem(datasetId);
        if (problem != null) {
            throw new DarkException(problem, 400);
        }
        Long darkId = self().findIdByDatasetId(datasetId);
        if (darkId == null) {
            List<Long> ids = reserveDatasets(List.of(datasetId), null);
            if (ids.isEmpty()) {
                throw new DarkException("The Minter did not reserve an ARK for dataset " + datasetId + " (see server log)", 502);
            }
            darkId = ids.get(0);
        }
        Dark row = self().find(darkId);
        if (row.getArk() == null) {
            reserveDatasets(List.of(datasetId), null);
            row = self().find(darkId);
        }
        if (row.isTombstoneRequested()) {
            tombstone(darkId, row.getArk());
            return self().rowJson(darkId, getConfig().maxAttempts());
        }
        if (row.getStatus() == DarkState.TOMBSTONE) {
            throw new DarkException("The dARK of dataset " + datasetId + " is tombstoned and cannot be reused", 409);
        }
        // A manual assign restarts the automatic Other ID retries.
        self().resetOtherIdAttempts(darkId);
        if (row.getStatus().isInProgress()) {
            refreshDataset(datasetId);
        }
        ensureOtherIdAndSend(darkId, null, OtherIdMode.MANUAL);
        return self().rowJson(darkId, getConfig().maxAttempts());
    }

    /** Initial load: reserve for local datasets without an ARK, then {@code updatecurrent} the published ones. */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void assignAll(DarkJob job, int limit) {
        try {
            DarkConfig config = getConfig();
            if (!config.isUsable()) {
                job.failed("dARK is disabled or not configured");
                return;
            }
            int remaining = limit <= 0 ? Integer.MAX_VALUE : limit;
            long lastSeen = 0;
            while (remaining > 0) {
                int size = Math.min(config.batchSize(), remaining);
                List<Long> batch = self().localDatasetsWithoutRow(size, lastSeen);
                if (batch.isEmpty()) {
                    break;
                }
                lastSeen = batch.get(batch.size() - 1);
                remaining -= batch.size();
                job.add("candidates", batch.size());
                List<Long> rows = reserveDatasets(batch, job);
                for (Long id : rows) {
                    ensureOtherIdAndSend(id, job, OtherIdMode.AUTO);
                }
            }
            job.done("assign-all finished");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "dARK: assign-all failed", e);
            job.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Manual tombstone ({@code POST /api/admin/dark/tombstone/{id}}). */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public JsonObject tombstoneDataset(Long datasetId) throws DarkException {
        requireEnabled();
        Long darkId = self().findIdByDatasetId(datasetId);
        if (darkId == null) {
            throw new DarkException("Dataset " + datasetId + " has no dARK", 404);
        }
        Dark row = self().find(darkId);
        if (row.getStatus() != DarkState.TOMBSTONE && !tombstone(darkId, row.getArk())) {
            throw new DarkException("Tombstone failed: " + self().find(darkId).getLastError(), 502);
        }
        return self().rowJson(darkId, getConfig().maxAttempts());
    }

    /** Who asks {@link #ensureOtherIdAndSend} to check the Other ID. */
    private enum OtherIdMode {
        /** Publication hook: may curate (limited attempts); sends even if the row has no ARK yet. */
        PUBLISH_HOOK,
        /** Curate hook: never curates again (it would loop); sends. */
        CURATE_HOOK,
        /** Refresh, assign-all: may curate while below {@link DarkPolicy#OTHER_ID_MAX_ATTEMPTS}. */
        AUTO,
        /** assign/{id}: always may curate. */
        MANUAL
    }

    /**
     * For a row whose dataset is published: if the latest published version lacks the dARK
     * Other ID, add it with {@code updatecurrent} (draft + curate; the curate hook then
     * sends the metadata); otherwise send the metadata now.
     * <p>
     * If the Other ID cannot be added (the dataset has a draft or a lock, the automatic
     * retries are exhausted, or updatecurrent failed), the metadata is still sent and
     * {@code last_error} keeps the OTHER_ID reason. The error is recorded after the PUT
     * because a successful PUT clears {@code last_error}.
     *
     * @return true if metadata was sent (or will be, through the curate hook)
     */
    private boolean ensureOtherIdAndSend(Long darkId, DarkJob job, OtherIdMode mode) {
        if (!isEnabled()) {
            return false;
        }
        boolean hook = mode == OtherIdMode.PUBLISH_HOOK || mode == OtherIdMode.CURATE_HOOK;
        OtherIdStatus st = self().otherIdStatus(darkId);
        if (st == null || !st.published()) {
            return hook && sendMetadata(darkId, true);
        }
        if (st.present()) {
            if (st.otherIdAttempts() > 0) {
                self().resetOtherIdAttempts(darkId);
            }
            return sendMetadata(darkId, true);
        }
        if (st.problem() != null) {
            // Option A: a user's draft (or a lock) is never touched; the Other ID is written
            // when that draft is published (beforePublication).
            boolean sent = sendMetadata(darkId, true);
            self().recordError(darkId, DarkPolicy.OTHER_ID_PREFIX + st.problem());
            inc(job, "otherIdPending");
            return sent;
        }
        if (mode == OtherIdMode.CURATE_HOOK) {
            logger.warning("dARK: dataset " + st.datasetId() + " was curated without its Other ID " + st.ark()
                    + "; the refresh process will add it back");
            return sendMetadata(darkId, true);
        }
        if (mode != OtherIdMode.MANUAL && !DarkPolicy.mayAutoCurate(st.otherIdAttempts())) {
            boolean sent = sendMetadata(darkId, true);
            self().recordError(darkId, DarkPolicy.OTHER_ID_PREFIX + "missing from the published version; automatic retries exhausted ("
                    + st.otherIdAttempts() + "/" + DarkPolicy.OTHER_ID_MAX_ATTEMPTS + "), use assign/{id}");
            inc(job, "otherIdExhausted");
            return sent;
        }
        try {
            self().curateWithOtherId(st.datasetId(), st.ark());
            self().resetOtherIdAttempts(darkId);
            inc(job, "curated");
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: updatecurrent failed for dataset " + st.datasetId(), e);
            boolean sent = sendMetadata(darkId, true);
            self().recordOtherIdFailure(darkId, e.getMessage());
            inc(job, "curateFailed");
            return sent;
        }
    }

    private static void inc(DarkJob job, String counter) {
        if (job != null) {
            job.inc(counter);
        }
    }

    /**
     * {@code updatecurrent}: adds the Other ID to the published version without a new
     * version, as the built-in admin user: draft with the Other ID
     * ({@code UpdateDatasetVersionCommand}) + {@code CuratePublishedDatasetVersionCommand},
     * each in its own transaction, exactly like the native API.
     * <p>
     * No transaction and no database lock are held here: both commands open
     * {@code REQUIRES_NEW} transactions (e.g. {@code addDatasetLock}) that write rows
     * referencing the dataset, so holding {@code SELECT ... FOR UPDATE} on it would make this
     * thread wait for itself forever. Concurrency is handled by Dataverse's own
     * EditInProgress lock and the checks below.
     * <p>
     * If curation fails, only the draft created here is deleted (checked by version id);
     * a user's draft is never touched.
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void curateWithOtherId(Long datasetId, String ark) throws CommandException {
        if (!isEnabled()) {
            return;
        }
        Dataset ds = datasetService.find(datasetId);
        if (ds == null || ds.isHarvested()) {
            return;
        }
        if (ds.getLatestVersion().isDraft() || !ds.getLocks().isEmpty()) {
            throw new IllegalStateException("Dataset has a draft or lock; assignment deferred");
        }
        Dark row = self().find(self().findIdByDatasetId(datasetId));
        if (row == null || row.isTombstoneRequested() || row.getStatus() == DarkState.TOMBSTONE
                || !Objects.equals(row.getArk(), ark) || ds.getReleasedVersion() == null) {
            return;
        }
        if (DarkOtherId.containsArk(ds.getReleasedVersion(), ark)) {
            return;
        }
        String stale = DarkOtherId.find(ds.getReleasedVersion(), getConfig().otherIdAgency());
        if (stale != null) {
            logger.info("dARK: dataset " + datasetId + " has Other ID " + stale + " under agency "
                    + getConfig().otherIdAgency() + "; it is replaced by " + ark);
        }
        AuthenticatedUser admin = authService.getAdminUser();
        if (admin == null) {
            throw new IllegalStateException("admin user not found");
        }
        DatasetFieldType otherIdType = fieldService.findByNameOpt(DatasetFieldConstant.otherId);
        if (otherIdType == null) {
            throw new IllegalStateException("field type otherId not found");
        }
        DataverseRequest req = new DataverseRequest(admin, (HttpServletRequest) null);

        DarkOtherId.put(ds.getOrCreateEditVersion(), otherIdType, getConfig().otherIdAgency(), ark);
        ds = engine.submit(new UpdateDatasetVersionCommand(ds, req));
        Long ourDraftId = ds.getLatestVersion().isDraft() ? ds.getLatestVersion().getId() : null;
        try {
            // Re-read the dataset, as the native updatecurrent API does in its own request:
            // the object returned above has the new draft's datasetVersionUsers list empty in
            // memory (the DatasetVersionUser row was inserted inside the command), so Curate's
            // cascade REMOVE of the draft would miss it and violate
            // fk_datasetversionuser_datasetversion_id.
            Dataset fresh = datasetService.find(datasetId);
            engine.submit(new CuratePublishedDatasetVersionCommand(fresh, req));
        } catch (CommandException | RuntimeException e) {
            deleteOwnDraft(datasetId, ourDraftId, req);
            throw e;
        }
    }

    /** Deletes the draft created by {@link #curateWithOtherId} only if it is still that same draft. */
    private void deleteOwnDraft(Long datasetId, Long draftId, DataverseRequest req) {
        if (draftId == null) {
            return;
        }
        try {
            Dataset current = datasetService.find(datasetId);
            DatasetVersion latest = current == null ? null : current.getLatestVersion();
            if (latest != null && latest.isDraft() && draftId.equals(latest.getId())) {
                engine.submit(new DeleteDatasetVersionCommand(req, current));
            }
        } catch (Exception cleanup) {
            logger.log(Level.WARNING, "dARK: could not delete the draft created for dataset " + datasetId, cleanup);
        }
    }

    private void requireEnabled() throws DarkException {
        if (!isEnabled()) {
            throw new DarkException("dARK is disabled or not configured (dataverse.dark.*)", 503);
        }
    }

    // =====================================================================
    // Short transactions on the table (call through self())
    // =====================================================================

    /** Data needed for a PUT, built in a read transaction. */
    public record PutPlan(String ark, String target, JsonObject level1, String level2, String version,
            Long datasetVersionId, Long revision) {
    }

    /** Other ID situation of the published version of a row's dataset. */
    public record OtherIdStatus(Long datasetId, String ark, boolean published, boolean present, String problem,
            int otherIdAttempts) {
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Dark find(Long id) {
        return id == null ? null : em.find(Dark.class, id);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Long findIdByDatasetId(Long datasetId) {
        Dark d = findByDatasetId(datasetId);
        return d == null ? null : d.getId();
    }

    /** Persists reservation intents before HTTP; the nullable FK may later be cleared by deletion. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<String[]> reserveCandidates(List<Long> ids) {
        List<String[]> out = new ArrayList<>();
        for (Long id : ids) {
            // Serialize first-time intents for the same dataset without holding a lock over HTTP.
            Dataset ds = em.find(Dataset.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (ds == null || ds.isHarvested()) {
                continue;
            }
            Dark row = findByDatasetId(id);
            if (row != null && row.getArk() != null) {
                continue;
            }
            String clientItemId = DarkIds.clientItemId(ds);
            if (clientItemId == null) {
                logger.warning("dARK: dataset " + id + " has no PID yet, or its PID exceeds "
                        + DarkIds.CLIENT_ITEM_ID_MAX_LENGTH + " characters (Minter limit); skipped");
                continue;
            }
            if (row == null) {
                row = new Dark();
                row.setDataset(ds);
                row.setClientItemId(clientItemId);
                row.setStatus(DarkState.RESERVED);
                row.setWasPublished(ds.isReleased());
                em.persist(row);
                em.flush();
            }
            out.add(new String[] { row.getId().toString(), row.getClientItemId() });
        }
        return out;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<String[]> pendingReservations(int limit) {
        List<Dark> rows = em.createQuery("SELECT d FROM Dark d WHERE d.ark IS NULL ORDER BY d.updatedAt, d.id", Dark.class)
                .setMaxResults(limit).getResultList();
        return rows.stream().map(d -> new String[] { d.getId().toString(), d.getClientItemId() }).toList();
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public String[] claimReservation(Long id) {
        if (!isEnabled()) {
            return null;
        }
        Dark row = em.find(Dark.class, id, LockModeType.PESSIMISTIC_WRITE);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (row == null || row.getArk() != null
                || (row.getReservationLeaseUntil() != null && row.getReservationLeaseUntil().after(now))) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        row.setReservationToken(token);
        row.setReservationLeaseUntil(new Timestamp(now.getTime()
                + (2L * getConfig().httpTimeoutSeconds() + 60L) * 1000L));
        return new String[] { id.toString(), row.getClientItemId(), token };
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void releaseReservation(Long id, String token) {
        Dark row = em.find(Dark.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row != null && Objects.equals(token, row.getReservationToken())) {
            row.setReservationToken(null);
            row.setReservationLeaseUntil(null);
        }
    }

    /** Records a response against its durable intent, even if the dataset was deleted during HTTP. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Long createRow(Long reservationId, String clientItemId, String token, DarkArkResponse r) {
        Dark row = em.find(Dark.class, reservationId, LockModeType.PESSIMISTIC_WRITE);
        if (row != null && !Objects.equals(token, row.getReservationToken())) {
            return null; // A newer worker owns recovery; its idempotent retry will recover this ARK.
        }
        if (row == null || !Objects.equals(row.getClientItemId(), clientItemId)
                || !Objects.equals(clientItemId, r.clientItemId()) || r.ark() == null) {
            throw new IllegalArgumentException("Reservation identity mismatch");
        }
        Dark byArk = findByArk(r.ark());
        if (byArk != null && !byArk.getId().equals(row.getId())) {
            throw new IllegalStateException("ARK already belongs to another local reservation");
        }
        if (row.getArk() != null) {
            if (!row.getArk().equals(r.ark())) {
                throw new IllegalStateException("Reservation already has a different ARK");
            }
            return row.getId();
        }
        row.setArk(r.ark());
        row.applyResponse(r);
        row.setLastError(null);
        return row.getId();
    }

    /**
     * Builds the PUT for a row, or returns null if the state does not allow it (D/U/T),
     * the dataset has no published version, or {@code onlyIfPending} and nothing is pending.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public PutPlan preparePut(Long darkId, boolean onlyIfPending) {
        Dark row = em.find(Dark.class, darkId);
        if (!isEnabled() || row == null || row.getDataset() == null || row.getDataset().isHarvested()
                || row.getArk() == null || row.isTombstoneRequested()) {
            return null;
        }
        DarkPolicy.PublishAction action = DarkPolicy.onPublish(row.getStatus());
        if (action == DarkPolicy.PublishAction.TOMBSTONED) {
            logger.warning("dARK: dataset " + row.getDataset().getId() + " published but its ARK " + row.getArk() + " is tombstoned");
            return null;
        }
        if (action != DarkPolicy.PublishAction.PUT) {
            logger.fine("dARK: " + row.getArk() + " in " + row.getStatus() + "; the new version stays pending (sent_version)");
            return null;
        }
        Dataset ds = row.getDataset();
        DatasetVersion released = ds.getReleasedVersion();
        if (released == null) {
            return null;
        }
        String version = released.getFriendlyVersionNumber();
        if (onlyIfPending && row.getStatus() == DarkState.PUBLISHED
                && Objects.equals(row.getSentVersion(), version)
                && Objects.equals(row.getSentDatasetVersionId(), released.getId())
                && Objects.equals(row.getSentRevision(), released.getVersion())) {
            return null;
        }
        DarkConfig config = getConfig();
        String landing = DarkMetadataBuilder.landingUrl(SystemConfig.getDataverseSiteUrlStatic(), ds);
        String target = DarkMetadataBuilder.target(config.target(), DarkIds.resolverUrl(ds), landing);
        try {
            JsonObject level1 = DarkMetadataBuilder.level1Json(
                    DarkMetadataBuilder.fromVersion(released, row.getArk(),
                            config.resolvePublisher(dataverseService.getRootDataverseName()), landing));
            String level2 = DarkMetadataBuilder.level2Xml(released);
            return new PutPlan(row.getArk(), target, level1, level2, version, released.getId(), released.getVersion());
        } catch (DarkException e) {
            row.setLastError(e.getMessage());
            return null;
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordPut(Long darkId, DarkClient.PutResult res, PutPlan plan) {
        Dark row = em.find(Dark.class, darkId, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || row.getStatus() == DarkState.TOMBSTONE || row.isTombstoneRequested()) {
            return;
        }
        if (res.alreadyInProgress()) {
            // Local state may be stale after a timed-out PUT. Reconcile through status polling.
            row.setStatus(row.getStatus() == DarkState.RESERVED ? DarkState.DRAFT : DarkState.UPDATE);
            row.setSentRevision(null);
            row.setAttempts(0);
            return;
        }
        row.applyResponse(res.ark());
        row.setSentVersion(plan.version());
        row.setSentDatasetVersionId(plan.datasetVersionId());
        row.setSentRevision(plan.revision());
        row.setWasPublished(true);
        row.setAttempts(0);
        row.setLastError(null);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordError(Long darkId, String error) {
        Dark row = em.find(Dark.class, darkId);
        if (row != null) {
            row.setLastError(error);
            row.setUpdatedAtNow();
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordTombstone(Long darkId) {
        Dark row = em.find(Dark.class, darkId);
        if (row != null) {
            row.setStatus(DarkState.TOMBSTONE);
            row.setLastError(null);
        }
    }

    /** Commit the deletion intent before attempting remote deletion. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public boolean requestTombstone(Long darkId) {
        if (!isEnabled()) {
            return false;
        }
        Dark row = em.find(Dark.class, darkId, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || row.getStatus() == DarkState.TOMBSTONE) {
            return false;
        }
        row.setTombstoneRequested(true);
        return true;
    }

    /** Applies a status check; returns the resulting state. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public DarkState recordStatus(Long darkId, DarkClient.StatusResult s, int maxAttempts) {
        Dark row = em.find(Dark.class, darkId);
        if (row == null) {
            return null;
        }
        if (row.getStatus() == DarkState.TOMBSTONE || row.isTombstoneRequested()) {
            return row.getStatus();
        }
        if (s == null || s.isError()) {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(s == null ? "no status returned" : (s.errorCode() + ": " + s.errorMessage()));
            return row.getStatus();
        }
        DarkPolicy.StatusUpdate u = DarkPolicy.afterStatusCheck(row.getStatus(), row.getAttempts(), s.ark().state(),
                row.getLastError(), maxAttempts);
        row.setStatus(u.state());
        row.setAttempts(u.attempts());
        row.setLastError(u.lastError());
        row.applyResponse(new DarkArkResponse(null, null, s.ark().target(), null, s.ark().metadataCid(),
                s.ark().level1Cid(), s.ark().level2Cid()));
        row.setUpdatedAtNow();
        return u.state();
    }

    /** @return [id, ark] of rows in D/U below the attempts limit. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Object[]> pendingRows(int maxAttempts) {
        List<Object[]> out = new ArrayList<>();
        for (Dark d : findPending(maxAttempts)) {
            out.add(new Object[] { d.getId(), d.getArk() });
        }
        return out;
    }

    /** Includes updatecurrent: a public version number alone does not identify metadata. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Long> idsNeedingResend() {
        return longs(em.createNativeQuery(
                "SELECT d.id FROM dark d JOIN LATERAL ("
                + " SELECT v.id, v.version, v.versionnumber, v.minorversionnumber FROM datasetversion v"
                + " WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'RELEASED'"
                + " ORDER BY v.versionnumber DESC, v.minorversionnumber DESC LIMIT 1) v ON true"
                + " WHERE d.status = 'P' AND NOT d.tombstone_requested AND d.ark IS NOT NULL"
                + " AND (d.sent_dataset_version_id IS DISTINCT FROM v.id"
                + " OR d.sent_revision IS DISTINCT FROM v.version"
                + " OR d.sent_version IS DISTINCT FROM (v.versionnumber || '.' || v.minorversionnumber))"
                + " ORDER BY d.updated_at").getResultList());
    }

    /** R rows whose dataset has a published version. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Long> idsReservedAndPublished() {
        return longs(em.createNativeQuery(
                "SELECT d.id FROM dark d"
                + " WHERE d.status = 'R' AND d.dataset_id IS NOT NULL AND d.ark IS NOT NULL AND NOT d.tombstone_requested"
                + " AND EXISTS (SELECT 1 FROM datasetversion v WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'RELEASED')"
                + " ORDER BY d.updated_at").getResultList());
    }

    /**
     * [id, ark] of rows that should be tombstoned: dataset destroyed (unless it was a
     * never-published draft and that tombstone is disabled) or every version deaccessioned.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Object[]> tombstoneCandidates(boolean tombstoneUnpublishedDrafts) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT d.id, d.ark FROM dark d WHERE d.status <> 'T' AND d.ark IS NOT NULL AND ("
                + " d.tombstone_requested OR (d.dataset_id IS NULL AND (d.was_published OR ?1))"
                + " OR (d.dataset_id IS NOT NULL"
                + "   AND EXISTS (SELECT 1 FROM datasetversion v WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'DEACCESSIONED')"
                + "   AND NOT EXISTS (SELECT 1 FROM datasetversion v WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'RELEASED')))"
                + " ORDER BY d.updated_at")
                .setParameter(1, tombstoneUnpublishedDrafts)
                .getResultList();
        List<Object[]> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new Object[] { ((Number) r[0]).longValue(), (String) r[1] });
        }
        return out;
    }

    /** [id, ark] if the dataset's row must be tombstoned after a deaccession (no published version left). */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Object[] tombstoneTargetForDataset(Long datasetId) {
        Dark row = findByDatasetId(datasetId);
        if (row == null || row.getStatus() == DarkState.TOMBSTONE) {
            return null;
        }
        Dataset ds = row.getDataset();
        if (ds != null && ds.getReleasedVersion() != null) {
            logger.fine("dARK: dataset " + datasetId + " still has a published version; no tombstone");
            return null;
        }
        row.setTombstoneRequested(true);
        row.setWasPublished(true);
        return new Object[] { row.getId(), row.getArk() };
    }

    /** [id, ark] of a destroyed dataset's row, found by its client_item_id (dataset_id is already NULL). */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Object[] tombstoneTargetForClientItemId(String clientItemId, boolean wasPublished) {
        Dark row = findByClientItemId(clientItemId);
        if (row == null || row.getStatus() == DarkState.TOMBSTONE || row.getDataset() != null) {
            return null;
        }
        row.setWasPublished(row.wasPublished() || wasPublished);
        if (!DarkPolicy.tombstoneOnDestroy(row.wasPublished(), getConfig().tombstoneUnpublishedDrafts())) {
            return null;
        }
        row.setTombstoneRequested(true);
        return new Object[] { row.getId(), row.getArk() };
    }

    /** Eligible local (non-harvested) datasets, of any PID type, without a row, by id after {@code afterId}. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Long> localDatasetsWithoutRow(int limit, long afterId) {
        return longs(em.createNativeQuery(
                "SELECT ds.id FROM dataset ds JOIN dvobject o ON o.id = ds.id"
                + " WHERE " + ELIGIBLE_LOCAL_DATASET
                + " AND ds.id > ?1"
                + " AND NOT EXISTS (SELECT 1 FROM dark d WHERE d.dataset_id = ds.id)"
                + " ORDER BY ds.id LIMIT ?2")
                .setParameter(1, afterId)
                .setParameter(2, limit)
                .getResultList());
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public OtherIdStatus otherIdStatus(Long darkId) {
        Dark row = em.find(Dark.class, darkId);
        if (row == null || row.getDataset() == null || row.getArk() == null
                || row.isTombstoneRequested() || row.getStatus() == DarkState.TOMBSTONE) {
            return null;
        }
        Dataset ds = row.getDataset();
        DatasetVersion released = ds.getReleasedVersion();
        if (released == null) {
            return new OtherIdStatus(ds.getId(), row.getArk(), false, false, null, row.getOtherIdAttempts());
        }
        // Any agency: an agency corrected by hand still counts as present.
        boolean present = DarkOtherId.containsArk(released, row.getArk());
        String problem = null;
        if (!present) {
            if (ds.getLatestVersion().isDraft()) {
                problem = "the dataset has a draft; the Other ID will be written when it is published";
            } else if (!ds.getLocks().isEmpty()) {
                problem = "the dataset is locked";
            }
        }
        return new OtherIdStatus(ds.getId(), row.getArk(), true, present, problem, row.getOtherIdAttempts());
    }

    /** Restarts the automatic Other ID retries (Other ID present, or a manual assign). */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void resetOtherIdAttempts(Long darkId) {
        Dark row = em.find(Dark.class, darkId);
        if (row != null && row.getOtherIdAttempts() != 0) {
            row.setOtherIdAttempts(0);
            row.setUpdatedAtNow();
        }
    }

    /** Counts a failed automatic updatecurrent; @return the attempts so far. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public int recordOtherIdFailure(Long darkId, String reason) {
        Dark row = em.find(Dark.class, darkId);
        if (row == null) {
            return 0;
        }
        int attempt = row.getOtherIdAttempts() + 1;
        row.setOtherIdAttempts(attempt);
        row.setLastError(DarkPolicy.otherIdFailure(attempt, reason));
        row.setUpdatedAtNow();
        return attempt;
    }

    /**
     * Rows (D/U/P, below the automatic retries limit) whose dataset's latest published
     * version has no Other ID value equal to the ARK, under any agency. Both
     * {@code ark:NAAN/x} and {@code ark:/NAAN/x} count.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Long> idsMissingOtherId(int limit) {
        return longs(em.createNativeQuery(
                "SELECT d.id FROM dark d JOIN LATERAL ("
                + " SELECT v.id FROM datasetversion v"
                + " WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'RELEASED'"
                + " ORDER BY v.versionnumber DESC, v.minorversionnumber DESC LIMIT 1) v ON true"
                + " WHERE d.status IN ('D', 'U', 'P') AND d.ark IS NOT NULL AND NOT d.tombstone_requested"
                + " AND d.other_id_attempts < ?1"
                + " AND NOT EXISTS ("
                + "   SELECT 1 FROM datasetfield f"
                + "   JOIN datasetfieldtype ft ON ft.id = f.datasetfieldtype_id AND ft.name = 'otherId'"
                + "   JOIN datasetfieldcompoundvalue cv ON cv.parentdatasetfield_id = f.id"
                + "   JOIN datasetfield cf ON cf.parentdatasetfieldcompoundvalue_id = cv.id"
                + "   JOIN datasetfieldtype cft ON cft.id = cf.datasetfieldtype_id AND cft.name = 'otherIdValue'"
                + "   JOIN datasetfieldvalue fv ON fv.datasetfield_id = cf.id"
                + "   WHERE f.datasetversion_id = v.id"
                + "     AND trim(fv.value) IN (d.ark, replace(d.ark, 'ark:', 'ark:/')))"
                + " ORDER BY d.updated_at LIMIT ?2")
                .setParameter(1, DarkPolicy.OTHER_ID_MAX_ATTEMPTS)
                .setParameter(2, limit)
                .getResultList());
    }

    /** @return null if the dataset can receive a dARK, otherwise the reason. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public String assignabilityProblem(Long datasetId) {
        if (!isEnabled()) {
            return "dARK is disabled or not configured (dataverse.dark.*)";
        }
        Dataset ds = em.find(Dataset.class, datasetId);
        if (ds == null) {
            return "Dataset " + datasetId + " not found";
        }
        if (ds.isHarvested()) {
            return "Dataset " + datasetId + " is harvested; dARK is only assigned to local datasets";
        }
        if (DarkIds.clientItemId(ds) == null) {
            return "Dataset " + datasetId + " has no PID yet, or its PID exceeds "
                    + DarkIds.CLIENT_ITEM_ID_MAX_LENGTH + " characters (Minter limit for client_item_id)";
        }
        return null;
    }

    // =====================================================================
    // Queries
    // =====================================================================

    public Dark findByDatasetId(Long datasetId) {
        if (datasetId == null) {
            return null;
        }
        List<Dark> r = em.createNamedQuery("Dark.findByDatasetId", Dark.class)
                .setParameter("datasetId", datasetId)
                .getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    public Dark findByArk(String ark) {
        String compact = DarkIds.normalizeArk(ark);
        if (compact == null) {
            return null;
        }
        List<Dark> r = em.createNamedQuery("Dark.findByArk", Dark.class)
                .setParameter("ark", compact)
                .getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    /** @return the most recent row for a {@code client_item_id}, or null. */
    public Dark findByClientItemId(String clientItemId) {
        if (clientItemId == null) {
            return null;
        }
        List<Dark> r = em.createNamedQuery("Dark.findByClientItemId", Dark.class)
                .setParameter("clientItemId", clientItemId)
                .setMaxResults(1)
                .getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    public List<Dark> findByStatus(DarkState status) {
        return em.createNamedQuery("Dark.findByStatus", Dark.class)
                .setParameter("status", status)
                .getResultList();
    }

    /** Rows in {@code D} or {@code U} that have not exceeded {@code maxAttempts} status checks. */
    public List<Dark> findPending(int maxAttempts) {
        return em.createQuery("SELECT d FROM Dark d WHERE d.status IN :states AND d.tombstoneRequested = false AND d.attempts < :max ORDER BY d.updatedAt ASC",
                Dark.class)
                .setParameter("states", List.of(DarkState.DRAFT, DarkState.UPDATE))
                .setParameter("max", maxAttempts)
                .getResultList();
    }

    /** @return number of rows per state; every state is present (0 if none). */
    public Map<DarkState, Long> countByStatus() {
        Map<DarkState, Long> counts = new EnumMap<>(DarkState.class);
        for (DarkState s : DarkState.values()) {
            counts.put(s, 0L);
        }
        List<Object[]> rows = em.createNamedQuery("Dark.countByStatus", Object[].class).getResultList();
        for (Object[] row : rows) {
            counts.put((DarkState) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Summary for {@code GET /api/admin/dark/count} and the dashboard. */
    public JsonObjectBuilder countsJson() {
        DarkConfig config = getConfig();
        JsonObjectBuilder byStatus = JsonUtil.createObjectBuilder();
        long total = 0;
        for (Map.Entry<DarkState, Long> e : countByStatus().entrySet()) {
            byStatus.add(String.valueOf(e.getKey().getCode()), e.getValue());
            total += e.getValue();
        }
        long stuck = ((Number) em.createQuery("SELECT COUNT(d) FROM Dark d WHERE d.status IN :states AND d.attempts >= :max")
                .setParameter("states", List.of(DarkState.DRAFT, DarkState.UPDATE))
                .setParameter("max", config.maxAttempts())
                .getSingleResult()).longValue();
        long errors = ((Number) em.createQuery("SELECT COUNT(d) FROM Dark d WHERE d.lastError IS NOT NULL")
                .getSingleResult()).longValue();
        return JsonUtil.createObjectBuilder()
                .add("enabled", config.isUsable())
                .add("total", total)
                .add("byStatus", byStatus)
                .add("stuck", stuck)
                .add("withErrors", errors)
                .add("missing", countMissing())
                .add("notEligible", countNotEligible());
    }

    /** Local datasets that cannot get a dARK: no PID yet, or a PID longer than the Minter limit. */
    public long countNotEligible() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM dataset ds JOIN dvobject o ON o.id = ds.id"
                + " WHERE ds.harvestingclient_id IS NULL"
                + " AND (o.protocol IS NULL OR o.identifier IS NULL OR " + PID_LENGTH_SQL + " > " + DarkIds.CLIENT_ITEM_ID_MAX_LENGTH + ")")
                .getSingleResult()).longValue();
    }

    public long countMissing() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM dataset ds JOIN dvobject o ON o.id = ds.id"
                + " WHERE " + ELIGIBLE_LOCAL_DATASET
                + " AND NOT EXISTS (SELECT 1 FROM dark d WHERE d.dataset_id = ds.id)")
                .getSingleResult()).longValue();
    }

    // ---- dashboard: "assigned" view ----

    /** Filters of the "assigned" view. */
    public record AssignedQuery(String ark, DarkState status, Long datasetId, boolean errorsOnly,
            String sort, boolean ascending) {
    }

    /** One row of the dashboard; no client_item_id or CIDs are exposed. */
    public record AssignedRow(Long id, String ark, Long datasetId, String persistentId, String title,
            DarkState status, String target, String sentVersion, int attempts, boolean stuck,
            String lastError, Timestamp createdAt, Timestamp updatedAt) {
    }

    /** Local dataset without a dARK. */
    public record MissingRow(Long datasetId, String persistentId, String title, boolean published) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    public Page<AssignedRow> listAssigned(AssignedQuery q, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNo = Math.max(0, page);
        long total = ((Number) assignedQuery(q, true).getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<Dark> rows = assignedQuery(q, false)
                .setFirstResult(pageNo * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
        int max = getConfig().maxAttempts();
        return new Page<>(rows.stream().map(d -> toRow(d, max)).toList(), total, pageNo, pageSize);
    }

    /** All rows of the "assigned" view for CSV, capped at {@link #MAX_EXPORT_ROWS}. */
    public List<AssignedRow> exportAssigned(AssignedQuery q) {
        @SuppressWarnings("unchecked")
        List<Dark> rows = assignedQuery(q, false).setMaxResults(MAX_EXPORT_ROWS).getResultList();
        int max = getConfig().maxAttempts();
        return rows.stream().map(d -> toRow(d, max)).toList();
    }

    private jakarta.persistence.Query assignedQuery(AssignedQuery q, boolean count) {
        StringBuilder jpql = new StringBuilder(count ? "SELECT COUNT(d) FROM Dark d" : "SELECT d FROM Dark d");
        jpql.append(" WHERE 1 = 1");
        if (q.ark() != null && !q.ark().isBlank()) {
            jpql.append(" AND LOWER(d.ark) LIKE :ark");
        }
        if (q.status() != null) {
            jpql.append(" AND d.status = :status");
        }
        if (q.datasetId() != null) {
            jpql.append(" AND d.dataset.id = :datasetId");
        }
        if (q.errorsOnly()) {
            jpql.append(" AND d.lastError IS NOT NULL");
        }
        if (!count) {
            String field = "createdAt".equalsIgnoreCase(q.sort()) ? "d.createdAt" : "d.updatedAt";
            jpql.append(" ORDER BY ").append(field).append(q.ascending() ? " ASC" : " DESC").append(", d.id");
        }
        jakarta.persistence.Query query = em.createQuery(jpql.toString());
        if (q.ark() != null && !q.ark().isBlank()) {
            query.setParameter("ark", "%" + q.ark().trim().toLowerCase(Locale.ROOT).replace("%", "") + "%");
        }
        if (q.status() != null) {
            query.setParameter("status", q.status());
        }
        if (q.datasetId() != null) {
            query.setParameter("datasetId", q.datasetId());
        }
        return query;
    }

    private static AssignedRow toRow(Dark d, int maxAttempts) {
        Dataset ds = d.getDataset();
        return new AssignedRow(d.getId(), d.getArk(),
                ds == null ? null : ds.getId(),
                ds == null || ds.getGlobalId() == null ? null : ds.getGlobalId().asString(),
                ds == null ? null : ds.getDisplayName(),
                d.getStatus(), d.getTarget(), d.getSentVersion(), d.getAttempts(),
                d.getStatus().isInProgress() && d.getAttempts() >= maxAttempts,
                d.getLastError(), d.getCreatedAt(), d.getUpdatedAt());
    }

    // ---- dashboard: "missing" view ----

    public Page<MissingRow> listMissing(Long datasetId, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNo = Math.max(0, page);
        String where = " WHERE " + ELIGIBLE_LOCAL_DATASET
                + " AND NOT EXISTS (SELECT 1 FROM dark d WHERE d.dataset_id = ds.id)"
                + (datasetId != null ? " AND ds.id = ?1" : "");
        jakarta.persistence.Query countQ = em.createNativeQuery("SELECT COUNT(*) FROM dataset ds JOIN dvobject o ON o.id = ds.id" + where);
        jakarta.persistence.Query idsQ = em.createNativeQuery("SELECT ds.id FROM dataset ds JOIN dvobject o ON o.id = ds.id" + where
                + " ORDER BY ds.id DESC");
        if (datasetId != null) {
            countQ.setParameter(1, datasetId);
            idsQ.setParameter(1, datasetId);
        }
        long total = ((Number) countQ.getSingleResult()).longValue();
        List<Long> ids = longs(idsQ.setFirstResult(pageNo * pageSize).setMaxResults(pageSize).getResultList());
        List<MissingRow> items = new ArrayList<>();
        for (Long id : ids) {
            Dataset ds = em.find(Dataset.class, id);
            if (ds != null) {
                items.add(new MissingRow(id, ds.getGlobalId() == null ? null : ds.getGlobalId().asString(),
                        ds.getDisplayName(), ds.getReleasedVersion() != null));
            }
        }
        return new Page<>(items, total, pageNo, pageSize);
    }

    // ---- JSON ----

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public JsonObject rowJson(Long darkId, int maxAttempts) {
        Dark d = em.find(Dark.class, darkId);
        return d == null ? JsonUtil.createObjectBuilder().build() : toJson(toRow(d, maxAttempts)).build();
    }

    public static JsonObjectBuilder toJson(AssignedRow r) {
        JsonObjectBuilder b = JsonUtil.createObjectBuilder()
                .add("id", r.id())
                .add("reservationPending", r.ark() == null)
                .add("status", String.valueOf(r.status().getCode()))
                .add("statusName", r.status().name())
                .add("attempts", r.attempts())
                .add("stuck", r.stuck());
        addNullable(b, "ark", r.ark());
        addNullable(b, "datasetId", r.datasetId());
        addNullable(b, "persistentId", r.persistentId());
        addNullable(b, "title", r.title());
        addNullable(b, "target", r.target());
        addNullable(b, "sentVersion", r.sentVersion());
        addNullable(b, "lastError", r.lastError());
        addNullable(b, "createdAt", r.createdAt() == null ? null : r.createdAt().toInstant().toString());
        addNullable(b, "updatedAt", r.updatedAt() == null ? null : r.updatedAt().toInstant().toString());
        return b;
    }

    public static JsonObjectBuilder toJson(MissingRow r) {
        JsonObjectBuilder b = JsonUtil.createObjectBuilder()
                .add("datasetId", r.datasetId())
                .add("published", r.published());
        addNullable(b, "persistentId", r.persistentId());
        addNullable(b, "title", r.title());
        return b;
    }

    private static void addNullable(JsonObjectBuilder b, String key, Object value) {
        if (value == null) {
            b.addNull(key);
        } else if (value instanceof Long l) {
            b.add(key, l);
        } else {
            b.add(key, value.toString());
        }
    }

    // ---- writes ----

    public Dark save(Dark dark) {
        if (dark.getId() == null) {
            em.persist(dark);
            return dark;
        }
        return em.merge(dark);
    }

    private static List<Long> longs(List<?> raw) {
        List<Long> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            out.add(((Number) o).longValue());
        }
        return out;
    }
}
