package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@LocalJvmSettings
class DarkConfigTest {

    @Test
    void disabledByDefault() {
        DarkConfig c = DarkConfig.fromSettings();
        assertFalse(c.enabled());
        assertFalse(c.isUsable());
        assertNull(c.minterUrl());
        assertEquals(100, c.batchSize());
        assertEquals("X-Authority-Id", c.authorityHeader());
        assertTrue(c.authorityHeaderEnabled());
        assertEquals("dARK", c.otherIdAgency());
        assertEquals(DarkConfig.Target.PID, c.target());
        assertEquals(20, c.maxAttempts());
        assertEquals(30, c.httpTimeoutSeconds());
        assertTrue(c.tombstoneUnpublishedDrafts());
        assertNull(c.publisher(), "no default: falls back to the root collection name");
    }

    @Test
    @JvmSetting(key = JvmSettings.DARK_ENABLED, value = "true")
    @JvmSetting(key = JvmSettings.DARK_MINTER_URL, value = "http://minter.test/")
    @JvmSetting(key = JvmSettings.DARK_AUTHORITY_ID, value = "auth-uuid")
    @JvmSetting(key = JvmSettings.DARK_NAAN, value = "99999")
    @JvmSetting(key = JvmSettings.DARK_BATCH_SIZE, value = "500")
    @JvmSetting(key = JvmSettings.DARK_TARGET, value = "landing")
    @JvmSetting(key = JvmSettings.DARK_AUTHORITY_HEADER_ENABLED, value = "false")
    void readsSettings() {
        DarkConfig c = DarkConfig.fromSettings();
        assertTrue(c.isUsable());
        assertEquals("http://minter.test/api/v1", c.minterUrl());
        assertEquals("auth-uuid", c.authorityId());
        assertEquals("99999", c.naan());
        assertEquals(DarkConfig.MINTER_MAX_BATCH, c.batchSize(), "batch size is capped to the Minter maximum");
        assertEquals(DarkConfig.Target.LANDING, c.target());
        assertFalse(c.authorityHeaderEnabled());
    }

    @Test
    @JvmSetting(key = JvmSettings.DARK_ENABLED, value = "true")
    @JvmSetting(key = JvmSettings.DARK_MINTER_URL, value = "http://minter.test")
    void enabledButIncompleteIsNotUsable() {
        assertFalse(DarkConfig.fromSettings().isUsable());
    }

    @Test
    void targetValues() {
        assertEquals(DarkConfig.Target.PID, DarkConfig.Target.parse("pid"));
        assertEquals(DarkConfig.Target.PID, DarkConfig.Target.parse("DOI"), "'doi' kept for compatibility");
        assertEquals(DarkConfig.Target.PID, DarkConfig.Target.parse(null));
        assertEquals(DarkConfig.Target.LANDING, DarkConfig.Target.parse(" Landing "));
        assertEquals(DarkConfig.Target.PID, DarkConfig.Target.parse("landig"), "unknown values fall back to pid (with a warning)");
    }

    @Test
    void normalizeMinterUrl() {
        assertEquals("http://m/api/v1", DarkConfig.normalizeMinterUrl("http://m"));
        assertEquals("http://m/api/v1", DarkConfig.normalizeMinterUrl("http://m/api/v1/"));
        assertEquals("http://m/x/api/v1", DarkConfig.normalizeMinterUrl(" http://m/x// "));
        assertNull(DarkConfig.normalizeMinterUrl(" "));
    }

    @Test
    void toStringHidesToken() {
        DarkConfig c = new DarkConfig(true, "http://m", "a", "1", "secret-token", true, null, 10, null, null, 5, 5, true, null);
        assertFalse(c.toString().contains("secret-token"));
    }

    @Test
    void publisherFallsBackToRootCollection() {
        DarkConfig unset = new DarkConfig(true, "http://m", "a", "1", null, true, null, 10, null, null, 5, 5, true, "  ");
        assertNull(unset.publisher(), "blank means unset");
        assertEquals("Root", unset.resolvePublisher("Root"));
        assertNull(unset.resolvePublisher(" "));

        DarkConfig set = new DarkConfig(true, "http://m", "a", "1", null, true, null, 10, null, null, 5, 5, true, " My Repository ");
        assertEquals("My Repository", set.resolvePublisher("Root"));
    }

    @Test
    @JvmSetting(key = JvmSettings.DARK_PUBLISHER, value = "Repositorio de Datos")
    void readsPublisherSetting() {
        assertEquals("Repositorio de Datos", DarkConfig.fromSettings().resolvePublisher("Root"));
    }
}
