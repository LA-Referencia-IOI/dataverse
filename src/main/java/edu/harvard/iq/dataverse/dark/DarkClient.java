package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the dARK Minter API ({@code /api/v1}).
 * <p>
 * Reservations always use {@code POST /arks/batch} (never {@code POST /arks}), which is
 * idempotent per {@code authority_id + naan + client_item_id}. A {@code 409} on
 * {@code PUT} means "metadata already being processed" and on {@code DELETE} "already
 * tombstoned"; both are returned as results, not thrown.
 * <p>
 * This class performs blocking HTTP calls: never use it inside a command transaction.
 */
public class DarkClient {

    private static final Logger logger = Logger.getLogger(DarkClient.class.getCanonicalName());

    private static final int MAX_ERROR_LENGTH = 1000;

    private final DarkConfig config;
    private final HttpClient http;

    /** Result of a batch reservation: one entry per accepted item plus per-item errors. */
    public record ReserveResult(List<DarkArkResponse> results, List<ItemError> errors) {
    }

    /** Per-item error of a batch call. */
    public record ItemError(String clientItemId, int index, String error) {
    }

    /** Result of a {@code PUT}: {@code alreadyInProgress} is true on {@code 409}, with {@code ark == null}. */
    public record PutResult(DarkArkResponse ark, boolean alreadyInProgress) {
    }

    /** Status of one ARK: either {@code ark} or an error ({@code errorCode}, e.g. {@code not_found}). */
    public record StatusResult(DarkArkResponse ark, String errorCode, String errorMessage) {
        public boolean isError() {
            return ark == null;
        }
    }

