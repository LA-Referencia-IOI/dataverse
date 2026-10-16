package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.DataverseSession;
import edu.harvard.iq.dataverse.PermissionsWrapper;
import edu.harvard.iq.dataverse.dark.DarkServiceBean.AssignedQuery;
import edu.harvard.iq.dataverse.dark.DarkServiceBean.AssignedRow;
import edu.harvard.iq.dataverse.dark.DarkServiceBean.MissingRow;
import edu.harvard.iq.dataverse.dark.DarkServiceBean.Page;
import edu.harvard.iq.dataverse.util.BundleUtil;
import edu.harvard.iq.dataverse.util.JsfHelper;
import jakarta.ejb.EJB;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Backing bean of {@code dashboard-dark.xhtml}: superuser dashboard of the dARK
 * integration (same data as {@code GET /api/admin/dark}, without going through the
 * localhost-only admin API).
 */
@ViewScoped
@Named("DarkDashboardPage")
public class DarkDashboardPage implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(DarkDashboardPage.class.getCanonicalName());
    private static final int PAGE_SIZE = 25;

    @Inject
    DataverseSession session;
    @Inject
    PermissionsWrapper permissionsWrapper;
    @EJB
    DarkServiceBean darkService;

    private String view = "assigned";
    private String arkFilter;
    private String statusFilter;
    private Long datasetIdFilter;
    private boolean errorsOnly;
    private int page;

    private Page<AssignedRow> assigned;
    private Page<MissingRow> missing;
    private JsonObject counts;

    public String init() {
        if (session.getUser() == null || !session.getUser().isAuthenticated() || !session.getUser().isSuperuser()) {
            return permissionsWrapper.notAuthorized();
        }
        load();
        return null;
    }

    private void load() {
        counts = darkService.countsJson().build();
        if (isMissingView()) {
            missing = darkService.listMissing(datasetIdFilter, page, PAGE_SIZE);
            assigned = null;
        } else {
            DarkState state = statusFilter == null || statusFilter.isBlank() ? null : DarkState.fromCode(statusFilter);
            assigned = darkService.listAssigned(new AssignedQuery(arkFilter, state, datasetIdFilter, errorsOnly, "updatedAt", false),
                    page, PAGE_SIZE);
            missing = null;
        }
    }

    // ---- navigation ----

    public void showAssigned() {
        view = "assigned";
        page = 0;
        load();
    }

    public void showMissing() {
        view = "missing";
        page = 0;
        load();
    }

    public void applyFilters() {
        page = 0;
        load();
    }

    public void clearFilters() {
        arkFilter = null;
        statusFilter = null;
        datasetIdFilter = null;
        errorsOnly = false;
        applyFilters();
    }

    public void nextPage() {
        if (page + 1 < getPageCount()) {
            page++;
            load();
        }
    }

    public void previousPage() {
        if (page > 0) {
            page--;
            load();
        }
    }

    // ---- actions ----

    /** Retry (rows with an error) or assign (missing view). */
    public void assign(Long datasetId) {
        runAction(() -> darkService.assign(datasetId));
    }

    /** Status refresh of a row in D/U. */
    public void refresh(Long datasetId) {
        runAction(() -> darkService.refreshDataset(datasetId));
    }

    public void assignAll() {
        DarkJob job = DarkJob.tryStart("assign-all");
        if (job == null) {
            JsfHelper.addWarningMessage(BundleUtil.getStringFromBundle("dashboard.dark.job.running"));
            return;
        }
        darkService.assignAllAsync(job, 0);
        JsfHelper.addSuccessMessage(BundleUtil.getStringFromBundle("dashboard.dark.job.started", List.of(job.getName())));
    }

    public void runRefresh() {
        DarkJob job = DarkJob.tryStart("refresh");
        if (job == null) {
            JsfHelper.addWarningMessage(BundleUtil.getStringFromBundle("dashboard.dark.job.running"));
            return;
        }
        darkService.refreshAllAsync(job);
        JsfHelper.addSuccessMessage(BundleUtil.getStringFromBundle("dashboard.dark.job.started", List.of(job.getName())));
    }

    /** Streams the CSV of the "assigned" view with the current filters. */
    public void exportCsv() {
        FacesContext fc = FacesContext.getCurrentInstance();
        ExternalContext ec = fc.getExternalContext();
        try {
            DarkState state = statusFilter == null || statusFilter.isBlank() ? null : DarkState.fromCode(statusFilter);
            List<AssignedRow> rows = darkService.exportAssigned(
                    new AssignedQuery(arkFilter, state, datasetIdFilter, errorsOnly, "updatedAt", false));
            byte[] csv = DarkCsv.toCsv(rows).getBytes(StandardCharsets.UTF_8);
            ec.responseReset();
            ec.setResponseContentType("text/csv; charset=UTF-8");
            ec.setResponseContentLength(csv.length);
            ec.setResponseHeader("Content-Disposition", "attachment; filename=\"dark-registry.csv\"");
            try (OutputStream out = ec.getResponseOutputStream()) {
                out.write(csv);
            }
            fc.responseComplete();
        } catch (IOException e) {
            logger.log(Level.WARNING, "dARK CSV export failed", e);
            JsfHelper.addErrorMessage(BundleUtil.getStringFromBundle("dashboard.dark.action.failed", List.of(e.getMessage())));
        }
    }

    private interface DarkAction {
        JsonObject run() throws DarkException;
    }

    private void runAction(DarkAction action) {
        try {
            JsonObject result = action.run();
            String status = result.containsKey("status") ? result.getString("status") : "";
            JsfHelper.addSuccessMessage(BundleUtil.getStringFromBundle("dashboard.dark.action.done",
                    List.of(result.getString("ark", "") + " " + stateLabel(status))));
        } catch (DarkException e) {
            JsfHelper.addErrorMessage(BundleUtil.getStringFromBundle("dashboard.dark.action.failed", List.of(e.getMessage())));
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK dashboard action failed", e);
            JsfHelper.addErrorMessage(BundleUtil.getStringFromBundle("dashboard.dark.action.failed", List.of(String.valueOf(e.getMessage()))));
        }
        load();
    }

    // ---- view helpers ----

    public boolean isEnabled() {
        return counts != null && counts.getBoolean("enabled", false);
    }

    public long getCount(String key) {
        return counts == null ? 0 : counts.getJsonNumber(key).longValue();
    }

    public long getStatusCount(String code) {
        return counts == null ? 0 : counts.getJsonObject("byStatus").getJsonNumber(code).longValue();
    }

    public String stateLabel(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return BundleUtil.getStringFromBundle("dashboard.dark.state." + code);
    }

    public String stateLabel(DarkState state) {
        return state == null ? "" : stateLabel(String.valueOf(state.getCode()));
    }

    public String format(Date date) {
        return date == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(date);
    }

    public String getLastJob() {
        DarkJob job = DarkJob.last();
        if (job == null) {
            return null;
        }
        JsonObject j = job.toJson().build();
        return BundleUtil.getStringFromBundle("dashboard.dark.job.last",
                List.of(j.getString("name"), j.getString("state"), j.getString("startedAt")))
                + " " + j.getJsonObject("counts");
    }

    public List<String> getStatusOptions() {
        return List.of("R", "D", "U", "P", "T");
    }

    public Map<String, String> getStatusLabels() {
        return Map.of("R", stateLabel("R"), "D", stateLabel("D"), "U", stateLabel("U"), "P", stateLabel("P"), "T", stateLabel("T"));
    }

    public boolean isMissingView() {
        return "missing".equals(view);
    }

    public List<AssignedRow> getAssignedRows() {
        return assigned == null ? List.of() : assigned.items();
    }

    public List<MissingRow> getMissingRows() {
        return missing == null ? List.of() : missing.items();
    }

    public long getTotal() {
        return isMissingView() ? (missing == null ? 0 : missing.total()) : (assigned == null ? 0 : assigned.total());
    }

    public int getPageCount() {
        return (int) Math.max(1, (getTotal() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public int getPageNumber() {
        return page + 1;
    }

    // ---- getters / setters ----

    public String getView() {
        return view;
    }

    public String getArkFilter() {
        return arkFilter;
    }

    public void setArkFilter(String arkFilter) {
        this.arkFilter = arkFilter;
    }

    public String getStatusFilter() {
        return statusFilter;
    }

    public void setStatusFilter(String statusFilter) {
        this.statusFilter = statusFilter;
    }

    public Long getDatasetIdFilter() {
        return datasetIdFilter;
    }

    public void setDatasetIdFilter(Long datasetIdFilter) {
        this.datasetIdFilter = datasetIdFilter;
    }

    public boolean isErrorsOnly() {
        return errorsOnly;
    }

    public void setErrorsOnly(boolean errorsOnly) {
        this.errorsOnly = errorsOnly;
    }
}
