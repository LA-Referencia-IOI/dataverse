package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.GlobalId;
import edu.harvard.iq.dataverse.mocks.MocksFactory;
import edu.harvard.iq.dataverse.pidproviders.doi.AbstractDOIProvider;
import edu.harvard.iq.dataverse.pidproviders.handle.HandlePidProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DarkIdsTest {

    @ParameterizedTest
    @CsvSource({
        "ark:12345/abc123, ark:12345/abc123",
        "ark:/12345/abc123, ark:12345/abc123",
        "ARK:/12345/abc123, ark:12345/abc123",
        "' ark:/12345/fk2/x.y ', ark:12345/fk2/x.y",
        "https://n2t.net/ark:/12345/abc123, ark:12345/abc123",
        "https://dark.example.org/ark:12345/abc123, ark:12345/abc123"
    })
    void normalizeArk(String input, String expected) {
        assertEquals(expected, DarkIds.normalizeArk(input));
    }

    @Test
    void normalizeArkRejectsNonArks() {
        assertNull(DarkIds.normalizeArk(null));
        assertNull(DarkIds.normalizeArk("doi:10.5072/FK2/ABC"));
        assertNull(DarkIds.normalizeArk("ark:12345"));
    }

    @ParameterizedTest
    @CsvSource({
        "doi:10.5072/FK2/ABC123, doi:10.5072/fk2/abc123",
        "DOI:10.5072/FK2/ABC123, doi:10.5072/fk2/abc123",
        "10.5072/FK2/ABC123, doi:10.5072/fk2/abc123",
        "https://doi.org/10.5072/FK2/ABC123, doi:10.5072/fk2/abc123",
        "http://dx.doi.org/10.5072/FK2/ABC123, doi:10.5072/fk2/abc123"
    })
    void normalizeDoi(String input, String expected) {
        assertEquals(expected, DarkIds.normalizeDoi(input));
    }

    @Test
    void normalizeDoiRejectsNonDois() {
        assertNull(DarkIds.normalizeDoi(null));
        assertNull(DarkIds.normalizeDoi("hdl:1902.1/12345"));
        assertNull(DarkIds.normalizeDoi("doi:11.5072/x"));
    }

    @Test
    void overlengthDoisAreRejectedInsteadOfColliding() {
        String prefix = "doi:10.5072/" + "a".repeat(88); // 12 + 88 = 100 chars, the Minter limit
        assertEquals(100, prefix.length());
        assertEquals(prefix, DarkIds.normalizeDoi(prefix));
        assertNull(DarkIds.normalizeDoi(prefix + "x"));
        assertNull(DarkIds.normalizeDoi(prefix + "y"));
    }

    // ---- every PID type gets a client_item_id ----

    @ParameterizedTest
    @CsvSource({
        // DOI: lower case
        "doi, doi:10.5072/FK2/ABC123, doi:10.5072/fk2/abc123",
        // Handle: lower case (case-insensitive for ASCII)
        "hdl, hdl:20.500.12345/ABC, hdl:20.500.12345/abc",
        // PermaLink: unchanged (may be case-sensitive)
        "perma, perma:DVaBc123, perma:DVaBc123",
        // PID provider plugin: unchanged
        "ark, ark:99999/XyZ, ark:99999/XyZ"
    })
    void normalizePidByType(String protocol, String pid, String expected) {
        assertEquals(expected, DarkIds.normalizePid(protocol, pid));
    }

    @Test
    void normalizePidRejectsEmptyAndOverlength() {
        assertNull(DarkIds.normalizePid(null, "hdl:1/2"));
        assertNull(DarkIds.normalizePid("hdl", " "));
        assertNull(DarkIds.normalizePid("perma", "perma:" + "x".repeat(95)), "101 characters");
        assertEquals(100, DarkIds.normalizePid("perma", "perma:" + "x".repeat(94)).length());
    }

    @Test
    void clientItemIdOfDoiDataset() {
        Dataset ds = dataset(AbstractDOIProvider.DOI_PROTOCOL, "10.5072", "FK2/ABC123", "/", AbstractDOIProvider.DOI_RESOLVER_URL);
        assertEquals("doi:10.5072/fk2/abc123", DarkIds.clientItemId(ds));
        assertEquals("10.5072/fk2/abc123", DarkIds.pidValue(DarkIds.clientItemId(ds)));
        assertEquals("doi", DarkIds.pidSchema("doi"));
        assertEquals("https://doi.org/10.5072/fk2/abc123", DarkIds.resolverUrl(ds));
    }

    @Test
    void clientItemIdOfHandleDataset() {
        Dataset ds = dataset(HandlePidProvider.HDL_PROTOCOL, "20.500.12345", "ABC", "/", HandlePidProvider.HDL_RESOLVER_URL);
        assertEquals("hdl:20.500.12345/abc", DarkIds.clientItemId(ds));
        assertEquals("20.500.12345/abc", DarkIds.pidValue(DarkIds.clientItemId(ds)));
        assertEquals("handle", DarkIds.pidSchema("hdl"));
        assertEquals("https://hdl.handle.net/20.500.12345/ABC", DarkIds.resolverUrl(ds));
    }

    @Test
    void clientItemIdOfPermaLinkDataset() {
        Dataset ds = dataset("perma", "DV", "aBc123", "", "https://repo.example.org/citation?persistentId=perma:");
        assertEquals("perma:DVaBc123", DarkIds.clientItemId(ds), "case is preserved");
        assertEquals("DVaBc123", DarkIds.pidValue(DarkIds.clientItemId(ds)));
        assertEquals("permalink", DarkIds.pidSchema("perma"));
        assertEquals("https://repo.example.org/citation?persistentId=perma:DVaBc123", DarkIds.resolverUrl(ds));
    }

    @Test
    void clientItemIdIsNullWithoutPid() {
        Dataset ds = MocksFactory.makeDataset();
        ds.setGlobalId(null);
        assertNull(DarkIds.clientItemId(ds));
        assertNull(DarkIds.clientItemId(null));
        assertNull(DarkIds.resolverUrl(null));
    }

    /**
     * DvObject.getGlobalId() re-parses the PID through the configured PID providers, which do
     * not exist in unit tests; this dataset returns exactly the given GlobalId.
     */
    private static Dataset dataset(String protocol, String authority, String identifier, String separator, String urlPrefix) {
        GlobalId pid = new GlobalId(protocol, authority, identifier, separator, urlPrefix, null);
        return new Dataset() {
            @Override
            public GlobalId getGlobalId() {
                return pid;
            }
        };
    }
}