    public DarkClient(DarkConfig config) {
        // HTTP/1.1: the Minter (FastAPI/uvicorn) does not speak h2c; avoids the upgrade attempt.
        this(config, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    DarkClient(DarkConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * Reserves ARKs for the given {@code client_item_id}s, split in chunks of
     * {@code batch-size}. A failing chunk throws; per-item errors are returned.
     */
    public ReserveResult reserveBatch(List<String> clientItemIds) throws DarkException {
        List<DarkArkResponse> results = new ArrayList<>();
        List<ItemError> errors = new ArrayList<>();
        int size = config.batchSize();
        for (int from = 0; from < clientItemIds.size(); from += size) {
            List<String> chunk = clientItemIds.subList(from, Math.min(from + size, clientItemIds.size()));
            JsonArrayBuilder items = JsonUtil.createArrayBuilder();
            chunk.forEach(id -> items.add(JsonUtil.createObjectBuilder().add("client_item_id", id)));
            JsonObject body = JsonUtil.createObjectBuilder()
                    .add("authority_id", config.authorityId())
                    .add("naan", config.naan())
                    .add("items", items)
                    .build();

            HttpResponse<String> response = send(request("/arks/batch").POST(jsonBody(body)).build());
            expectSuccess(response, "reserve batch");
            JsonObject json = parseObject(response.body());

            JsonArray res = json.containsKey("results") && !json.isNull("results") ? json.getJsonArray("results") : JsonValue.EMPTY_JSON_ARRAY;
            for (JsonValue v : res) {
                results.add(DarkArkResponse.fromJson(v.asJsonObject()));
            }
            if (json.containsKey("errors") && !json.isNull("errors")) {
                for (JsonValue v : json.getJsonArray("errors")) {
                    JsonObject e = v.asJsonObject();
                    int index = e.containsKey("index") && !e.isNull("index") ? e.getInt("index") + from : -1;
                    errors.add(new ItemError(DarkArkResponse.string(e, "client_item_id"), index,
                            DarkArkResponse.string(e, "error")));
                }
            }
        }
        return new ReserveResult(results, errors);
    }

    /**
     * Sends Level 1 and Level 2 metadata ({@code PUT /arks/{ark}}): R → D, P → U.
     */
    public PutResult put(String ark, String target, JsonObject minimalMetadata, String originalMetadata,
            String metadataSchema, String metadataMediaType) throws DarkException {
        JsonObject body = JsonUtil.createObjectBuilder()
                .add("authority_id", config.authorityId())
                .add("target", target)
                .add("minimal_metadata", minimalMetadata)
                .add("original_metadata", originalMetadata)
                .add("metadata_schema", metadataSchema)
                .add("metadata_media_type", metadataMediaType)
                .build();
        HttpResponse<String> response = send(request(arkPath(ark)).PUT(jsonBody(body)).build());
        if (response.statusCode() == 409) {
            logger.fine("dARK PUT " + ark + " returned 409: metadata already in progress");
            return new PutResult(null, true);
        }
        expectSuccess(response, "PUT " + ark);
        return new PutResult(DarkArkResponse.fromJson(parseObject(response.body())), false);
    }

    /** {@code GET /arks/{ark}}; throws with status 404 if the ARK does not exist. */
    public DarkArkResponse get(String ark) throws DarkException {
        HttpResponse<String> response = send(request(arkPath(ark)).GET().build());
        expectSuccess(response, "GET " + ark);
        return DarkArkResponse.fromJson(parseObject(response.body()));
    }

    /**
     * Tombstones an ARK ({@code DELETE /arks/{ark}}).
     *
     * @return true if tombstoned now ({@code 204}), false if it already was ({@code 409}).
     */
    public boolean delete(String ark) throws DarkException {
        HttpResponse<String> response = send(request(arkPath(ark)).DELETE().build());
        if (response.statusCode() == 409) {
            return false;
        }
        expectSuccess(response, "DELETE " + ark);
        return true;
    }

    /**
     * Tombstones an ARK and confirms it. A {@code 409} on {@code DELETE} is ambiguous: the
     * ARK is either already tombstoned or locked while the Minter processes metadata. In
     * that case the real state is read with {@code GET}.
     *
     * @return true only if the ARK is in {@link DarkState#TOMBSTONE} afterwards; false if it
     *         is still active (locked), so the caller keeps the tombstone pending and retries.
     */
    public boolean tombstone(String ark) throws DarkException {
        if (delete(ark)) {
            return true;
        }
        DarkArkResponse current = get(ark);
        return current.state() == DarkState.TOMBSTONE;
    }

    /**
     * Status of several ARKs through the (undocumented) {@code POST /arks/status/batch},
     * in chunks of up to 100. Falls back to one {@code GET} per ARK if the endpoint
     * does not exist ({@code 404}/{@code 405}).
     *
     * @return map keyed by the compact ARK, in request order.
     */
    public Map<String, StatusResult> statusBatch(List<String> arks) throws DarkException {
        Map<String, StatusResult> out = new LinkedHashMap<>();
        int size = Math.min(config.batchSize(), DarkConfig.MINTER_MAX_BATCH);
        for (int from = 0; from < arks.size(); from += size) {
            List<String> chunk = arks.subList(from, Math.min(from + size, arks.size()));
            JsonArrayBuilder list = JsonUtil.createArrayBuilder();
            chunk.forEach(list::add);
            JsonObject body = JsonUtil.createObjectBuilder().add("arks", list).build();

            HttpResponse<String> response = send(request("/arks/status/batch").POST(jsonBody(body)).build());
            if (response.statusCode() == 404 || response.statusCode() == 405) {
                logger.fine("dARK status/batch not available (HTTP " + response.statusCode() + "), falling back to GET");
                for (String ark : chunk) {
                    out.put(DarkIds.normalizeArk(ark), statusByGet(ark));
                }
                continue;
            }
            expectSuccess(response, "status batch");
            JsonObject json = parseObject(response.body());
            if (!json.containsKey("results") || json.isNull("results")) {
                continue;
            }
            for (JsonValue v : json.getJsonArray("results")) {
                JsonObject r = v.asJsonObject();
                String ark = DarkIds.normalizeArk(DarkArkResponse.string(r, "ark"));
                if (r.containsKey("status") && !r.isNull("status")) {
                    out.put(ark, new StatusResult(DarkArkResponse.fromJson(r.getJsonObject("status")), null, null));
                } else if (r.containsKey("error") && !r.isNull("error")) {
                    JsonObject e = r.getJsonObject("error");
                    out.put(ark, new StatusResult(null, DarkArkResponse.string(e, "code"), DarkArkResponse.string(e, "message")));
                }
            }
        }
        return out;
    }

    private StatusResult statusByGet(String ark) {
        try {
            return new StatusResult(get(ark), null, null);
        } catch (DarkException e) {
            return new StatusResult(null, e.isNotFound() ? "not_found" : "http_error", e.toLastError());
        }
    }

    // ---- plumbing ----

    /** ARKs go in the path in compact form, unencoded (the Minter uses a {@code path} parameter). */
    static String arkPath(String ark) {
        String compact = DarkIds.normalizeArk(ark);
        if (compact == null) {
            throw new IllegalArgumentException("Not an ARK: " + ark);
        }
        return "/arks/" + compact;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.minterUrl() + path))
                .timeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (config.authorityHeaderEnabled() && config.authorityId() != null) {
            b.header(config.authorityHeader(), config.authorityId());
        }
        if (config.apiToken() != null && !config.apiToken().isBlank()) {
            b.header("Authorization", "Bearer " + config.apiToken());
        }
        return b;
    }

    private static HttpRequest.BodyPublisher jsonBody(JsonObject body) {
        return HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest request) throws DarkException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.FINE, "dARK Minter unreachable", e);
            throw new DarkException("Minter unreachable: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DarkException("Interrupted while calling the Minter", e);
        }
    }

    private static void expectSuccess(HttpResponse<String> response, String operation) throws DarkException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw new DarkException(errorMessage(response.body(), operation), status);
    }

    /** Extracts FastAPI's {@code detail} (string or validation list) or falls back to the raw body. */
    static String errorMessage(String body, String operation) {
        String msg = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonObject json = JsonUtil.getJsonObject(body);
                if (json.containsKey("detail") && !json.isNull("detail")) {
                    JsonValue detail = json.get("detail");
                    if (detail.getValueType() == JsonValue.ValueType.STRING) {
                        msg = json.getString("detail");
                    } else if (detail.getValueType() == JsonValue.ValueType.ARRAY) {
                        List<String> parts = new ArrayList<>();
                        for (JsonValue d : detail.asJsonArray()) {
                            if (d.getValueType() == JsonValue.ValueType.OBJECT) {
                                JsonObject o = d.asJsonObject();
                                String loc = o.containsKey("loc") ? o.get("loc").toString() : "";
                                parts.add((loc.isEmpty() ? "" : loc + " ") + DarkArkResponse.string(o, "msg"));
                            } else {
                                parts.add(d.toString());
                            }
                        }
                        msg = String.join("; ", parts);
                    } else {
                        msg = detail.toString();
                    }
                } else if (json.containsKey("error")) {
                    msg = DarkArkResponse.string(json, "error");
                }
            } catch (Exception notJson) {
                msg = body.trim();
            }
        }
        if (msg == null || msg.isBlank()) {
            msg = operation + " failed";
        }
        return msg.length() > MAX_ERROR_LENGTH ? msg.substring(0, MAX_ERROR_LENGTH) : msg;
    }

    private static JsonObject parseObject(String body) throws DarkException {
        try {
            return JsonUtil.getJsonObject(body);
        } catch (Exception e) {
            throw new DarkException("Invalid JSON from the Minter: " + e.getMessage(), e);
        }
    }
}
