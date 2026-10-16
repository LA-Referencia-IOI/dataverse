package edu.harvard.iq.dataverse.api;

import edu.harvard.iq.dataverse.dark.DarkServiceBean.AssignedQuery;
import edu.harvard.iq.dataverse.dark.DarkServiceBean.AssignedRow;
import edu.harvard.iq.dataverse.dark.DarkState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the parameter parsing and CSV of {@link Dark}. */
class DarkApiTest {

    @Test
    void parsesFilters() {
        AssignedQuery q = Dark.query("abc", "u", 42L, true, "createdAt", "asc");
        assertEquals("abc", q.ark());
        assertEquals(DarkState.UPDATE, q.status());
        assertEquals(42L, q.datasetId());
        assertTrue(q.errorsOnly());
        assertTrue(q.ascending());

        AssignedQuery defaults = Dark.query(null, " ", null, false, "updatedAt", "DESC");
        assertNull(defaults.status());
        assertFalse(defaults.ascending());
    }

    @Test
    void rejectsInvalidFilters() {
        assertThrows(IllegalArgumentException.class, () -> Dark.query(null, "X", null, false, "updatedAt", "DESC"));
        assertThrows(IllegalArgumentException.class, () -> Dark.query(null, null, null, false, "ark", "DESC"));
        assertThrows(IllegalArgumentException.class, () -> Dark.query(null, null, null, false, "updatedAt", "UP"));
    }

    @Test
    void csvHasHeaderAndNoInternalFields() throws Exception {
        Timestamp t = Timestamp.from(Instant.parse("2026-09-28T10:00:00Z"));
        AssignedRow row = new AssignedRow(1L, "ark:12345/abc", 7L, "doi:10.5072/FK2/ABC", "Title, with comma",
                DarkState.PUBLISHED, "https://doi.org/10.5072/fk2/abc", "1.0", 0, false, null, t, t);

        String csv = Dark.toCsv(List.of(row));
        String[] lines = csv.split("\r\n");

        assertEquals("ark,datasetId,persistentId,title,status,target,sentVersion,attempts,stuck,lastError,createdAt,updatedAt", lines[0]);
        assertTrue(lines[1].startsWith("ark:12345/abc,7,doi:10.5072/FK2/ABC,\"Title, with comma\",P,"), lines[1]);
        assertFalse(csv.contains("client_item_id"));
        assertFalse(csv.toLowerCase().contains("cid"));
    }
}
