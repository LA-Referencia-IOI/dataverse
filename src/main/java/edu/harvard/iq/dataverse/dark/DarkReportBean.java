package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.ejb.Stateless;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read side of the dARK registry for the admin API and the dashboard: counts, the
 * "assigned" and "missing" lists, and their JSON. Neither {@code client_item_id} nor CIDs
 * are exposed.
 */
@Stateless
public class DarkReportBean {

    /** Maximum page size of the lists. */
    public static final int MAX_PAGE_SIZE = 100;
    /** Maximum rows of the CSV export. */
    public static final int MAX_EXPORT_ROWS = 100_000;

    private static final String DATASETS = " FROM dataset ds JOIN dvobject o ON o.id = ds.id";
    private static final String WITHOUT_ROW = " WHERE " + DarkRepository.ELIGIBLE_LOCAL_DATASET
            + " AND NOT EXISTS (SELECT 1 FROM dark d WHERE d.dataset_id = ds.id)";

    /** Filters of the "assigned" view. */
    public record AssignedQuery(String ark, DarkState status, Long datasetId, boolean errorsOnly,
            String sort, boolean ascending) {
    }

    /** One row of the registry. {@code stuck}: retried only once a day (see {@link DarkPolicy#isStuck}). */
    public record AssignedRow(Long id, String ark, Long datasetId, String persistentId, String title,
            DarkState status, String target, String sentVersion, int attempts, boolean stuck,
            String lastError, Timestamp createdAt, Timestamp updatedAt) {
    }

    /** Local dataset without a dARK. */
    public record MissingRow(Long datasetId, String persistentId, String title, boolean published) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;

    // ---- counts ----

    /** Summary for {@code GET /api/admin/dark/count} and the dashboard. */
    public JsonObject counts() {
        DarkConfig config = DarkConfig.fromSettings();
        JsonObjectBuilder byStatus = JsonUtil.createObjectBuilder();
        for (DarkState state : DarkState.values()) {
            byStatus.add(String.valueOf(state.getCode()), 0);
        }
        long total = 0;
        for (Object[] row : em.createNamedQuery("Dark.countByStatus", Object[].class).getResultList()) {
            long count = ((Number) row[1]).longValue();
            byStatus.add(String.valueOf(((DarkState) row[0]).getCode()), count);
            total += count;
        }
        return JsonUtil.createObjectBuilder()
                .add("enabled", config.isUsable())
                .add("total", total)
                .add("byStatus", byStatus)
                .add("stuck", count(em.createQuery("SELECT COUNT(d) FROM Dark d WHERE d.status <> :tombstone AND d.attempts >= :max")
                        .setParameter("tombstone", DarkState.TOMBSTONE)
                        .setParameter("max", config.maxAttempts())))
                .add("withErrors", count(em.createQuery("SELECT COUNT(d) FROM Dark d WHERE d.lastError IS NOT NULL")))
                .add("missing", count(em.createNativeQuery("SELECT COUNT(*)" + DATASETS + WITHOUT_ROW)))
                // Local datasets that cannot get a dARK: no PID yet, or a PID longer than the Minter limit.
                .add("notEligible", count(em.createNativeQuery("SELECT COUNT(*)" + DATASETS
                        + " WHERE ds.harvestingclient_id IS NULL AND (o.protocol IS NULL OR o.identifier IS NULL OR "
                        + DarkRepository.PID_LENGTH_SQL + " > " + DarkIds.CLIENT_ITEM_ID_MAX_LENGTH + ")")))
                .build();
    }

    // ---- "assigned" view ----

    public Page<AssignedRow> listAssigned(AssignedQuery q, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNo = Math.max(0, page);
        List<AssignedRow> items = toRows(assignedQuery(q, false).setFirstResult(pageNo * pageSize).setMaxResults(pageSize));
        return new Page<>(items, count(assignedQuery(q, true)), pageNo, pageSize);
    }

    /** All rows of the "assigned" view for CSV, capped at {@link #MAX_EXPORT_ROWS}. */
    public List<AssignedRow> exportAssigned(AssignedQuery q) {
        return toRows(assignedQuery(q, false).setMaxResults(MAX_EXPORT_ROWS));
    }

    /** JSON of one row; empty if it does not exist. */
    public JsonObject rowJson(Long darkId) {
        Dark row = em.find(Dark.class, darkId);
        return row == null ? JsonUtil.createObjectBuilder().build()
                : toJson(toRow(row, DarkConfig.fromSettings().maxAttempts())).build();
    }

    private Query assignedQuery(AssignedQuery q, boolean count) {
        boolean byArk = q.ark() != null && !q.ark().isBlank();
        StringBuilder jpql = new StringBuilder(count ? "SELECT COUNT(d) FROM Dark d" : "SELECT d FROM Dark d");
        jpql.append(" WHERE 1 = 1");
        if (byArk) {
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
        Query query = em.createQuery(jpql.toString());
        if (byArk) {
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

    @SuppressWarnings("unchecked")
    private static List<AssignedRow> toRows(Query query) {
        int maxAttempts = DarkConfig.fromSettings().maxAttempts();
        return ((List<Dark>) query.getResultList()).stream().map(row -> toRow(row, maxAttempts)).toList();
    }

    private static AssignedRow toRow(Dark row, int maxAttempts) {
        Dataset ds = row.getDataset();
        return new AssignedRow(row.getId(), row.getArk(),
                ds == null ? null : ds.getId(),
                ds == null ? null : persistentId(ds),
                ds == null ? null : ds.getDisplayName(),
                row.getStatus(), row.getTarget(), row.getSentVersion(), row.getAttempts(),
                DarkPolicy.isStuck(row.getStatus(), row.getAttempts(), maxAttempts),
                row.getLastError(), row.getCreatedAt(), row.getUpdatedAt());
    }

    // ---- "missing" view ----

    public Page<MissingRow> listMissing(Long datasetId, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNo = Math.max(0, page);
        String where = WITHOUT_ROW + (datasetId != null ? " AND ds.id = ?1" : "");
        Query total = em.createNativeQuery("SELECT COUNT(*)" + DATASETS + where);
        Query ids = em.createNativeQuery("SELECT ds.id" + DATASETS + where + " ORDER BY ds.id DESC");
        if (datasetId != null) {
            total.setParameter(1, datasetId);
            ids.setParameter(1, datasetId);
        }
        List<MissingRow> items = new ArrayList<>();
        for (Long id : DarkRepository.longs(ids.setFirstResult(pageNo * pageSize).setMaxResults(pageSize).getResultList())) {
            Dataset ds = em.find(Dataset.class, id);
            if (ds != null) {
                items.add(new MissingRow(id, persistentId(ds), ds.getDisplayName(), ds.getReleasedVersion() != null));
            }
        }
        return new Page<>(items, count(total), pageNo, pageSize);
    }

    // ---- JSON ----

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

    private static String persistentId(Dataset ds) {
        return ds.getGlobalId() == null ? null : ds.getGlobalId().asString();
    }

    private static long count(Query query) {
        return ((Number) query.getSingleResult()).longValue();
    }
}
