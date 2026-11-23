package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.util.SystemConfig;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every transaction on the {@code dark} table. Each method is one short transaction of its
 * own ({@code REQUIRES_NEW}), so {@link DarkServiceBean} can call the Minter between them
 * without holding a transaction or a lock over HTTP. Writers lock their row
 * ({@code SELECT ... FOR UPDATE}): a hook and a job may reach the same row at once.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class DarkRepository {

    private static final Logger logger = Logger.getLogger(DarkRepository.class.getCanonicalName());

    /** SQL length of the PID string ({@code protocol:authority<separator>identifier}). */
    static final String PID_LENGTH_SQL =
            "length(o.protocol || ':' || coalesce(o.authority, '') || coalesce(o.separator, '') || o.identifier)";

    /**
     * Native SQL condition ({@code dataset ds JOIN dvobject o}) for datasets that must have a
     * dARK: every local (non-harvested) dataset with a PID of any type whose string fits the
     * Minter's {@code client_item_id} limit.
     */
    static final String ELIGIBLE_LOCAL_DATASET =
            "ds.harvestingclient_id IS NULL AND o.protocol IS NOT NULL AND o.identifier IS NOT NULL"
            + " AND " + PID_LENGTH_SQL + " <= " + DarkIds.CLIENT_ITEM_ID_MAX_LENGTH;

    /** Latest released version of the dataset of row {@code d}, as lateral subquery {@code v}. */
    private static final String LATEST_RELEASED =
            " JOIN LATERAL (SELECT v.id, v.version, v.versionnumber, v.minorversionnumber FROM datasetversion v"
            + " WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'RELEASED'"
            + " ORDER BY v.versionnumber DESC, v.minorversionnumber DESC LIMIT 1) v ON true";

    /**
     * Rows a job may work on now ({@link DarkPolicy#SLOW_RETRY_INTERVAL}): below
     * {@code max-attempts} (?1), or not touched since the slow retry cutoff (?2).
     */
    private static final String RETRY_DUE = "(d.attempts < ?1 OR d.updated_at < ?2)";

    /** A reservation intent; {@code token} is set once the row is claimed. */
    public record Reservation(Long id, String clientItemId, String token) {
    }

    /** A row and its ARK. */
    public record ArkRow(Long id, String ark) {
    }

    /** Data needed for a PUT, read in one transaction. */
    public record PutPlan(String ark, String target, JsonObject level1, String level2, String version,
            Long datasetVersionId, Long revision) {
    }

    /**
     * Other ID situation of the released version of a row's dataset. {@code blocked}: it is
     * missing but the dataset has a draft or a lock, so it cannot be added now.
     */
    public record OtherIdStatus(Long datasetId, String ark, boolean published, boolean present, boolean blocked,
            int otherIdAttempts) {
    }

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;

    @EJB
    DataverseServiceBean dataverseService;

    // ---- lookups ----

    public Dark find(Long id) {
        return id == null ? null : em.find(Dark.class, id);
    }

    public Long findIdByDatasetId(Long datasetId) {
        Dark row = byDatasetId(datasetId);
        return row == null ? null : row.getId();
    }

    /**
     * ARK to write in the Other ID of the version being published, or null. Runs inside the
     * publication's transaction without flushing it, and never throws: an exception here
     * would roll the publication back.
     */
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public String arkForPublication(Long datasetId) {
        try {
            List<Dark> found = em.createNamedQuery("Dark.findByDatasetId", Dark.class)
                    .setParameter("datasetId", datasetId)
                    .setFlushMode(FlushModeType.COMMIT)
                    .getResultList();
            return found.isEmpty() || !found.get(0).isActive() ? null : found.get(0).getArk();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "dARK: could not read the ARK of dataset " + datasetId, e);
            return null;
        }
    }

    /** @return null if the dataset can receive a dARK, otherwise the reason. */
    public String assignabilityProblem(Long datasetId) {
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

    // ---- reservations ----

    /**
     * Persists the reservation intent of each dataset that has no ARK yet, before any HTTP.
     * The dataset is locked so that two first-time intents for it are serialized.
     */
    public List<Reservation> reserveCandidates(List<Long> datasetIds) {
        List<Reservation> out = new ArrayList<>();
        for (Long id : datasetIds) {
            Dataset ds = em.find(Dataset.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (ds == null || ds.isHarvested()) {
                continue;
            }
            Dark row = byDatasetId(id);
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
                em.persist(row);
                em.flush();
            }
            out.add(new Reservation(row.getId(), row.getClientItemId(), null));
        }
        return out;
    }

    /** Intents without an ARK, including those whose dataset was destroyed meanwhile. */
    public List<Reservation> pendingReservations(int maxAttempts, int limit) {
        List<Reservation> out = new ArrayList<>();
        for (Object[] r : rows(due("SELECT d.id, d.client_item_id FROM dark d WHERE d.ark IS NULL AND " + RETRY_DUE
                + " ORDER BY d.updated_at, d.id LIMIT ?3", maxAttempts).setParameter(3, limit))) {
            out.add(new Reservation(((Number) r[0]).longValue(), (String) r[1], null));
        }
        return out;
    }

    /**
     * Claims an intent for {@code leaseSeconds}, so two workers do not reserve it at once.
     *
     * @return the claim, or null if the row has an ARK or another worker holds it
     */
    public Reservation claimReservation(Long id, int leaseSeconds) {
        Dark row = locked(id);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (row == null || row.getArk() != null
                || (row.getReservationLeaseUntil() != null && row.getReservationLeaseUntil().after(now))) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        row.setReservationToken(token);
        row.setReservationLeaseUntil(new Timestamp(now.getTime() + leaseSeconds * 1000L));
        return new Reservation(id, row.getClientItemId(), token);
    }

    public void releaseReservation(Reservation claim) {
        Dark row = locked(claim.id());
        if (row != null && Objects.equals(claim.token(), row.getReservationToken())) {
            row.setReservationToken(null);
            row.setReservationLeaseUntil(null);
        }
    }

    /**
     * Stores the ARK the Minter reserved for a claimed intent.
     *
     * @return the row id, or null if another worker took the claim over
     * @throws IllegalStateException if the response does not belong to this intent
     */
    public Long confirmReservation(Reservation claim, DarkArkResponse reserved) {
        Dark row = locked(claim.id());
        if (row != null && !Objects.equals(claim.token(), row.getReservationToken())) {
            return null; // Its retry is idempotent at the Minter and will store this same ARK.
        }
        if (row == null || !Objects.equals(row.getClientItemId(), reserved.clientItemId()) || reserved.ark() == null) {
            throw new IllegalStateException("Reservation identity mismatch");
        }
        List<Dark> sameArk = em.createNamedQuery("Dark.findByArk", Dark.class).setParameter("ark", reserved.ark()).getResultList();
        if (!sameArk.isEmpty() && !sameArk.get(0).getId().equals(row.getId())) {
            throw new IllegalStateException("ARK already belongs to another local reservation");
        }
        if (row.getArk() != null && !row.getArk().equals(reserved.ark())) {
            throw new IllegalStateException("Reservation already has a different ARK");
        }
        row.setArk(reserved.ark());
        row.applyResponse(reserved);
        row.setAttempts(0);
        row.setLastError(null);
        return row.getId();
    }

    /** Eligible local datasets, of any PID type, without a row, by id after {@code afterId}. */
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

    // ---- metadata ----

    /**
     * Builds the PUT of a row with the metadata of the latest released version.
     *
     * @return null if there is nothing to send: the state does not allow a PUT (D/U/T), the
     *         dataset has no released version, or that version was already sent
     * @throws DarkException if the metadata cannot be built
     */
    public PutPlan preparePut(Long darkId, DarkConfig config) throws DarkException {
        Dark row = em.find(Dark.class, darkId);
        if (row == null || !row.isActive() || row.getDataset() == null || row.getDataset().isHarvested()) {
            return null;
        }
        if (DarkPolicy.onPublish(row.getStatus()) != DarkPolicy.PublishAction.PUT) {
            logger.fine("dARK: " + row.getArk() + " in " + row.getStatus() + "; the new version stays pending");
            return null;
        }
        Dataset ds = row.getDataset();
        DatasetVersion released = ds.getReleasedVersion();
        if (released == null) {
            return null;
        }
        String version = released.getFriendlyVersionNumber();
        if (row.getStatus() == DarkState.PUBLISHED
                && Objects.equals(row.getSentVersion(), version)
                && Objects.equals(row.getSentDatasetVersionId(), released.getId())
                && Objects.equals(row.getSentRevision(), released.getVersion())) {
            return null;
        }
        String landing = DarkMetadataBuilder.landingUrl(SystemConfig.getDataverseSiteUrlStatic(), ds);
        JsonObject level1 = DarkMetadataBuilder.level1Json(DarkMetadataBuilder.fromVersion(released, row.getArk(),
                config.resolvePublisher(dataverseService.getRootDataverseName()), landing));
        return new PutPlan(row.getArk(), DarkMetadataBuilder.target(config.target(), DarkIds.resolverUrl(ds), landing),
                level1, DarkMetadataBuilder.level2Xml(released), version, released.getId(), released.getVersion());
    }

    public void recordPut(Long darkId, DarkClient.PutResult result, PutPlan plan) {
        Dark row = locked(darkId);
        if (row == null || !row.isActive()) {
            return;
        }
        row.setAttempts(0);
        if (result.alreadyInProgress()) {
            // The local state was stale (e.g. after a timed-out PUT). Status checks reconcile it,
            // and the cleared revision makes the refresh send this version again afterwards.
            row.setStatus(row.getStatus() == DarkState.RESERVED ? DarkState.DRAFT : DarkState.UPDATE);
            row.setSentRevision(null);
            return;
        }
        row.applyResponse(result.ark());
        row.setSentVersion(plan.version());
        row.setSentDatasetVersionId(plan.datasetVersionId());
        row.setSentRevision(plan.revision());
        row.setLastError(null);
    }

    public OtherIdStatus otherIdStatus(Long darkId) {
        Dark row = em.find(Dark.class, darkId);
        if (row == null || !row.isActive() || row.getDataset() == null) {
            return null;
        }
        Dataset ds = row.getDataset();
        DatasetVersion released = ds.getReleasedVersion();
        if (released == null) {
            return new OtherIdStatus(ds.getId(), row.getArk(), false, false, false, row.getOtherIdAttempts());
        }
        // Any agency: an agency corrected by hand still counts as present.
        boolean present = DarkOtherId.containsArk(released, row.getArk());
        boolean blocked = !present && (ds.getLatestVersion().isDraft() || !ds.getLocks().isEmpty());
        return new OtherIdStatus(ds.getId(), row.getArk(), true, present, blocked, row.getOtherIdAttempts());
    }

    /** Restarts the automatic Other ID retries (Other ID present, or a manual assign). */
    public void resetOtherIdAttempts(Long darkId) {
        Dark row = locked(darkId);
        if (row != null && row.getOtherIdAttempts() != 0) {
            row.setOtherIdAttempts(0);
        }
    }

    /**
     * Counts a failed {@code updatecurrent}.
     *
     * @param keepLastError true to leave {@code last_error} as it is (a more important error is there)
     */
    public void recordOtherIdFailure(Long darkId, String reason, boolean keepLastError) {
        Dark row = locked(darkId);
        if (row == null) {
            return;
        }
        row.setOtherIdAttempts(row.getOtherIdAttempts() + 1);
        if (!keepLastError) {
            row.setLastError(DarkPolicy.otherIdFailure(row.getOtherIdAttempts(), reason));
        }
        row.setUpdatedAtNow();
    }

    // ---- status ----

    /** Rows in D or U whose status is due for a check. */
    public List<ArkRow> pendingStatus(int maxAttempts) {
        return arkRows(due("SELECT d.id, d.ark FROM dark d WHERE d.status IN ('D', 'U') AND NOT d.tombstone_requested AND "
                + RETRY_DUE + " ORDER BY d.updated_at", maxAttempts));
    }

    /** Applies a status check; returns the resulting state. */
    public DarkState recordStatus(Long darkId, DarkClient.StatusResult status, int maxAttempts) {
        Dark row = locked(darkId);
        if (row == null) {
            return null;
        }
        if (!row.isActive()) {
            return row.getStatus();
        }
        row.setUpdatedAtNow();
        if (status == null || status.isError()) {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(status == null ? "no status returned" : (status.errorCode() + ": " + status.errorMessage()));
            return row.getStatus();
        }
        DarkArkResponse ark = status.ark();
        DarkPolicy.StatusUpdate update = DarkPolicy.afterStatusCheck(row.getStatus(), row.getAttempts(), ark.state(),
                row.getLastError(), maxAttempts);
        row.applyResponse(ark);
        row.setStatus(update.state());
        row.setAttempts(update.attempts());
        row.setLastError(update.lastError());
        return update.state();
    }

    // ---- tombstones ----

    /** Commits the intent before the remote deletion; false if the row is already tombstoned. */
    public boolean requestTombstone(Long darkId) {
        Dark row = locked(darkId);
        if (row == null || row.getStatus() == DarkState.TOMBSTONE) {
            return false;
        }
        row.setTombstoneRequested(true);
        return true;
    }

    public void recordTombstone(Long darkId) {
        Dark row = locked(darkId);
        if (row != null) {
            row.setStatus(DarkState.TOMBSTONE);
            row.setLastError(null);
        }
    }

    /**
     * Requests the tombstone of a destroyed dataset's row, found by its {@code client_item_id}
     * ({@code dataset_id} is already NULL).
     *
     * @return the row to tombstone, or null if there is none
     */
    public ArkRow requestTombstoneOfDestroyed(String clientItemId) {
        List<Dark> found = em.createNamedQuery("Dark.findByClientItemId", Dark.class)
                .setParameter("clientItemId", clientItemId)
                .setMaxResults(1)
                .getResultList();
        if (found.isEmpty() || found.get(0).getStatus() == DarkState.TOMBSTONE || found.get(0).getDataset() != null) {
            return null;
        }
        found.get(0).setTombstoneRequested(true);
        return new ArkRow(found.get(0).getId(), found.get(0).getArk());
    }

    /**
     * Rows to tombstone: tombstone already requested, or dataset destroyed (published or
     * not). A deaccessioned dataset keeps its ARK: it still exists and can be published
     * again, while a tombstone is irreversible.
     */
    public List<ArkRow> tombstoneCandidates(int maxAttempts) {
        return arkRows(due("SELECT d.id, d.ark FROM dark d WHERE d.status <> 'T' AND d.ark IS NOT NULL"
                + " AND (d.tombstone_requested OR d.dataset_id IS NULL) AND " + RETRY_DUE
                + " ORDER BY d.updated_at", maxAttempts));
    }

    // ---- errors ----

    /** Stores an error that is expected to go away by itself (Minter unreachable or busy). */
    public void recordError(Long darkId, String error) {
        recordError(darkId, error, false);
    }

    /** Stores an error the Minter will repeat for the same request; counts towards {@code max-attempts}. */
    public void recordRejection(Long darkId, String error) {
        recordError(darkId, error, true);
    }

    private void recordError(Long darkId, String error, boolean countAttempt) {
        Dark row = locked(darkId);
        if (row != null) {
            row.setLastError(error);
            if (countAttempt) {
                row.setAttempts(row.getAttempts() + 1);
            }
            row.setUpdatedAtNow();
        }
    }

    // ---- rows a refresh has to send ----

    /**
     * P rows whose latest released version was not sent. The version's revision is compared
     * too: {@code updatecurrent} changes the metadata without changing the version number.
     */
    public List<Long> idsNeedingResend(int maxAttempts) {
        return longs(due("SELECT d.id FROM dark d" + LATEST_RELEASED
                + " WHERE d.status = 'P' AND NOT d.tombstone_requested AND d.ark IS NOT NULL"
                + " AND (d.sent_dataset_version_id IS DISTINCT FROM v.id"
                + " OR d.sent_revision IS DISTINCT FROM v.version"
                + " OR d.sent_version IS DISTINCT FROM (v.versionnumber || '.' || v.minorversionnumber))"
                + " AND " + RETRY_DUE + " ORDER BY d.updated_at", maxAttempts).getResultList());
    }

    /** R rows whose dataset has a released version. */
    public List<Long> idsReservedAndPublished(int maxAttempts) {
        return longs(due("SELECT d.id FROM dark d"
                + " WHERE d.status = 'R' AND d.dataset_id IS NOT NULL AND d.ark IS NOT NULL AND NOT d.tombstone_requested"
                + " AND EXISTS (SELECT 1 FROM datasetversion v WHERE v.dataset_id = d.dataset_id AND v.versionstate = 'RELEASED')"
                + " AND " + RETRY_DUE + " ORDER BY d.updated_at", maxAttempts).getResultList());
    }

    /**
     * Rows (D/U/P, below the automatic retries limit) whose dataset's latest released version
     * has no Other ID value equal to the ARK, under any agency. Both {@code ark:NAAN/x} and
     * {@code ark:/NAAN/x} count.
     */
    public List<Long> idsMissingOtherId(int limit) {
        return longs(em.createNativeQuery("SELECT d.id FROM dark d" + LATEST_RELEASED
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

    // ---- helpers ----

    private Dark locked(Long id) {
        return em.find(Dark.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    private Dark byDatasetId(Long datasetId) {
        List<Dark> found = em.createNamedQuery("Dark.findByDatasetId", Dark.class)
                .setParameter("datasetId", datasetId)
                .getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    /** Native query using {@link #RETRY_DUE}, with its two parameters set. */
    private Query due(String sql, int maxAttempts) {
        return em.createNativeQuery(sql)
                .setParameter(1, maxAttempts)
                .setParameter(2, Timestamp.from(Instant.now().minus(DarkPolicy.SLOW_RETRY_INTERVAL)));
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    private static List<ArkRow> arkRows(Query query) {
        List<ArkRow> out = new ArrayList<>();
        for (Object[] r : rows(query)) {
            out.add(new ArkRow(((Number) r[0]).longValue(), (String) r[1]));
        }
        return out;
    }

    static List<Long> longs(List<?> raw) {
        List<Long> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            out.add(((Number) o).longValue());
        }
        return out;
    }
}
