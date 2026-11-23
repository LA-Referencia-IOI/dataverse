package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldServiceBean;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.EjbDataverseEngine;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.dark.DarkRepository.ArkRow;
import edu.harvard.iq.dataverse.dark.DarkRepository.OtherIdStatus;
import edu.harvard.iq.dataverse.dark.DarkRepository.PutPlan;
import edu.harvard.iq.dataverse.dark.DarkRepository.Reservation;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.CuratePublishedDatasetVersionCommand;
import edu.harvard.iq.dataverse.engine.command.impl.DeleteDatasetVersionCommand;
import edu.harvard.iq.dataverse.engine.command.impl.UpdateDatasetVersionCommand;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lifecycle of the dARK identifiers: reservation, metadata, Other ID, tombstones, and the
 * refresh and assign-all jobs.
 * <p>
 * Rules:
 * <ul>
 * <li>No HTTP inside a transaction. This bean runs without one ({@code NOT_SUPPORTED});
 * the table is read and written through the short transactions of {@link DarkRepository}.</li>
 * <li>Hooks never do the work themselves: they schedule it after the commit of the
 * command ({@link #afterCommit}), and it runs {@code @Asynchronous}.</li>
 * <li>A Minter failure never propagates to a Dataverse command: it is stored in
 * {@code last_error} and the refresh job retries it.</li>
 * <li>Whether dARK is enabled is checked where work enters: {@link DarkHooks} for the
 * hooks, and the public operations here for the admin API, the dashboard and the jobs.</li>
 * </ul>
 */
@Named
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class DarkServiceBean {

    private static final Logger logger = Logger.getLogger(DarkServiceBean.class.getCanonicalName());

    private static final String REFRESH_JOB = "refresh";
    private static final String ASSIGN_ALL_JOB = "assign-all";

    /** Outcome of {@link #sendMetadata} and {@link #syncPublished}. */
    enum SendResult {
        /** The Minter accepted the metadata. */
        SENT,
        /** The Other ID was added with {@code updatecurrent}; its hook sends the metadata. */
        CURATED,
        /** 409: the Minter is still processing the previous metadata. */
        IN_PROGRESS,
        /** No released version, state that does not allow a PUT, or version already sent. */
        NOTHING_TO_SEND,
        /** The metadata could not be built or the Minter did not accept it; see {@code last_error}. */
        FAILED
    }

    /** Whether {@link #syncPublished} may add a missing Other ID with {@code updatecurrent}. */
    enum Curate {
        /** After an {@code updatecurrent}: curating again from its own hook would loop. */
        NEVER,
        /** Hooks and jobs: while below {@link DarkPolicy#OTHER_ID_MAX_ATTEMPTS}. */
        IF_ATTEMPTS_LEFT,
        /** A manual assign. */
        ALWAYS
    }

    @EJB
    DarkRepository rows;

    @EJB
    DarkReportBean report;

    @EJB
    DatasetServiceBean datasetService;

    @EJB
    DatasetFieldServiceBean fieldService;

    @EJB
    AuthenticationServiceBean authService;

    @EJB
    EjbDataverseEngine engine;

    @Resource
    private SessionContext sessionContext;

    @Resource
    private TransactionSynchronizationRegistry txRegistry;

    /** @return true when {@code dataverse.dark.enabled=true} and the Minter connection is configured. */
    public boolean isEnabled() {
        DarkConfig config = DarkConfig.fromSettings();
        String reason = config.inactiveReason();
        if (reason != null) {
            DarkConfig.warnOnce(reason);
        }
        return config.isUsable();
    }

    private void requireEnabled() throws DarkException {
        if (!isEnabled()) {
            throw new DarkException("dARK is disabled or not configured (dataverse.dark.*)", 503);
        }
    }

    DarkClient client() {
        return new DarkClient(DarkConfig.fromSettings());
    }

    // =====================================================================
    // Hooks (called by DarkHooks, possibly inside the command's transaction)
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

    /** Dataset destroyed (includes deleting a never-published dataset): tombstone after the commit. */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void onDatasetDestroyed(String clientItemId) {
        afterCommit("destroy " + clientItemId, me -> me.tombstoneDestroyedAsync(clientItemId));
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
            String ark = rows.arkForPublication(dataset.getId());
            if (ark == null) {
                return;
            }
            DatasetFieldType otherIdType = fieldService.findByNameOpt(DatasetFieldConstant.otherId);
            if (otherIdType == null) {
                logger.warning("dARK: field type otherId not found; Other ID not written for dataset " + dataset.getId());
            } else if (DarkOtherId.put(dataset.getLatestVersion(), otherIdType, DarkConfig.fromSettings().otherIdAgency(), ark)) {
                logger.fine("dARK: Other ID " + ark + " added to dataset " + dataset.getId());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK: could not add Other ID to dataset " + dataset.getId(), e);
        }
    }

    /**
     * Starts an asynchronous method of this bean after the current transaction commits (never
     * on rollback), or right away when there is no transaction. Failures are logged, never
     * propagated. If the server stops before the method runs, the dataset is left without a
     * row and is picked up by assign-all.
     */
    private void afterCommit(String what, Consumer<DarkServiceBean> asyncCall) {
        try {
            DarkServiceBean me = self();
            int status = txRegistry.getTransactionStatus();
            if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
                asyncCall.accept(me);
                return;
            }
            txRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int result) {
                    if (result == Status.STATUS_COMMITTED) {
                        try {
                            asyncCall.accept(me);
                        } catch (RuntimeException e) {
                            logger.log(Level.WARNING, "dARK: could not start '" + what + "'", e);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "dARK: could not start '" + what + "'", e);
        }
    }

    /** This bean through the container: needed to call its {@code @Asynchronous} methods. */
    private DarkServiceBean self() {
        return sessionContext.getBusinessObject(DarkServiceBean.class);
    }

    // =====================================================================
    // Asynchronous work
    // =====================================================================

    @Asynchronous
    public void reserveAsync(Long datasetId) {
        try {
            reserveDatasets(List.of(datasetId), null);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "dARK: reservation failed for dataset " + datasetId, e);
        }
    }

    @Asynchronous
    public void publishAsync(Long datasetId, boolean curated) {
        try {
            Long darkId = rows.findIdByDatasetId(datasetId);
            if (darkId == null) {
                logger.fine("dARK: dataset " + datasetId + " published without a row; assign or assign-all will handle it");
                return;
            }
            syncPublished(darkId, null, curated ? Curate.NEVER : Curate.IF_ATTEMPTS_LEFT);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "dARK: publish handling failed for dataset " + datasetId, e);
        }
    }

    @Asynchronous
    public void tombstoneDestroyedAsync(String clientItemId) {
        try {
            ArkRow row = rows.requestTombstoneOfDestroyed(clientItemId);
            if (row != null) {
                tombstone(row.id(), row.ark());
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "dARK: destroy handling failed for " + clientItemId, e);
        }
    }

    @Asynchronous
    public void refreshAllAsync(DarkJob job) {
        run(job, () -> refreshAll(job));
    }

    @Asynchronous
    public void assignAllAsync(DarkJob job, int limit) {
        run(job, () -> assignAll(job, limit));
    }

    /** Runs a job to its end; a failure outside its {@link #guarded} steps marks it as failed. */
    private void run(DarkJob job, Runnable work) {
        try {
            work.run();
            job.done(job.getName() + " finished");
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "dARK: job '" + job.getName() + "' failed", e);
            job.failed(describe(e));
        }
    }

    // =====================================================================
    // Operations against the Minter
    // =====================================================================

    /**
     * Reserves ARKs ({@code POST /arks/batch}) for local datasets that have no ARK yet.
     *
     * @return ids of the rows that got an ARK
     */
    List<Long> reserveDatasets(List<Long> datasetIds, DarkJob job) {
        return datasetIds.isEmpty() ? List.of() : reserveRows(rows.reserveCandidates(datasetIds), job);
    }

    /** Claims each intent for a short lease, so two workers never reserve the same one at once. */
    private List<Long> reserveRows(List<Reservation> intents, DarkJob job) {
        int leaseSeconds = 2 * DarkConfig.fromSettings().httpTimeoutSeconds() + 60;
        List<Reservation> claims = intents.stream()
                .map(intent -> rows.claimReservation(intent.id(), leaseSeconds))
                .filter(Objects::nonNull)
                .toList();
        if (claims.isEmpty()) {
            return List.of();
        }
        try {
            return reserveClaimed(claims, job);
        } finally {
            claims.forEach(rows::releaseReservation);
        }
    }

    private List<Long> reserveClaimed(List<Reservation> claims, DarkJob job) {
        DarkClient.ReserveResult result;
        try {
            result = client().reserveBatch(claims.stream().map(Reservation::clientItemId).toList());
        } catch (DarkException e) {
            logger.warning("dARK: reservation failed for " + claims.size() + " dataset(s): " + e.toLastError());
            claims.forEach(claim -> recordFailure(claim.id(), e, ""));
            add(job, "reserveFailed", claims.size());
            return List.of();
        }
        // Responses are matched by client_item_id, never by position.
        for (DarkClient.ItemError error : result.errors()) {
            logger.warning("dARK: reservation rejected for " + error.clientItemId() + ": " + error.error());
            claimOf(claims, error.clientItemId()).ifPresent(claim -> rows.recordRejection(claim.id(), error.error()));
            add(job, "reserveFailed", 1);
        }
        List<Long> reserved = new ArrayList<>();
        for (DarkArkResponse ark : result.results()) {
            Reservation claim = claimOf(claims, ark.clientItemId()).orElse(null);
            if (claim == null || ark.ark() == null) {
                logger.warning("dARK: reservation response has no matching client_item_id/ARK; intent retained for retry");
                continue;
            }
            try {
                Long id = rows.confirmReservation(claim, ark);
                if (id != null) {
                    reserved.add(id);
                    add(job, "reserved", 1);
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "dARK: could not store ARK " + ark.ark() + " for row " + claim.id(), e);
                rows.recordError(claim.id(), "Reservation not stored: " + describe(e));
            }
        }
        return reserved;
    }

    private static Optional<Reservation> claimOf(List<Reservation> claims, String clientItemId) {
        return claims.stream().filter(claim -> claim.clientItemId().equals(clientItemId)).findFirst();
    }

    /**
     * Sends Level 1 + Level 2 metadata of the latest released version ({@code PUT /arks/{ark}})
     * when the row's state allows it ({@link DarkPolicy#onPublish}).
     */
    SendResult sendMetadata(Long darkId) {
        try {
            PutPlan plan = rows.preparePut(darkId, DarkConfig.fromSettings());
            if (plan == null) {
                return SendResult.NOTHING_TO_SEND;
            }
            DarkClient.PutResult result = client().put(plan.ark(), plan.target(), plan.level1(), plan.level2(),
                    DarkMetadataBuilder.LEVEL2_SCHEMA, DarkMetadataBuilder.LEVEL2_MEDIA_TYPE);
            rows.recordPut(darkId, result, plan);
            return result.alreadyInProgress() ? SendResult.IN_PROGRESS : SendResult.SENT;
        } catch (DarkException e) {
            logger.warning("dARK: metadata of row " + darkId + " not sent: " + e.toLastError());
            recordFailure(darkId, e, "");
            return SendResult.FAILED;
        }
    }

    /**
     * Tombstones an ARK ({@code DELETE /arks/{ark}}). The intent is committed first, so the
     * refresh job retries it until the Minter confirms.
     *
     * @return true if the ARK is tombstoned
     */
    boolean tombstone(Long darkId, String ark) {
        if (!rows.requestTombstone(darkId) || ark == null) {
            return false;
        }
        try {
            if (client().tombstone(ark)) {
                rows.recordTombstone(darkId);
                logger.info("dARK: " + ark + " tombstoned");
                return true;
            }
            logger.warning("dARK: DELETE " + ark + " not applied yet: ARK is locked by the Minter");
            rows.recordError(darkId, DarkPolicy.TOMBSTONE_PREFIX + "HTTP 409: ARK is locked by the Minter; tombstone will be retried");
        } catch (DarkException e) {
            logger.warning("dARK: DELETE " + ark + " failed: " + e.toLastError());
            recordFailure(darkId, e, DarkPolicy.TOMBSTONE_PREFIX);
        }
        return false;
    }

    /** A rejection (HTTP 4xx) counts towards {@code max-attempts}; an unreachable or failing Minter does not. */
    private void recordFailure(Long darkId, DarkException e, String prefix) {
        if (e.isRejection()) {
            rows.recordRejection(darkId, prefix + e.toLastError());
        } else {
            rows.recordError(darkId, prefix + e.toLastError());
        }
    }

    /**
     * Brings the Minter up to date with the released version of a row's dataset: adds the
     * dARK Other ID to that version if it is missing and {@code curate} allows it
     * ({@code updatecurrent}, whose hook then sends the metadata); otherwise sends the
     * metadata now.
     * <p>
     * A draft or a lock is never touched: the Other ID is then written when that draft is
     * published ({@link #addOtherIdForPublication}). An Other ID problem is stored in
     * {@code last_error} after the PUT, because an accepted PUT clears it, and never over
     * the error of a failed PUT.
     */
    private SendResult syncPublished(Long darkId, DarkJob job, Curate curate) {
        OtherIdStatus otherId = rows.otherIdStatus(darkId);
        if (otherId == null || !otherId.published()) {
            return SendResult.NOTHING_TO_SEND;
        }
        String exhausted = null;
        String curationError = null;
        if (otherId.present()) {
            if (otherId.otherIdAttempts() > 0) {
                rows.resetOtherIdAttempts(darkId);
            }
        } else if (otherId.blocked()) {
            add(job, "otherIdPending", 1);
        } else if (curate == Curate.NEVER) {
            logger.warning("dARK: dataset " + otherId.datasetId() + " was curated without its Other ID " + otherId.ark()
                    + "; the refresh job will add it back");
        } else if (curate == Curate.IF_ATTEMPTS_LEFT && !DarkPolicy.mayAutoCurate(otherId.otherIdAttempts())) {
            exhausted = DarkPolicy.otherIdExhausted(otherId.otherIdAttempts());
            add(job, "otherIdExhausted", 1);
        } else {
            try {
                if (curateWithOtherId(otherId.datasetId(), otherId.ark())) {
                    rows.resetOtherIdAttempts(darkId);
                    add(job, "curated", 1);
                    return SendResult.CURATED;
                }
            } catch (CommandException | RuntimeException e) {
                logger.log(Level.WARNING, "dARK: updatecurrent failed for dataset " + otherId.datasetId(), e);
                curationError = describe(e);
                add(job, "curateFailed", 1);
            }
        }
        SendResult result = sendMetadata(darkId);
        boolean keepSendError = result == SendResult.FAILED;
        if (curationError != null) {
            rows.recordOtherIdFailure(darkId, curationError, keepSendError);
        } else if (exhausted != null && !keepSendError) {
            rows.recordError(darkId, exhausted);
        }
        return result;
    }

    /**
     * {@code updatecurrent}: adds the Other ID to the released version without creating a new
     * version, as the first superuser ({@code getAdminUser}): draft with the Other ID
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
     *
     * @return true if the Other ID was added; false if there was nothing to do or the dataset
     *         got a draft or a lock meanwhile
     */
    boolean curateWithOtherId(Long datasetId, String ark) throws CommandException {
        Dataset ds = datasetService.find(datasetId);
        Dark row = rows.find(rows.findIdByDatasetId(datasetId));
        if (ds == null || ds.isHarvested() || ds.getReleasedVersion() == null
                || ds.getLatestVersion().isDraft() || !ds.getLocks().isEmpty()
                || row == null || !row.isActive() || !row.getArk().equals(ark)
                || DarkOtherId.containsArk(ds.getReleasedVersion(), ark)) {
            return false;
        }
        String agency = DarkConfig.fromSettings().otherIdAgency();
        String stale = DarkOtherId.find(ds.getReleasedVersion(), agency);
        if (stale != null) {
            logger.info("dARK: dataset " + datasetId + " has Other ID " + stale + " under agency " + agency
                    + "; it is replaced by " + ark);
        }
        AuthenticatedUser admin = authService.getAdminUser();
        if (admin == null) {
            throw new IllegalStateException("no superuser found");
        }
        DatasetFieldType otherIdType = fieldService.findByNameOpt(DatasetFieldConstant.otherId);
        if (otherIdType == null) {
            throw new IllegalStateException("field type otherId not found");
        }
        DataverseRequest request = new DataverseRequest(admin, (HttpServletRequest) null);

        DarkOtherId.put(ds.getOrCreateEditVersion(), otherIdType, agency, ark);
        ds = engine.submit(new UpdateDatasetVersionCommand(ds, request));
        Long ownDraftId = ds.getLatestVersion().isDraft() ? ds.getLatestVersion().getId() : null;
        try {
            // Re-read the dataset, as the native updatecurrent API does in its own request:
            // the object returned above has the new draft's datasetVersionUsers list empty in
            // memory (the DatasetVersionUser row was inserted inside the command), so Curate's
            // cascade REMOVE of the draft would miss it and violate
            // fk_datasetversionuser_datasetversion_id.
            engine.submit(new CuratePublishedDatasetVersionCommand(datasetService.find(datasetId), request));
            return true;
        } catch (CommandException | RuntimeException e) {
            deleteOwnDraft(datasetId, ownDraftId, request);
            throw e;
        }
    }

    /** Deletes the draft created by {@link #curateWithOtherId} only if it is still that same draft. */
    private void deleteOwnDraft(Long datasetId, Long draftId, DataverseRequest request) {
        if (draftId == null) {
            return;
        }
        try {
            Dataset current = datasetService.find(datasetId);
            DatasetVersion latest = current == null ? null : current.getLatestVersion();
            if (latest != null && latest.isDraft() && draftId.equals(latest.getId())) {
                engine.submit(new DeleteDatasetVersionCommand(request, current));
            }
        } catch (Exception cleanup) {
            logger.log(Level.WARNING, "dARK: could not delete the draft created for dataset " + datasetId, cleanup);
        }
    }

    // =====================================================================
    // Operations on one dataset (admin API and dashboard)
    // =====================================================================

    /** Refresh of one dataset ({@code POST /api/admin/dark/refresh/{id}}). */
    public JsonObject refreshDataset(Long datasetId) throws DarkException {
        requireEnabled();
        return sync(existingRow(datasetId), Curate.IF_ATTEMPTS_LEFT);
    }

    /**
     * Assigns or retries one dataset ({@code POST /api/admin/dark/assign/{id}}): reserves if
     * needed; if the dataset is released, adds the Other ID to the released version
     * ({@code updatecurrent}) and sends the metadata.
     */
    public JsonObject assign(Long datasetId) throws DarkException {
        requireEnabled();
        String problem = rows.assignabilityProblem(datasetId);
        if (problem != null) {
            throw new DarkException(problem, 400);
        }
        Long darkId = rows.findIdByDatasetId(datasetId);
        if (darkId == null) {
            List<Long> reserved = reserveDatasets(List.of(datasetId), null);
            if (reserved.isEmpty()) {
                throw new DarkException("The Minter did not reserve an ARK for dataset " + datasetId + " (see server log)", 502);
            }
            darkId = reserved.get(0);
        }
        if (rows.find(darkId).getStatus() == DarkState.TOMBSTONE) {
            throw new DarkException("The dARK of dataset " + datasetId + " is tombstoned and cannot be reused", 409);
        }
        // A manual assign restarts the automatic Other ID retries.
        rows.resetOtherIdAttempts(darkId);
        return sync(darkId, Curate.ALWAYS);
    }

    /** Manual tombstone ({@code POST /api/admin/dark/tombstone/{id}}). */
    public JsonObject tombstoneDataset(Long datasetId) throws DarkException {
        requireEnabled();
        Long darkId = existingRow(datasetId);
        Dark row = rows.find(darkId);
        if (row.getStatus() != DarkState.TOMBSTONE && !tombstone(darkId, row.getArk())) {
            throw new DarkException("Tombstone failed: " + rows.find(darkId).getLastError(), 502);
        }
        return report.rowJson(darkId);
    }

    private Long existingRow(Long datasetId) throws DarkException {
        Long darkId = rows.findIdByDatasetId(datasetId);
        if (darkId == null) {
            throw new DarkException("Dataset " + datasetId + " has no dARK", 404);
        }
        return darkId;
    }

    /**
     * Does now, for one row, what the refresh job would do: pending reservation, pending
     * tombstone, status check (ignoring {@code max-attempts}) and metadata.
     */
    private JsonObject sync(Long darkId, Curate curate) throws DarkException {
        Dark row = rows.find(darkId);
        if (row.getArk() == null) {
            reserveRows(List.of(new Reservation(darkId, row.getClientItemId(), null)), null);
            row = rows.find(darkId);
        }
        if (row.isTombstoneRequested()) {
            tombstone(darkId, row.getArk());
        } else if (row.isActive()) {
            if (row.getStatus().isInProgress()) {
                rows.recordStatus(darkId, client().statusBatch(List.of(row.getArk())).get(row.getArk()), Integer.MAX_VALUE);
            }
            syncPublished(darkId, null, curate);
        }
        return report.rowJson(darkId);
    }

    // =====================================================================
    // Signing key (admin API)
    // =====================================================================

    /**
     * Generates the key pair that signs the requests to the Minter
     * ({@code POST /api/admin/dark/key}). Its public key must be registered by the Minter
     * administrator before dARK is enabled.
     *
     * @param replace true to discard the current key; only while dARK is disabled, because
     *                the new key signs nothing the Minter accepts until it is registered
     */
    public JsonObject generateKey(boolean replace) throws DarkException {
        DarkConfig config = DarkConfig.fromSettings();
        Path directory = requireKeyDirectory(config);
        if (replace && config.enabled()) {
            throw new DarkException("Set dataverse.dark.enabled=false before replacing the key, and enable it again"
                    + " once the Minter administrator has registered the new public key", 409);
        }
        return keyJson(config, DarkKeyStore.generate(directory, replace)).build();
    }

    /**
     * The public half of the signing key and what the Minter says about this installation's
     * authority ({@code GET /api/admin/dark/key}). The Minter does not disclose the key it
     * has registered, so this cannot tell whether the key is accepted: the first signed
     * request does.
     */
    public JsonObject key() throws DarkException {
        DarkConfig config = DarkConfig.fromSettings();
        Path directory = requireKeyDirectory(config);
        if (!DarkKeyStore.exists(directory)) {
            throw new DarkException("There is no key in " + directory + "; generate it with POST /api/admin/dark/key", 404);
        }
        JsonObjectBuilder minter = JsonUtil.createObjectBuilder();
        if (config.connectionProblem() != null) {
            minter.add("error", config.connectionProblem());
        } else {
            try {
                DarkClient.AuthorityInfo authority = client().authority();
                minter.add("authorityActive", authority.active())
                        .add("naanAuthorized", authority.naans().contains(config.naan()));
            } catch (DarkException e) {
                minter.add("error", e.toLastError());
            }
        }
        return keyJson(config, DarkKeyStore.load(directory)).add("minter", minter).build();
    }

    private static Path requireKeyDirectory(DarkConfig config) throws DarkException {
        if (config.keyPath() == null) {
            throw new DarkException("dataverse.dark.key-directory is not set", 400);
        }
        return config.keyPath();
    }

    private static JsonObjectBuilder keyJson(DarkConfig config, DarkKeyStore.Credential key) {
        JsonObjectBuilder json = JsonUtil.createObjectBuilder()
                .add("publicKey", key.publicKey())
                .add("keyId", key.keyId())
                .add("enabled", config.enabled());
        if (config.authorityId() != null) {
            json.add("authorityId", config.authorityId());
        }
        if (config.naan() != null) {
            json.add("naan", config.naan());
        }
        if (config.minterUrl() != null) {
            json.add("minterUrl", config.minterUrl());
        }
        return json;
    }

    // =====================================================================
    // Jobs
    // =====================================================================

    /** @return the refresh job just started in the background, or null if another job is running. */
    public DarkJob startRefresh() throws DarkException {
        requireEnabled();
        DarkJob job = DarkJob.tryStart(REFRESH_JOB);
        if (job != null) {
            self().refreshAllAsync(job);
        }
        return job;
    }

    /** @return the assign-all job just started in the background, or null if another job is running. */
    public DarkJob startAssignAll(int limit) throws DarkException {
        requireEnabled();
        DarkJob job = DarkJob.tryStart(ASSIGN_ALL_JOB);
        if (job != null) {
            self().assignAllAsync(job, limit);
        }
        return job;
    }

    /**
     * Refresh (cron). It only works on rows that exist: datasets without a row are left to
     * assign-all, because giving a dARK to a released dataset modifies it
     * ({@code updatecurrent}).
     * <p>
     * A row is sent (PUT or {@code updatecurrent}) at most once per run: an
     * {@code updatecurrent} already triggers a PUT through its hook, and a second PUT in the
     * same run would get a 409. A failure never stops the run: each step and each row is
     * {@link #guarded}.
     */
    void refreshAll(DarkJob job) {
        DarkConfig config = DarkConfig.fromSettings();
        int maxAttempts = config.maxAttempts();
        Set<Long> sentThisRun = new HashSet<>();

        // 1. Reservation intents without an ARK, including those of datasets destroyed meanwhile.
        guarded(job, null, () -> {
            for (Long id : reserveRows(rows.pendingReservations(maxAttempts, config.batchSize()), job)) {
                sentThisRun.add(id);
                guarded(job, id, () -> syncPublished(id, job, Curate.IF_ATTEMPTS_LEFT));
            }
        });

        // 2. Status of D/U rows.
        guarded(job, null, () -> checkStatuses(job, maxAttempts));

        // 3. P rows whose latest released version was not sent yet.
        guarded(job, null, () -> {
            for (Long id : rows.idsNeedingResend(maxAttempts)) {
                if (sentThisRun.add(id)) {
                    guarded(job, id, () -> {
                        SendResult result = sendMetadata(id);
                        if (result == SendResult.SENT) {
                            job.inc("resent");
                        } else if (result == SendResult.FAILED) {
                            job.inc("resendFailed");
                        }
                    });
                }
            }
        });

        // 4. R rows of released datasets (failed PUT, or reserved after the publication).
        guarded(job, null, () -> {
            for (Long id : rows.idsReservedAndPublished(maxAttempts)) {
                if (sentThisRun.add(id)) {
                    guarded(job, id, () -> {
                        SendResult result = syncPublished(id, job, Curate.IF_ATTEMPTS_LEFT);
                        if (result == SendResult.SENT || result == SendResult.CURATED) {
                            job.inc("sent");
                        }
                    });
                }
            }
        });

        // 5. Released versions without the dARK Other ID (reservation finished after the
        //    publication, or an updatecurrent removed it).
        guarded(job, null, () -> {
            for (Long id : rows.idsMissingOtherId(config.batchSize())) {
                if (sentThisRun.add(id)) {
                    job.inc("otherIdMissing");
                    guarded(job, id, () -> syncPublished(id, job, Curate.IF_ATTEMPTS_LEFT));
                }
            }
        });

        // 6. Tombstones of destroyed datasets that failed or were never requested.
        guarded(job, null, () -> {
            for (ArkRow row : rows.tombstoneCandidates(maxAttempts)) {
                guarded(job, row.id(), () -> job.inc(tombstone(row.id(), row.ark()) ? "tombstoned" : "tombstoneFailed"));
            }
        });
    }

    /** {@code POST /arks/status/batch} for every D/U row that is due, and its result per row. */
    private void checkStatuses(DarkJob job, int maxAttempts) {
        List<ArkRow> pending = rows.pendingStatus(maxAttempts);
        job.add("checked", pending.size());
        if (pending.isEmpty()) {
            return;
        }
        Map<String, DarkClient.StatusResult> statuses;
        try {
            statuses = client().statusBatch(pending.stream().map(ArkRow::ark).toList());
        } catch (DarkException | RuntimeException e) {
            String error = e instanceof DarkException minter ? minter.toLastError() : describe(e);
            logger.log(Level.WARNING, "dARK: status check failed: " + error, e instanceof DarkException ? null : e);
            job.add("statusErrors", pending.size());
            pending.forEach(row -> guarded(job, row.id(), () -> rows.recordError(row.id(), error)));
            return;
        }
        for (ArkRow row : pending) {
            guarded(job, row.id(), () -> {
                DarkClient.StatusResult status = statuses.get(row.ark());
                if (rows.recordStatus(row.id(), status, maxAttempts) == DarkState.PUBLISHED) {
                    job.inc("published");
                } else {
                    job.inc(status == null || status.isError() ? "statusErrors" : "stillPending");
                }
            });
        }
    }

    /**
     * Assign-all: reserves for local datasets without a row, in batches, and brings the
     * released ones up to date (Other ID with {@code updatecurrent}, then metadata).
     *
     * @param limit maximum number of datasets; 0 for all
     */
    void assignAll(DarkJob job, int limit) {
        int batchSize = DarkConfig.fromSettings().batchSize();
        int remaining = limit <= 0 ? Integer.MAX_VALUE : limit;
        long lastSeen = 0;
        while (remaining > 0) {
            List<Long> batch = rows.localDatasetsWithoutRow(Math.min(batchSize, remaining), lastSeen);
            if (batch.isEmpty()) {
                break;
            }
            lastSeen = batch.get(batch.size() - 1);
            remaining -= batch.size();
            job.add("candidates", batch.size());
            guarded(job, null, () -> {
                for (Long id : reserveDatasets(batch, job)) {
                    guarded(job, id, () -> syncPublished(id, job, Curate.IF_ATTEMPTS_LEFT));
                }
            });
        }
    }

    /**
     * Runs one step of a job, or the work of one row ({@code darkId} not null). An unexpected
     * failure is logged and counted in {@code unexpectedErrors} and, for a row, stored in its
     * {@code last_error}; it never stops the job. The next refresh processes the row again.
     */
    private void guarded(DarkJob job, Long darkId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "dARK: unexpected failure in job '" + job.getName() + "'"
                    + (darkId == null ? "" : " on row " + darkId), e);
            job.inc("unexpectedErrors");
            if (darkId != null) {
                try {
                    rows.recordError(darkId, DarkPolicy.UNEXPECTED_PREFIX + describe(e));
                } catch (RuntimeException alreadyLogged) {
                    // The failure is in the server log and in the job counters.
                }
            }
        }
    }

    /** Root cause as {@code ExceptionClass: message} (EJB calls wrap the original exception). */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    /** Hooks and single-dataset operations run without a job. */
    private static void add(DarkJob job, String counter, int delta) {
        if (job != null) {
            job.add(counter, delta);
        }
    }
}
