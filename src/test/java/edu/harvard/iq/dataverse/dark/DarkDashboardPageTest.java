package edu.harvard.iq.dataverse.dark;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DarkDashboardPageTest {

    @Test
    void datesAreShownInUtcWhateverTheServerTimeZone() {
        Timestamp instant = Timestamp.from(Instant.parse("2026-11-17T16:21:25Z"));
        TimeZone serverZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Mexico_City"));
            assertEquals("2026-11-17 16:21 UTC", new DarkDashboardPage().format(instant));
        } finally {
            TimeZone.setDefault(serverZone);
        }
        assertEquals("", new DarkDashboardPage().format(null));
    }
}
