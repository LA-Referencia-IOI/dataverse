package edu.harvard.iq.dataverse.api;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.dark.DarkException;
import edu.harvard.iq.dataverse.dark.DarkCsv;
import edu.harvard.iq.dataverse.dark.DarkJob;
import edu.harvard.iq.dataverse.dark.DarkReportBean;
import edu.harvard.iq.dataverse.dark.DarkServiceBean;
import edu.harvard.iq.dataverse.dark.DarkReportBean.AssignedQuery;
import edu.harvard.iq.dataverse.dark.DarkReportBean.AssignedRow;
import edu.harvard.iq.dataverse.dark.DarkReportBean.MissingRow;
import edu.harvard.iq.dataverse.dark.DarkReportBean.Page;
import edu.harvard.iq.dataverse.dark.DarkState;
import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.ejb.EJB;
import jakarta.json.JsonArrayBuilder;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Administrative API of the dARK integration ({@code /api/admin/dark}). Long operations run in the
 * background and respond immediately; their progress is at {@code GET /api/admin/dark/job}.
 * Neither {@code client_item_id} nor CIDs are exposed.
 */
@Path("admin/dark")
@Tag(name = "Admin", description = "Administrative Dataverse operations.")
public class Dark extends AbstractApiBean {

    private static final Logger logger = Logger.getLogger(Dark.class.getCanonicalName());

    @EJB
    DarkServiceBean darkService;

    @EJB
    DarkReportBean darkReport;

    // ---- cron / lifecycle ----

    @POST
    @Path("refresh")
    @Operation(summary = "Refresh dARK states (cron)",
            description = "Retries pending reservations, checks D/U states, resends pending versions and retries failed PUTs and tombstones. "
                    + "Does not assign dARKs to datasets that never had one (use assign-all). Runs in the background.")
    public Response refresh() {
        return started(darkService::startRefresh);
    }

    @POST
    @Path("refresh/{id}")
    @Operation(summary = "Refresh the dARK of one dataset")
    public Response refreshOne(@Parameter(description = "Dataset id or :persistentId") @PathParam("id") String id) {
        try {
            Dataset ds = findDatasetOrDie(id);
            return ok(darkService.refreshDataset(ds.getId()));
        } catch (WrappedResponse wr) {
            return wr.getResponse();
        } catch (DarkException e) {
            return darkError(e);
        }
    }

    @POST
    @Path("assign/{id}")
    @Operation(summary = "Assign (or retry) the dARK of one dataset",
            description = "Reserves an ARK if needed. For a published dataset, adds the Other ID to the published version (updatecurrent) and sends its metadata.")
    public Response assign(@Parameter(description = "Dataset id or :persistentId") @PathParam("id") String id) {
        try {
            Dataset ds = findDatasetOrDie(id);
            return ok(darkService.assign(ds.getId()));
        } catch (WrappedResponse wr) {
            return wr.getResponse();
        } catch (DarkException e) {
            return darkError(e);
        }
    }

    @POST
    @Path("assign-all")
    @Operation(summary = "Assign dARKs to local datasets that have none (initial load)",
            description = "Runs in the background, in batches of dataverse.dark.batch-size.")
    public Response assignAll(@Parameter(description = "Maximum number of datasets (0 = all)")
            @QueryParam("limit") @DefaultValue("0") int limit) {
        if (limit < 0) {
            return badRequest("limit must be 0 or greater");
        }
        return started(() -> darkService.startAssignAll(limit));
    }

    @POST
    @Path("tombstone/{id}")
    @Operation(summary = "Tombstone the dARK of one dataset (irreversible)")
    public Response tombstone(@Parameter(description = "Dataset id or :persistentId") @PathParam("id") String id) {
        try {
            Dataset ds = findDatasetOrDie(id);
            return ok(darkService.tombstoneDataset(ds.getId()));
        } catch (WrappedResponse wr) {
            return wr.getResponse();
        } catch (DarkException e) {
            return darkError(e);
        }
    }

    // ---- signing key ----

    @POST
    @Path("key")
    @Operation(summary = "Generate the key that signs the requests to the Minter",
            description = "Returns only the public key (base64) and its id, to hand to the Minter administrator. "
                    + "An existing key is never overwritten unless replace=true, which is only accepted while dARK is disabled.")
    public Response generateKey(@Parameter(description = "Discard the current key and generate a new one")
            @QueryParam("replace") @DefaultValue("false") boolean replace) {
        try {
            return ok(darkService.generateKey(replace));
        } catch (DarkException e) {
            return darkError(e);
        }
    }

    @GET
    @Path("key")
    @Operation(summary = "Public key that signs the requests to the Minter",
            description = "Also asks the Minter whether the authority is active and the NAAN authorized. "
                    + "It cannot tell whether the key is registered: the first signed request does.")
    public Response key() {
        try {
            return ok(darkService.key());
        } catch (DarkException e) {
            return darkError(e);
        }
    }

    // ---- status ----

