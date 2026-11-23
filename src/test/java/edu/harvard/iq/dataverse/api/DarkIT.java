package edu.harvard.iq.dataverse.api;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests of {@code /api/admin/dark}. The API tests run with dARK enabled
 * or disabled. {@link #lifecycleAgainstMinter()} needs a running instance with
 * {@code dataverse.dark.enabled=true} pointing to a Minter (e.g. the sandbox) and
 * {@code -Ddark.it.e2e=true}; it is skipped otherwise.
 */
public class DarkIT {

    private static final String BASE = "/api/admin/dark";

    @BeforeAll
    public static void setUp() {
        RestAssured.baseURI = UtilIT.getRestAssuredBaseUri();
    }

    private static boolean darkEnabled() {
        return given().get(BASE + "/count").then().extract().jsonPath().getBoolean("data.enabled");
    }

    @Test
    public void countHasAllStates() {
        Response r = given().get(BASE + "/count");
        r.then().assertThat().statusCode(OK.getStatusCode())
                .body("data.enabled", notNullValue())
                .body("data.total", notNullValue())
                .body("data.missing", notNullValue());
        Map<String, Object> byStatus = r.jsonPath().getMap("data.byStatus");
        assertEquals(List.of("D", "P", "R", "T", "U"), byStatus.keySet().stream().sorted().toList());
    }

    @Test
    public void listIsPaginated() {
        given().queryParam("size", 1000).get(BASE).then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data.view", equalTo("assigned"))
                .body("data.page", equalTo(0))
                .body("data.size", equalTo(100))
                .body("data.items", notNullValue());

        given().queryParam("view", "missing").queryParam("size", 5).get(BASE).then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data.view", equalTo("missing"))
                .body("data.size", equalTo(5));
    }

    @Test
    public void listValidatesParameters() {
        given().queryParam("view", "other").get(BASE).then().assertThat().statusCode(BAD_REQUEST.getStatusCode());
        given().queryParam("status", "X").get(BASE).then().assertThat().statusCode(BAD_REQUEST.getStatusCode());
        given().queryParam("sort", "ark").get(BASE).then().assertThat().statusCode(BAD_REQUEST.getStatusCode());
        given().queryParam("direction", "UP").get(BASE).then().assertThat().statusCode(BAD_REQUEST.getStatusCode());
        given().queryParam("page", -1).get(BASE).then().assertThat().statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    public void listDoesNotExposeInternalFields() {
        String body = given().queryParam("size", 100).get(BASE).then().statusCode(OK.getStatusCode()).extract().asString();
        assertFalse(body.contains("clientItemId") || body.contains("client_item_id"));
        assertFalse(body.contains("Cid\"") || body.contains("_cid"));
    }

    @Test
    public void exportIsCsv() {
        Response r = given().get(BASE + "/export");
        r.then().assertThat()
                .statusCode(OK.getStatusCode())
                .contentType(startsWith("text/csv"))
                .body(startsWith("ark,datasetId,persistentId,title,status"));
    }

    @Test
    public void unknownDatasetIs404() {
        given().post(BASE + "/assign/999999999").then().assertThat().statusCode(NOT_FOUND.getStatusCode());
        given().post(BASE + "/refresh/999999999").then().assertThat().statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    public void jobEndpointResponds() {
        int status = given().get(BASE + "/job").statusCode();
        assertTrue(status == OK.getStatusCode() || status == NOT_FOUND.getStatusCode(), "status " + status);
    }

    @Test
    public void disabledIntegrationChangesNothing() {
        assumeTrue(!darkEnabled(), "dARK is enabled on this instance");

        given().post(BASE + "/refresh").then().assertThat().statusCode(SERVICE_UNAVAILABLE.getStatusCode());
        given().post(BASE + "/assign-all").then().assertThat().statusCode(SERVICE_UNAVAILABLE.getStatusCode());

        Response user = UtilIT.createRandomUser();
        String token = UtilIT.getApiTokenFromResponse(user);
        String alias = UtilIT.getAliasFromResponse(UtilIT.createRandomDataverse(token));
        Response ds = UtilIT.createRandomDatasetViaNativeApi(alias, token);
        ds.then().assertThat().statusCode(CREATED.getStatusCode());
        Integer datasetId = UtilIT.getDatasetIdFromResponse(ds);

        // No row and no Other ID: the dataset only shows up as "missing".
        given().queryParam("datasetId", datasetId).get(BASE).then().assertThat()
                .statusCode(OK.getStatusCode()).body("data.total", equalTo(0));
        given().queryParam("view", "missing").queryParam("datasetId", datasetId).get(BASE).then().assertThat()
                .statusCode(OK.getStatusCode()).body("data.total", equalTo(1));

        UtilIT.deleteDatasetViaNativeApi(datasetId, token);
        UtilIT.deleteDataverse(alias, token);
    }

    /**
     * End to end against a Minter: create → R → publish → Other ID + D → refresh → P →
     * new version → U → refresh → P → deaccession (ARK kept) → destroy → T.
     */
    @Test
    public void lifecycleAgainstMinter() throws Exception {
        assumeTrue(Boolean.getBoolean("dark.it.e2e"), "set -Ddark.it.e2e=true to run against a Minter");
        assumeTrue(darkEnabled(), "dARK is not enabled on this instance");

        Response user = UtilIT.createRandomUser();
        String username = UtilIT.getUsernameFromResponse(user);
        String token = UtilIT.getApiTokenFromResponse(user);
        UtilIT.makeSuperUser(username);
        String alias = UtilIT.getAliasFromResponse(UtilIT.createRandomDataverse(token));
        UtilIT.publishDataverseViaNativeApi(alias, token).then().assertThat().statusCode(OK.getStatusCode());
        Response ds = UtilIT.createRandomDatasetViaNativeApi(alias, token);
        ds.then().assertThat().statusCode(CREATED.getStatusCode());
        Integer datasetId = UtilIT.getDatasetIdFromResponse(ds);

        // create → R
        JsonPath row = waitFor(datasetId, s -> s.equals("R"), 60, false);
        String ark = row.getString("ark");
        assertNotNull(ark);

        // publish → Other ID + D
        UtilIT.publishDatasetViaNativeApi(datasetId, "major", token).then().assertThat().statusCode(OK.getStatusCode());
        waitFor(datasetId, s -> s.equals("D") || s.equals("P"), 120, false);
        String versionJson = UtilIT.nativeGet(datasetId, token).asString();
        assertTrue(versionJson.contains(ark), "Other ID with the ARK in the published version");

        // refresh → P
        waitFor(datasetId, s -> s.equals("P"), 600, true);

        // new version → U → refresh → P
        given().header(UtilIT.API_TOKEN_HTTP_HEADER, token)
                .body("{\"fields\":[{\"typeName\":\"title\",\"value\":\"dARK IT v2\"}]}")
                .contentType("application/json")
                .put("/api/datasets/" + datasetId + "/editMetadata?replace=true")
                .then().assertThat().statusCode(OK.getStatusCode());
        UtilIT.publishDatasetViaNativeApi(datasetId, "major", token).then().assertThat().statusCode(OK.getStatusCode());
        waitFor(datasetId, s -> s.equals("U") || s.equals("P"), 120, false);
        JsonPath p2 = waitFor(datasetId, s -> s.equals("P"), 600, true);
        assertEquals("2.0", p2.getString("sentVersion"));

        // deaccession of every version: the dataset still exists and can be published again,
        // so its ARK is kept
        UtilIT.deaccessionDataset(datasetId, "2.0", "dARK IT", null, token).then().assertThat().statusCode(OK.getStatusCode());
        UtilIT.deaccessionDataset(datasetId, "1.0", "dARK IT", null, token).then().assertThat().statusCode(OK.getStatusCode());
        given().post(BASE + "/refresh/" + datasetId).then().assertThat().statusCode(OK.getStatusCode());
        assertEquals("P", waitFor(datasetId, s -> true, 30, false).getString("status"));

        // destroy → T (the row survives the dataset, found by its ARK)
        UtilIT.destroyDataset(datasetId, token);
        waitForArk(ark, "T", 120);
        UtilIT.deleteDataverse(alias, token);
    }

    /** Polls the assigned view by ARK (the dataset no longer exists) until the status matches, calling refresh. */
    private static void waitForArk(String ark, String expected, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            given().post(BASE + "/refresh");
            List<Map<String, Object>> items = given().queryParam("ark", ark).get(BASE).then().extract().jsonPath().getList("data.items");
            if (items != null && !items.isEmpty()) {
                last = (String) items.get(0).get("status");
                if (expected.equals(last)) {
                    return;
                }
            }
            Thread.sleep(5000);
        }
        throw new AssertionError(ark + " did not reach dARK state " + expected + "; last=" + last);
    }

    /** Polls the assigned view of a dataset until its status matches (optionally calling refresh). */
    private static JsonPath waitFor(Integer datasetId, Predicate<String> status, int seconds, boolean refresh) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            if (refresh) {
                given().post(BASE + "/refresh/" + datasetId);
            }
            JsonPath jp = given().queryParam("datasetId", datasetId).get(BASE).then().extract().jsonPath();
            List<Map<String, Object>> items = jp.getList("data.items");
            if (items != null && !items.isEmpty()) {
                last = (String) items.get(0).get("status");
                if (status.test(last)) {
                    return new JsonPath(given().queryParam("datasetId", datasetId).get(BASE).asString()).setRootPath("data.items[0]");
                }
            }
            Thread.sleep(5000);
        }
        throw new AssertionError("dataset " + datasetId + " did not reach the expected dARK state; last=" + last);
    }
}
