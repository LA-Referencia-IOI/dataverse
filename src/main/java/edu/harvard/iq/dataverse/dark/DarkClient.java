package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * HTTP client for the dARK Minter API ({@code /api/v1}).
 * <p>
 * Reservations always use {@code POST /arks/batch} (never {@code POST /arks}), which is
 * idempotent per {@code authority_id + naan + client_item_id}. A {@code 409} on
 * {@code PUT} means "metadata already being processed" and on {@code DELETE} "already
 * tombstoned, or locked" (see {@link #tombstone}); both are returned as results, not thrown.
 * <p>
 * An ARK record this version cannot read (for example a state other than R/D/U/P/T) is an
 * error of that ARK ({@link DarkException}, or a per-item error in the batch calls), never
 * an unchecked exception.
 * <p>
 * Requests that modify data ({@code POST /arks/batch}, {@code PUT}, {@code DELETE}) are
 * signed with this installation's key ({@link DarkSigner}); reads are not, as the Minter
 * specifies. Every request carries the authority in {@code X-DARK-Authority-Id}. A signed
 * request is sent once, to the exact URL that was signed: redirects are never followed.
 * <p>
 * This class performs blocking HTTP calls: never use it inside a command transaction.
 */
public class DarkClient {

    private static final Logger logger = Logger.getLogger(DarkClient.class.getCanonicalName());

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    /** Error code of a signed request whose nonce the Minter had already admitted. */
    private static final String REPLAY = "SIGNED_REQUEST_REPLAY";

    /**
     * One HTTP client for the application: it owns a connection pool and a selector thread.
     * HTTP/1.1 because the Minter (FastAPI/uvicorn) does not speak h2c. The timeout is set on
     * each request, and also covers the connection.
     */
    private static final HttpClient SHARED_HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Breaker SHARED_BREAKER = new Breaker(Clock.systemUTC());

    private final DarkConfig config;
    private final HttpClient http;
    private final Breaker breaker;

    /**
     * Circuit breaker: after {@link #FAILURES_TO_OPEN} consecutive calls that got no answer,
     * or whose signature the Minter refused (401), calls fail at once for {@link #OPEN_FOR};
     * then one call is tried again. Both problems affect every row alike, so a job with many
     * rows neither waits a timeout per row nor sends a request per row that cannot succeed.
     */
    static final class Breaker {

        static final int FAILURES_TO_OPEN = 3;
        static final Duration OPEN_FOR = Duration.ofSeconds(60);

        private final Clock clock;
        private int failures;
        private Instant openUntil = Instant.MIN;

        Breaker(Clock clock) {
            this.clock = clock;
        }

        synchronized void check() throws DarkException {
            if (clock.instant().isBefore(openUntil)) {
                throw new DarkException("Minter unreachable or refusing this installation's key; calls are paused until " + openUntil);
            }
        }

        synchronized void succeeded() {
            failures = 0;
        }

        synchronized void failed() {
            if (++failures >= FAILURES_TO_OPEN) {
                openUntil = clock.instant().plus(OPEN_FOR);
            }
        }
    }

    /** Result of a batch reservation: one entry per accepted item plus per-item errors. */
    public record ReserveResult(List<DarkArkResponse> results, List<ItemError> errors) {
    }

    /** Per-item error of a batch call. */
    public record ItemError(String clientItemId, String error) {
    }

    /** Result of a {@code PUT}: {@code alreadyInProgress} is true on {@code 409}, with {@code ark == null}. */
    public record PutResult(DarkArkResponse ark, boolean alreadyInProgress) {
    }

    /** {@code errorCode} of a {@link StatusResult} whose ARK record could not be read. */
    public static final String INVALID_RESPONSE = "invalid_response";

    /** The authority as registered in the Minter. */
    public record AuthorityInfo(boolean active, List<String> naans) {
    }