    @GET
    @Path("count")
    @Operation(summary = "dARK counts by state, stuck, with errors and missing")
    public Response count() {
        return ok(darkReport.counts());
    }

    @GET
    @Path("job")
    @Operation(summary = "Status of the last background dARK job")
    public Response job() {
        DarkJob job = DarkJob.last();
        if (job == null) {
            return notFound("No dARK job has run since the application started");
        }
        return ok(job.toJson());
    }

    // ---- dashboard ----

    @GET
    @Operation(summary = "dARK dashboard data",
            description = "view=assigned (rows of the dark table) or view=missing (local datasets without a dARK).")
    public Response list(@QueryParam("view") @DefaultValue("assigned") String view,
            @QueryParam("ark") String ark,
            @QueryParam("status") String status,
            @QueryParam("datasetId") Long datasetId,
            @QueryParam("errorsOnly") @DefaultValue("false") boolean errorsOnly,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size,
            @QueryParam("sort") @DefaultValue("updatedAt") String sort,
            @QueryParam("direction") @DefaultValue("DESC") String direction) {
        if (page < 0 || size < 1) {
            return badRequest("page must be >= 0 and size >= 1 (max " + DarkReportBean.MAX_PAGE_SIZE + ")");
        }
        if ("missing".equalsIgnoreCase(view)) {
            Page<MissingRow> p = darkReport.listMissing(datasetId, page, size);
            JsonArrayBuilder items = JsonUtil.createArrayBuilder();
            p.items().forEach(r -> items.add(DarkReportBean.toJson(r)));
            return ok(JsonUtil.createObjectBuilder()
                    .add("view", "missing").add("page", p.page()).add("size", p.size()).add("total", p.total())
                    .add("items", items));
        }
        if (!"assigned".equalsIgnoreCase(view)) {
            return badRequest("view must be 'assigned' or 'missing'");
        }
        AssignedQuery q;
        try {
            q = query(ark, status, datasetId, errorsOnly, sort, direction);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        Page<AssignedRow> p = darkReport.listAssigned(q, page, size);
        JsonArrayBuilder items = JsonUtil.createArrayBuilder();
        p.items().forEach(r -> items.add(DarkReportBean.toJson(r)));
        return ok(JsonUtil.createObjectBuilder()
                .add("view", "assigned").add("page", p.page()).add("size", p.size()).add("total", p.total())
                .add("items", items));
    }

    @GET
    @Path("export")
    @Produces("text/csv")
    @Operation(summary = "dARK registry as CSV (same filters as the assigned view)")
    public Response export(@QueryParam("ark") String ark,
            @QueryParam("status") String status,
            @QueryParam("datasetId") Long datasetId,
            @QueryParam("errorsOnly") @DefaultValue("false") boolean errorsOnly,
            @QueryParam("sort") @DefaultValue("updatedAt") String sort,
            @QueryParam("direction") @DefaultValue("DESC") String direction) {
        AssignedQuery q;
        try {
            q = query(ark, status, datasetId, errorsOnly, sort, direction);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ok(DarkCsv.toCsv(darkReport.exportAssigned(q)), MediaType.valueOf("text/csv"), "dark-registry.csv");
        } catch (IOException e) {
            return error(Status.INTERNAL_SERVER_ERROR, "Could not build CSV: " + e.getMessage());
        }
    }

    // ---- helpers ----

    static AssignedQuery query(String ark, String status, Long datasetId, boolean errorsOnly, String sort, String direction) {
        DarkState state = null;
        if (status != null && !status.isBlank()) {
            state = DarkState.fromCode(status);
        }
        if (!"createdAt".equalsIgnoreCase(sort) && !"updatedAt".equalsIgnoreCase(sort)) {
            throw new IllegalArgumentException("sort must be createdAt or updatedAt");
        }
        if (!"ASC".equalsIgnoreCase(direction) && !"DESC".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be ASC or DESC");
        }
        return new AssignedQuery(ark, state, datasetId, errorsOnly, sort, "ASC".equalsIgnoreCase(direction));
    }

    private interface JobStart {
        DarkJob start() throws DarkException;
    }

    private Response started(JobStart start) {
        try {
            DarkJob job = start.start();
            if (job == null) {
                return error(Status.CONFLICT, "Another dARK job is running: " + DarkJob.last().getName());
            }
            return ok("dARK " + job.getName() + " started; see /api/admin/dark/job", job.toJson());
        } catch (DarkException e) {
            return darkError(e);
        }
    }

    private Response darkError(DarkException e) {
        int code = e.getHttpStatus();
        Status status = switch (code) {
            case 400 -> Status.BAD_REQUEST;
            case 404 -> Status.NOT_FOUND;
            case 409 -> Status.CONFLICT;
            case 503 -> Status.SERVICE_UNAVAILABLE;
            case 0 -> Status.BAD_GATEWAY;
            default -> code >= 500 || code == 502 ? Status.BAD_GATEWAY : Status.BAD_REQUEST;
        };
        logger.fine("dARK API error: " + e.toLastError());
        return error(status, e.getMessage());
    }
}
