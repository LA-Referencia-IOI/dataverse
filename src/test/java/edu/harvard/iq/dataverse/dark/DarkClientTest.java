package edu.harvard.iq.dataverse.dark;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.json.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DarkClient} against a simulated Minter: a minimal HTTP/1.1 server on a
 * loopback {@link ServerSocket} (the JDK's com.sun.net.httpserver is banned by forbiddenapis).
 */
class DarkClientTest {

    /** A recorded request. */
    record Req(String method, String path, Map<String, String> headers, String body) {
        JsonObject json() {
            return JsonUtil.getJsonObject(body);
        }
    }

    /** A canned response. */
    record Resp(int status, String body, Map<String, String> headers) {
        Resp(int status, String body) {
            this(status, body, Map.of());
        }
    }

    interface Handler {
        Resp handle(Req req);
    }

    private ServerSocket server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private String baseUrl;

    @TempDir
    Path root;
    /** Directory of the key the client signs with. */
    private Path keys;
    private DarkKeyStore.Credential key;

    @BeforeEach
    void generateKey() throws DarkException {
        keys = root.resolve("keys").resolve("dark");
        key = DarkKeyStore.generate(keys, false);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "dark-minter-stub");
        acceptor.setDaemon(true);
        acceptor.start();
        baseUrl = "http://127.0.0.1:" + server.getLocalPort();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                serve(socket);
            } catch (IOException closedOrBroken) {
                // server closed, or the client gave up
            }
        }
    }

    /** Serves one request per connection ({@code Connection: close}). */
    private void serve(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ");
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
        String path = parts[1].contains("?") ? parts[1].substring(0, parts[1].indexOf('?')) : parts[1];

        Req req = new Req(parts[0], path, headers, body);
        requests.add(req);
        Handler h = handlers.get(req.method() + " " + req.path());
        if (h == null) {
            h = handlers.get(req.method() + " *");
        }
        Resp resp = h == null ? new Resp(404, "{\"detail\":\"Not Found\"}") : h.handle(req);

        byte[] bytes = resp.body() == null || resp.status() == 204 ? new byte[0] : resp.body().getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(resp.status()).append(" Stub\r\n")
                .append("Connection: close\r\n");
        if (resp.status() != 204) {
            head.append("Content-Type: application/json\r\n").append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        resp.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') {
                buf.write(b);
            }
        }
        return b == -1 && buf.size() == 0 ? null : buf.toString(StandardCharsets.US_ASCII);
    }

    private DarkClient client(int batchSize) {
        return new DarkClient(config(batchSize), HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                new DarkClient.Breaker(Clock.systemUTC()));
    }

    private DarkConfig config(int batchSize) {
        return new DarkConfig(true, baseUrl, "auth-uuid", "12345", keys.toString(), false, batchSize, "dARK",
                DarkConfig.Target.PID, 20, 5, null);
    }

    private DarkClient client() {
        return client(100);
    }

    private static String arkJson(String ark, String state) {
        return JsonUtil.createObjectBuilder()
                .add("ark", ark).add("state", state).add("target", "https://doi.org/10.5072/fk2/abc")
                .add("metadata_cid", "bafy-meta").add("level1_cid", "bafy-l1").add("level2_cid", "bafy-l2")
                .addNull("client_item_id")
                .build().toString();
    }

    // ---- reserve ----

    @Test
    void reserveBatchSendsItemsAndParsesResults() throws Exception {
        handlers.put("POST /api/v1/arks/batch", req -> {
            var items = req.json().getJsonArray("items");
            var results = JsonUtil.createArrayBuilder();
            for (int i = 0; i < items.size(); i++) {
                String id = items.getJsonObject(i).getString("client_item_id");
                results.add(JsonUtil.createObjectBuilder().add("ark", "ark:/12345/x" + i).add("state", "R").add("client_item_id", id));
            }
            return new Resp(200, JsonUtil.createObjectBuilder().add("results", results).addNull("errors").build().toString());
        });

        DarkClient.ReserveResult r = client().reserveBatch(List.of("doi:10.5072/fk2/a", "doi:10.5072/fk2/b"));

        assertEquals(2, r.results().size());
        assertTrue(r.errors().isEmpty());
        assertEquals("ark:12345/x0", r.results().get(0).ark(), "ARK normalized to compact form");
        assertEquals(DarkState.RESERVED, r.results().get(0).state());
        assertEquals("doi:10.5072/fk2/b", r.results().get(1).clientItemId());

        Req sent = requests.get(0);
        assertEquals("auth-uuid", sent.json().getString("authority_id"));
        assertEquals("12345", sent.json().getString("naan"));
        assertEquals("auth-uuid", sent.headers().get("x-dark-authority-id"));
        assertEquals("application/json", sent.headers().get("content-type"));
    }

    @Test
    void reserveBatchSplitsInChunks() throws Exception {
        handlers.put("POST /api/v1/arks/batch", req -> {
            var items = req.json().getJsonArray("items");
            var results = JsonUtil.createArrayBuilder();
            for (int i = 1; i < items.size(); i++) {
                results.add(JsonUtil.createObjectBuilder().add("ark", "ark:/12345/y").add("state", "R"));
            }
            var errors = JsonUtil.createArrayBuilder().add(JsonUtil.createObjectBuilder()
                    .add("client_item_id", items.getJsonObject(0).getString("client_item_id"))
                    .add("error", "invalid").add("index", 0));
            return new Resp(200, JsonUtil.createObjectBuilder().add("results", results).add("errors", errors).build().toString());
        });

        List<String> ids = IntStream.range(0, 5).mapToObj(i -> "doi:10.5072/fk2/" + i).toList();
        DarkClient.ReserveResult r = client(2).reserveBatch(ids);

        assertEquals(3, requests.size(), "5 items in chunks of 2");
        assertEquals(2, r.results().size());
        assertEquals(3, r.errors().size());
        assertEquals("doi:10.5072/fk2/2", r.errors().get(1).clientItemId());
    }

    @Test
    void reserveBatchThrowsOnServerError() {
        handlers.put("POST /api/v1/arks/batch", req -> new Resp(503, "{\"detail\":\"blockchain unavailable\"}"));
        DarkException e = assertThrows(DarkException.class, () -> client().reserveBatch(List.of("doi:10.5072/a")));
        assertEquals(503, e.getHttpStatus());
        assertEquals("HTTP 503: blockchain unavailable", e.toLastError());
    }

    // ---- put ----

    @Test
    void putSendsMetadataAndParsesResponse() throws Exception {
        handlers.put("PUT /api/v1/arks/ark:12345/abc", req -> new Resp(200, arkJson("ark:/12345/abc", "D")));

        JsonObject l1 = JsonUtil.createObjectBuilder().add("ark", "ark:12345/abc").add("title", "T").build();
        DarkClient.PutResult r = client().put("ark:/12345/abc", "https://doi.org/10.5072/fk2/abc",
                l1, "<resource/>", "oai_datacite", "application/xml");

        assertFalse(r.alreadyInProgress());
        assertEquals(DarkState.DRAFT, r.ark().state());
        assertEquals("bafy-l2", r.ark().level2Cid());

        Req sent = requests.get(0);
        assertEquals("PUT", sent.method());
        assertEquals("/api/v1/arks/ark:12345/abc", sent.path(), "compact ARK, unencoded, in the path");
        JsonObject body = sent.json();
        assertEquals("auth-uuid", body.getString("authority_id"));
        assertEquals("https://doi.org/10.5072/fk2/abc", body.getString("target"));
        assertEquals("T", body.getJsonObject("minimal_metadata").getString("title"));
        assertEquals("<resource/>", body.getString("original_metadata"));
        assertEquals("oai_datacite", body.getString("metadata_schema"));
        assertEquals("application/xml", body.getString("metadata_media_type"));
    }

    @Test
    void put409IsAlreadyInProgressNotAnError() throws Exception {
        handlers.put("PUT *", req -> new Resp(409, "{\"detail\":\"ARK is locked\"}"));
        DarkClient.PutResult r = client().put("ark:12345/abc", "t", JsonUtil.getJsonObject("{}"), "x", "s", "m");
        assertTrue(r.alreadyInProgress());
        assertNull(r.ark());
    }

    @Test
    void put422KeepsValidationMessage() {
        handlers.put("PUT *", req -> new Resp(422,
                "{\"detail\":[{\"loc\":[\"body\",\"minimal_metadata\",\"title\"],\"msg\":\"field required\",\"type\":\"missing\"}]}"));
        DarkException e = assertThrows(DarkException.class,
                () -> client().put("ark:12345/abc", "t", JsonUtil.getJsonObject("{}"), "x", "s", "m"));
        assertEquals(422, e.getHttpStatus());
        assertTrue(e.getMessage().contains("field required"), e.getMessage());
        assertTrue(e.getMessage().contains("title"), e.getMessage());
    }

    // ---- get / delete ----

    @Test
    void getParsesState() throws Exception {
        handlers.put("GET /api/v1/arks/ark:12345/abc", req -> new Resp(200, arkJson("ark:/12345/abc", "P")));
        DarkArkResponse r = client().get("ark:12345/abc");
        assertEquals(DarkState.PUBLISHED, r.state());
        assertEquals("ark:12345/abc", r.ark());
        assertEquals("bafy-meta", r.metadataCid());
    }

    @Test
    void getNotFound() {
        DarkException e = assertThrows(DarkException.class, () -> client().get("ark:12345/missing"));
        assertTrue(e.isNotFound());
    }

    @Test
    void deleteReturnsTrueOn204AndFalseOn409() throws Exception {
        handlers.put("DELETE /api/v1/arks/ark:12345/a", req -> new Resp(204, null));
        handlers.put("DELETE /api/v1/arks/ark:12345/b", req -> new Resp(409, "{\"detail\":\"already tombstoned\"}"));
        assertTrue(client().delete("ark:/12345/a"));
        assertFalse(client().delete("ark:12345/b"));
    }

    @Test
    void deleteThrowsOnServerError() {
        handlers.put("DELETE *", req -> new Resp(500, "boom"));
        DarkException e = assertThrows(DarkException.class, () -> client().delete("ark:12345/a"));
        assertEquals(500, e.getHttpStatus());
        assertEquals("boom", e.getMessage());
    }

    // ---- tombstone (DELETE + confirmation) ----

    @Test
    void tombstoneConfirmedOn204WithoutExtraGet() throws Exception {
        handlers.put("DELETE /api/v1/arks/ark:12345/a", req -> new Resp(204, null));
        assertTrue(client().tombstone("ark:12345/a"));
        assertEquals(1, requests.size(), "no GET needed after a 204");
    }

    @Test
    void tombstone409AlreadyTombstonedIsConfirmedByGet() throws Exception {
        handlers.put("DELETE /api/v1/arks/ark:12345/a", req -> new Resp(409, "{\"detail\":\"already tombstoned\"}"));
        handlers.put("GET /api/v1/arks/ark:12345/a", req -> new Resp(200, arkJson("ark:/12345/a", "T")));
        assertTrue(client().tombstone("ark:12345/a"));
        assertEquals(List.of("DELETE", "GET"), requests.stream().map(Req::method).toList());
    }

    @Test
    void tombstone409WhileLockedIsNotConfirmed() throws Exception {
        // The Minter also answers 409 when the ARK is locked while it processes metadata.
        handlers.put("DELETE /api/v1/arks/ark:12345/a", req -> new Resp(409, "{\"detail\":\"ARK is locked\"}"));
        handlers.put("GET /api/v1/arks/ark:12345/a", req -> new Resp(200, arkJson("ark:/12345/a", "U")));
        assertFalse(client().tombstone("ark:12345/a"), "a locked ARK must not be recorded as tombstoned");
    }

    @Test
    void tombstoneGetFailureAfter409Throws() {
        handlers.put("DELETE *", req -> new Resp(409, "{\"detail\":\"ARK is locked\"}"));
        handlers.put("GET *", req -> new Resp(503, "{\"detail\":\"unavailable\"}"));
        DarkException e = assertThrows(DarkException.class, () -> client().tombstone("ark:12345/a"));
        assertEquals(503, e.getHttpStatus());
    }

    // ---- status batch ----

    @Test
    void statusBatchParsesResultsAndErrors() throws Exception {
        handlers.put("POST /api/v1/arks/status/batch", req -> new Resp(200, "{\"results\":["
                + "{\"ark\":\"ark:/12345/a\",\"status\":" + arkJson("ark:/12345/a", "P") + "},"
                + "{\"ark\":\"ark:/12345/b\",\"error\":{\"code\":\"not_found\",\"message\":\"no such ark\",\"retryable\":false}}"
                + "]}"));

        Map<String, DarkClient.StatusResult> r = client().statusBatch(List.of("ark:12345/a", "ark:12345/b"));

        assertEquals(DarkState.PUBLISHED, r.get("ark:12345/a").ark().state());
        assertTrue(r.get("ark:12345/b").isError());
        assertEquals("not_found", r.get("ark:12345/b").errorCode());
        assertEquals(2, requests.get(0).json().getJsonArray("arks").size());
    }

    @Test
    void statusBatchFallsBackToGetWhenEndpointMissing() throws Exception {
        // No handler for status/batch -> 404
        handlers.put("GET /api/v1/arks/ark:12345/a", req -> new Resp(200, arkJson("ark:/12345/a", "U")));

        Map<String, DarkClient.StatusResult> r = client().statusBatch(List.of("ark:12345/a", "ark:12345/b"));

        assertEquals(DarkState.UPDATE, r.get("ark:12345/a").ark().state());
        assertEquals("not_found", r.get("ark:12345/b").errorCode());
        List<String> calls = new ArrayList<>();
        requests.forEach(q -> calls.add(q.method() + " " + q.path()));
        assertEquals(List.of("POST /api/v1/arks/status/batch", "GET /api/v1/arks/ark:12345/a", "GET /api/v1/arks/ark:12345/b"), calls);
    }

    // ---- ARK records this version cannot read ----

    @Test
    void putWithUnknownStateIsAnErrorNotACrash() {
        handlers.put("PUT *", req -> new Resp(200, arkJson("ark:/12345/a", "X")));
        DarkException e = assertThrows(DarkException.class, () -> client().put("ark:12345/a", "https://doi.org/10.5072/fk2/a",
                JsonUtil.createObjectBuilder().add("ark", "ark:12345/a").build(), "<xml/>", "oai_datacite", "application/xml"));
        assertEquals("Unexpected ARK record from the Minter: Unknown dARK state: X", e.toLastError());
    }

    @Test
    void getWithUnknownStateIsAnErrorNotACrash() {
        handlers.put("GET *", req -> new Resp(200, arkJson("ark:/12345/a", "X")));
        DarkException e = assertThrows(DarkException.class, () -> client().get("ark:12345/a"));
        assertTrue(e.getMessage().contains("Unknown dARK state: X"), e.getMessage());
    }

    @Test
    void statusBatchUnknownStateOnlyAffectsThatArk() throws Exception {
        handlers.put("POST /api/v1/arks/status/batch", req -> new Resp(200, "{\"results\":["
                + "{\"ark\":\"ark:/12345/a\",\"status\":" + arkJson("ark:/12345/a", "X") + "},"
                + "{\"ark\":\"ark:/12345/b\",\"status\":\"P\"},"
                + "{\"ark\":\"ark:/12345/c\",\"status\":" + arkJson("ark:/12345/c", "P") + "}"
                + "]}"));

        Map<String, DarkClient.StatusResult> r = client().statusBatch(List.of("ark:12345/a", "ark:12345/b", "ark:12345/c"));

        assertTrue(r.get("ark:12345/a").isError());
        assertEquals(DarkClient.INVALID_RESPONSE, r.get("ark:12345/a").errorCode());
        assertTrue(r.get("ark:12345/a").errorMessage().contains("Unknown dARK state: X"));
        assertEquals(DarkClient.INVALID_RESPONSE, r.get("ark:12345/b").errorCode(), "a status that is not an object");
        assertEquals(DarkState.PUBLISHED, r.get("ark:12345/c").ark().state(), "the other ARKs are not affected");
    }

    @Test
    void statusByGetUnknownStateIsAnErrorOfThatArk() throws Exception {
        // No handler for status/batch -> 404 -> one GET per ARK
        handlers.put("GET /api/v1/arks/ark:12345/a", req -> new Resp(200, arkJson("ark:/12345/a", "X")));
        handlers.put("GET /api/v1/arks/ark:12345/b", req -> new Resp(200, arkJson("ark:/12345/b", "D")));

        Map<String, DarkClient.StatusResult> r = client().statusBatch(List.of("ark:12345/a", "ark:12345/b"));

        assertTrue(r.get("ark:12345/a").isError());
        assertTrue(r.get("ark:12345/a").errorMessage().contains("Unknown dARK state: X"));
        assertEquals(DarkState.DRAFT, r.get("ark:12345/b").ark().state());
    }

    @Test
    void reserveBatchUnknownStateIsAnItemError() throws Exception {
        handlers.put("POST /api/v1/arks/batch", req -> new Resp(200, "{\"results\":["
                + "{\"ark\":\"ark:/12345/a\",\"state\":\"X\",\"client_item_id\":\"doi:10.5072/fk2/a\"},"
                + "{\"ark\":\"ark:/12345/b\",\"state\":\"R\",\"client_item_id\":\"doi:10.5072/fk2/b\"}"
                + "],\"errors\":null}"));

        DarkClient.ReserveResult r = client().reserveBatch(List.of("doi:10.5072/fk2/a", "doi:10.5072/fk2/b"));

        assertEquals(1, r.results().size());
        assertEquals("ark:12345/b", r.results().get(0).ark());
        assertEquals(1, r.errors().size());
        assertEquals("doi:10.5072/fk2/a", r.errors().get(0).clientItemId());
        assertTrue(r.errors().get(0).error().contains("Unknown dARK state: X"));
    }

    // ---- DARK2 signature ----

    private static final JsonObject LEVEL1 = JsonUtil.createObjectBuilder().add("title", "Título con ñ").build();

    private DarkClient.PutResult put(DarkClient client) throws DarkException {
        return client.put("ark:12345/abc", "https://doi.org/10.5072/fk2/abc", LEVEL1, "<xml/>", "oai_datacite", "application/xml");
    }

    /** Verifies the signature of a recorded request with the public half of the client's key. */
    private boolean signatureIsValid(Req req) throws Exception {
        Map<String, String> h = req.headers();
        String message = String.join("\n", "DARK2", req.method(), req.path(), h.get("x-dark-authority-id"), h.get("x-dark-key-id"),
                h.get("x-dark-timestamp"), h.get("x-dark-nonce"), h.get("x-dark-content-sha256"));
        byte[] spki = HexFormat.of().parseHex("302a300506032b6570032100" + HexFormat.of().formatHex(Base64.getDecoder().decode(key.publicKey())));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(h.get("x-dark-signature")));
    }

    @Test
    void aWriteIsSignedOverTheExactBytesSent() throws Exception {
        handlers.put("PUT *", req -> new Resp(200, arkJson("ark:/12345/abc", "D")));

        put(client());

        Req sent = requests.get(0);
        assertEquals("/api/v1/arks/ark:12345/abc", sent.path());
        assertEquals("auth-uuid", sent.headers().get("x-dark-authority-id"));
        assertEquals(key.keyId(), sent.headers().get("x-dark-key-id"));
        assertEquals(DarkSigner.sha256Hex(sent.body().getBytes(StandardCharsets.UTF_8)), sent.headers().get("x-dark-content-sha256"),
                "digest of the body as received, non-ASCII included");
        long skew = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(sent.headers().get("x-dark-timestamp")));
        assertTrue(skew < 60, "current Unix seconds");
        assertEquals(sent.headers().get("x-dark-nonce"), UUID.fromString(sent.headers().get("x-dark-nonce")).toString());
        assertTrue(signatureIsValid(sent));
    }

    @Test
    void everyWriteIsSigned() throws Exception {
        handlers.put("POST /api/v1/arks/batch", req -> new Resp(200, "{\"results\":[],\"errors\":null}"));
        handlers.put("PUT *", req -> new Resp(200, arkJson("ark:/12345/abc", "D")));
        handlers.put("DELETE *", req -> new Resp(204, null));
        DarkClient client = client();

        client.reserveBatch(List.of("doi:10.5072/fk2/a"));
        put(client);
        client.delete("ark:12345/abc");

        assertEquals(List.of("POST", "PUT", "DELETE"), requests.stream().map(Req::method).toList());
        for (Req sent : requests) {
            assertTrue(signatureIsValid(sent), sent.method());
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                requests.get(2).headers().get("x-dark-content-sha256"), "a DELETE has no body");
        assertEquals(3, requests.stream().map(r -> r.headers().get("x-dark-nonce")).distinct().count(), "a new nonce per request");
    }

    @Test
    void readsCarryTheAuthorityButNoSignature() throws Exception {
        handlers.put("GET *", req -> new Resp(200, arkJson("ark:/12345/abc", "P")));
        handlers.put("POST /api/v1/arks/status/batch", req -> new Resp(200, "{\"results\":[]}"));
        DarkClient client = client();

        client.get("ark:12345/abc");
        client.statusBatch(List.of("ark:12345/abc"));

        for (Req sent : requests) {
            assertEquals("auth-uuid", sent.headers().get("x-dark-authority-id"), sent.method());
            assertNull(sent.headers().get("x-dark-signature"), sent.method());
            assertNull(sent.headers().get("x-dark-nonce"), sent.method());
        }
    }

    @Test
    void readsWorkWithoutAKey() throws Exception {
        Files.delete(keys.resolve("private.pk8"));
        handlers.put("GET *", req -> new Resp(200, arkJson("ark:/12345/abc", "P")));
        assertEquals(DarkState.PUBLISHED, client().get("ark:12345/abc").state());
    }

    @Test
    void aWriteWithoutAUsableKeyIsNeverSent() throws Exception {
        Files.setPosixFilePermissions(keys.resolve("private.pk8"), PosixFilePermissions.fromString("rw-r--r--"));

        DarkException e = assertThrows(DarkException.class, () -> put(client()));

        assertTrue(e.getMessage().contains("cannot be used"), e.getMessage());
        assertFalse(e.isRejection());
        assertTrue(requests.isEmpty(), "nothing leaves unsigned");
    }

    @Test
    void aRedirectIsNotFollowed() {
        handlers.put("PUT *", req -> new Resp(307, "", Map.of("Location", baseUrl + "/elsewhere")));

        DarkException e = assertThrows(DarkException.class, () -> put(client()));

        assertEquals(307, e.getHttpStatus());
        assertEquals(1, requests.size());
    }

    @Test
    void aRefusedSignatureKeepsOnlyTheErrorCode() {
        handlers.put("PUT *", req -> new Resp(401, "{\"detail\":\"gateway said something\"}",
                Map.of("X-DARK-Error-Code", "SIGNED_AUTH_KEY_INVALID")));

        DarkException e = assertThrows(DarkException.class, () -> put(client()));

        assertEquals("HTTP 401: SIGNED_AUTH_KEY_INVALID", e.toLastError());
        assertTrue(e.isAuthenticationFailure());
        assertFalse(e.isRejection(), "it is not about this row");
    }

    @Test
    void anUnauthorizedAnswerWithoutACodeDoesNotEchoItsBody() {
        handlers.put("PUT *", req -> new Resp(401, "{\"detail\":\"gateway said something\"}", Map.of("X-DARK-Error-Code", "not a code")));

        DarkException e = assertThrows(DarkException.class, () -> put(client()));

        assertEquals("HTTP 401: PUT ark:12345/abc not authorized", e.toLastError());
    }

    @Test
    void aPermissionErrorIsARejectionOfThatRequest() {
        handlers.put("PUT *", req -> new Resp(403, "{\"detail\":\"AUTHORITY_MISMATCH\"}", Map.of("X-DARK-Error-Code", "AUTHORITY_MISMATCH")));

        DarkException e = assertThrows(DarkException.class, () -> put(client()));

        assertEquals("HTTP 403: AUTHORITY_MISMATCH", e.toLastError());
        assertTrue(e.isRejection());
        assertFalse(e.isAuthenticationFailure());
    }

    @Test
    void otherErrorsKeepTheCodeAndTheExplanation() {
        handlers.put("PUT *", req -> new Resp(503, "{\"detail\":\"registry down\"}", Map.of("X-DARK-Error-Code", "AUTH_REGISTRY_UNAVAILABLE")));

        DarkException e = assertThrows(DarkException.class, () -> put(client()));

        assertEquals("HTTP 503: AUTH_REGISTRY_UNAVAILABLE: registry down", e.toLastError());
        assertFalse(e.isRejection());
    }

    @Test
    void aReplayIsAnErrorNotMetadataInProgress() {
        handlers.put("PUT *", req -> new Resp(409, "{\"detail\":\"SIGNED_REQUEST_REPLAY\"}", Map.of("X-DARK-Error-Code", "SIGNED_REQUEST_REPLAY")));
        handlers.put("DELETE *", req -> new Resp(409, "{\"detail\":\"SIGNED_REQUEST_REPLAY\"}", Map.of("X-DARK-Error-Code", "SIGNED_REQUEST_REPLAY")));

        assertEquals("HTTP 409: SIGNED_REQUEST_REPLAY", assertThrows(DarkException.class, () -> put(client())).toLastError());
        assertEquals(409, assertThrows(DarkException.class, () -> client().delete("ark:12345/abc")).getHttpStatus());
    }

    @Test
    void refusedSignaturesPauseTheCalls() {
        handlers.put("PUT *", req -> new Resp(401, "", Map.of("X-DARK-Error-Code", "SIGNED_AUTH_KEY_INVALID")));
        DarkClient client = client();

        for (int i = 0; i < DarkClient.Breaker.FAILURES_TO_OPEN; i++) {
            assertTrue(assertThrows(DarkException.class, () -> put(client)).isAuthenticationFailure());
        }
        DarkException paused = assertThrows(DarkException.class, () -> put(client));

        assertTrue(paused.getMessage().contains("paused until"), paused.getMessage());
        assertEquals(DarkClient.Breaker.FAILURES_TO_OPEN, requests.size(), "the paused call was not sent");
    }

    @Test
    void authorityReportsWhetherItIsActiveAndItsNaans() throws Exception {
        handlers.put("GET /api/v1/authority/auth-uuid", req -> new Resp(200,
                "{\"uuid\":\"auth-uuid\",\"wallet_address\":\"0xabc\",\"naans\":[\"12345\",\"99999\"],\"active\":true}"));

        DarkClient.AuthorityInfo authority = client().authority();

        assertTrue(authority.active());
        assertEquals(List.of("12345", "99999"), authority.naans());
        assertNull(requests.get(0).headers().get("x-dark-signature"));
    }

    // ---- circuit breaker ----

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void breakerOpensAfterConsecutiveUnansweredCallsAndClosesAfterAWhile() throws Exception {
        ManualClock clock = new ManualClock();
        DarkClient.Breaker breaker = new DarkClient.Breaker(clock);

        for (int i = 1; i < DarkClient.Breaker.FAILURES_TO_OPEN; i++) {
            breaker.failed();
        }
        breaker.check();
        breaker.failed();
        DarkException open = assertThrows(DarkException.class, breaker::check);
        assertFalse(open.isRejection(), "a paused call does not count towards max-attempts");

        clock.advance(DarkClient.Breaker.OPEN_FOR);
        breaker.check();
        breaker.failed();
        assertThrows(DarkException.class, breaker::check, "one more unanswered call opens it again");
    }

    @Test
    void anAnswerResetsTheBreaker() throws Exception {
        DarkClient.Breaker breaker = new DarkClient.Breaker(new ManualClock());
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i < DarkClient.Breaker.FAILURES_TO_OPEN; i++) {
                breaker.failed();
            }
            breaker.succeeded();
        }
        breaker.check();
    }

    @Test
    void callsFailAtOnceWhileTheMinterIsUnreachable() throws Exception {
        server.close();
        DarkClient client = new DarkClient(config(100),
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), new DarkClient.Breaker(new ManualClock()));

        for (int i = 0; i < DarkClient.Breaker.FAILURES_TO_OPEN; i++) {
            assertTrue(assertThrows(DarkException.class, () -> client.get("ark:12345/a")).getMessage().startsWith("Minter unreachable:"));
        }
        assertTrue(assertThrows(DarkException.class, () -> client.get("ark:12345/a")).getMessage().contains("paused until"));
    }

    @Test
    void anErrorResponseIsAnAnswerAndKeepsTheBreakerClosed() {
        handlers.put("GET *", req -> new Resp(503, "{\"detail\":\"unavailable\"}"));
        DarkClient client = client();
        for (int i = 0; i < DarkClient.Breaker.FAILURES_TO_OPEN + 2; i++) {
            assertEquals(503, assertThrows(DarkException.class, () -> client.get("ark:12345/a")).getHttpStatus());
        }
    }

    // ---- transport ----

    @Test
    void unreachableMinterThrowsWithoutStatus() throws IOException {
        server.close();
        DarkException e = assertThrows(DarkException.class, () -> client().get("ark:12345/a"));
        assertEquals(0, e.getHttpStatus());
        assertTrue(e.getMessage().startsWith("Minter unreachable"), e.getMessage());
    }

    @Test
    void arkPathRejectsNonArk() {
        assertThrows(IllegalArgumentException.class, () -> DarkClient.arkPath("doi:10.1/x"));
    }
}