    /** Status of one ARK: either {@code ark} or an error ({@code errorCode}, e.g. {@code not_found}). */
    public record StatusResult(DarkArkResponse ark, String errorCode, String errorMessage) {
        public boolean isError() {
            return ark == null;
        }
    }

    public DarkClient(DarkConfig config) {
        this(config, SHARED_HTTP, SHARED_BREAKER);
    }

    DarkClient(DarkConfig config, HttpClient http, Breaker breaker) {
        this.config = config;
        this.http = http;
        this.breaker = breaker;
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

            HttpResponse<String> response = send("POST", "/arks/batch", body, true);
            expectSuccess(response, "reserve batch");
            JsonObject json = parseObject(response.body());

            JsonArray res = json.containsKey("results") && !json.isNull("results") ? json.getJsonArray("results") : JsonValue.EMPTY_JSON_ARRAY;
            for (JsonValue v : res) {
                try {
                    results.add(parseArk(v));
                } catch (DarkException e) {
                    String id = v.getValueType() == JsonValue.ValueType.OBJECT
                            ? DarkArkResponse.string(v.asJsonObject(), "client_item_id") : null;
                    errors.add(new ItemError(id, e.getMessage()));
                }
            }
            if (json.containsKey("errors") && !json.isNull("errors")) {
                for (JsonValue v : json.getJsonArray("errors")) {
                    JsonObject e = v.asJsonObject();
                    errors.add(new ItemError(DarkArkResponse.string(e, "client_item_id"), DarkArkResponse.string(e, "error")));
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
        HttpResponse<String> response = send("PUT", arkPath(ark), body, true);
        if (isBusinessConflict(response)) {
            logger.fine("dARK PUT " + ark + " returned 409: metadata already in progress");
            return new PutResult(null, true);
        }
        expectSuccess(response, "PUT " + ark);
        return new PutResult(parseArk(parseObject(response.body())), false);
    }

    /** {@code GET /arks/{ark}}; throws with status 404 if the ARK does not exist. */
    public DarkArkResponse get(String ark) throws DarkException {
        HttpResponse<String> response = send("GET", arkPath(ark), null, false);
        expectSuccess(response, "GET " + ark);
        return parseArk(parseObject(response.body()));
    }

    /**
     * Tombstones an ARK ({@code DELETE /arks/{ark}}).
     *
     * @return true if tombstoned now ({@code 204}), false on {@code 409} (see {@link #tombstone}).
     */
    public boolean delete(String ark) throws DarkException {
        HttpResponse<String> response = send("DELETE", arkPath(ark), null, true);
        if (isBusinessConflict(response)) {
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

            HttpResponse<String> response = send("POST", "/arks/status/batch", body, false);
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
                    try {
                        out.put(ark, new StatusResult(parseArk(r.get("status")), null, null));
                    } catch (DarkException e) {
                        out.put(ark, new StatusResult(null, INVALID_RESPONSE, e.getMessage()));
                    }
                } else if (r.containsKey("error") && !r.isNull("error")) {
                    JsonObject e = r.getJsonObject("error");
                    out.put(ark, new StatusResult(null, DarkArkResponse.string(e, "code"), DarkArkResponse.string(e, "message")));
                }
            }
        }
        return out;
    }

    /**
     * What the Minter knows about this installation's authority ({@code GET /authority/{uuid}}).
     * It does not include the registered key: only a signed request proves that the key is accepted.
     */
    public AuthorityInfo authority() throws DarkException {
        HttpResponse<String> response = send("GET", "/authority/" + config.authorityId(), null, false);
        expectSuccess(response, "GET authority");
        JsonObject json = parseObject(response.body());
        List<String> naans = new ArrayList<>();
        if (json.containsKey("naans") && json.get("naans").getValueType() == JsonValue.ValueType.ARRAY) {
            json.getJsonArray("naans").forEach(naan -> naans.add(naan.getValueType() == JsonValue.ValueType.STRING
                    ? ((JsonString) naan).getString() : naan.toString()));
        }
        return new AuthorityInfo(json.getBoolean("active", false), naans);
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

    /**
     * Sends one request. The JSON body is serialized once: the same bytes are signed and sent.
     *
     * @param signed true for a request that modifies data
     */
    private HttpResponse<String> send(String method, String path, JsonObject json, boolean signed) throws DarkException {
        breaker.check();
        URI uri = URI.create(config.minterUrl() + path);
        byte[] body = json == null ? new byte[0] : json.toString().getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (signed) {
            signatureHeaders(method, uri, body).forEach(request::header);
        } else {
            request.header(DarkSigner.AUTHORITY_HEADER, config.authorityId());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            breaker.failed();
            logger.log(Level.FINE, "dARK Minter unreachable", e);
            throw new DarkException("Minter unreachable: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DarkException("Interrupted while calling the Minter", e);
        }
        int status = response.statusCode();
        if (status == 401) {
            breaker.failed();
        } else {
            breaker.succeeded();
        }
        if (status >= 300 && status < 400) {
            throw new DarkException("Unexpected redirect; correct dataverse.dark.minter-url instead of following it", status);
        }
        return response;
    }

    /** The key is read on every signed request, so a replaced or damaged key is noticed at once. */
    private Map<String, String> signatureHeaders(String method, URI uri, byte[] body) throws DarkException {
        if (config.keyPath() == null) {
            throw new DarkException("dataverse.dark.key-directory is missing");
        }
        DarkKeyStore.Credential key = DarkKeyStore.load(config.keyPath());
        try {
            return DarkSigner.headers(key.privateKey(), key.keyId(), method, uri, config.authorityId(), body,
                    Instant.now().getEpochSecond(), UUID.randomUUID());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DarkException("Could not sign the request: " + e.getMessage());
        }
    }

    /**
     * A 409 means "the Minter is busy with this ARK" (metadata in progress, locked, already
     * tombstoned), except when it reports that this very signed request was already admitted.
     */
    private static boolean isBusinessConflict(HttpResponse<String> response) {
        return response.statusCode() == 409 && !REPLAY.equals(errorCode(response));
    }

    /** {@code X-DARK-Error-Code} of a response, only if it has the shape of a code. */
    private static String errorCode(HttpResponse<String> response) {
        String code = response.headers().firstValue(DarkSigner.ERROR_CODE_HEADER).orElse("");
        return ERROR_CODE.matcher(code).matches() ? code : null;
    }

    private static void expectSuccess(HttpResponse<String> response, String operation) throws DarkException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        // An authentication or permission answer may come from a gateway: only its code is kept.
        // Any other error keeps the Minter's explanation, e.g. which metadata field is invalid.
        String code = errorCode(response);
        String detail = status == 401 || status == 403 ? null : errorMessage(response.body(), operation);
        String message = code == null ? (detail == null ? operation + " not authorized" : detail)
                : (detail == null || detail.equals(code) ? code : code + ": " + detail);
        throw new DarkException(message, status);
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

    /** Reads an ARK record; one that is not an object or has an unknown state is a {@link DarkException}. */
    private static DarkArkResponse parseArk(JsonValue record) throws DarkException {
        if (record == null || record.getValueType() != JsonValue.ValueType.OBJECT) {
            throw new DarkException("Unexpected ARK record from the Minter: " + record);
        }
        try {
            return DarkArkResponse.fromJson(record.asJsonObject());
        } catch (IllegalArgumentException e) {
            throw new DarkException("Unexpected ARK record from the Minter: " + e.getMessage(), e);
        }
    }

    private static JsonObject parseObject(String body) throws DarkException {
        try {
            return JsonUtil.getJsonObject(body);
        } catch (Exception e) {
            throw new DarkException("Invalid JSON from the Minter: " + e.getMessage(), e);
        }
    }
}
