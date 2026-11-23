package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@LocalJvmSettings
class DarkConfigTest {

    @TempDir
    Path root;

    private DarkConfig config(boolean enabled, String minterUrl, boolean allowHttp, Path keyDirectory) {
        return new DarkConfig(enabled, minterUrl, "auth-uuid", "12345", keyDirectory == null ? null : keyDirectory.toString(),
                allowHttp, 100, null, null, 20, 30, null);
    }

    private Path keyDirectoryWithKey() throws Exception {
        Path directory = root.resolve("keys").resolve("dark");
        DarkKeyStore.generate(directory, false);
        return directory;
    }

    @Test
    void disabledByDefault() {
        DarkConfig c = DarkConfig.fromSettings();
        assertFalse(c.enabled());
        assertFalse(c.isUsable());
        assertNull(c.inactiveReason(), "simply not enabled: nothing to warn about");
        assertNull(c.minterUrl());
        assertNull(c.keyPath());
        assertFalse(c.allowHttp());
        assertEquals(100, c.batchSize());
        assertEquals("dARK", c.otherIdAgency());
        assertEquals(DarkConfig.Target.PID, c.target());
        assertEquals(20, c.maxAttempts());
        assertEquals(30, c.httpTimeoutSeconds());
        assertNull(c.publisher(), "no default: falls back to the root collection name");
    }

    @Test
    @JvmSetting(key = JvmSettings.DARK_ENABLED, value = "true")
    @JvmSetting(key = JvmSettings.DARK_MINTER_URL, value = "https://minter.test/")
    @JvmSetting(key = JvmSettings.DARK_AUTHORITY_ID, value = "auth-uuid")
    @JvmSetting(key = JvmSettings.DARK_NAAN, value = "99999")
    @JvmSetting(key = JvmSettings.DARK_KEY_DIRECTORY, value = " /dv/keys/dark ")
    @JvmSetting(key = JvmSettings.DARK_ALLOW_HTTP, value = "true")
    @JvmSetting(key = JvmSettings.DARK_BATCH_SIZE, value = "500")
    @JvmSetting(key = JvmSettings.DARK_TARGET, value = "landing")
    void readsSettings() {
        DarkConfig c = DarkConfig.fromSettings();
        assertTrue(c.enabled());
        assertEquals("https://minter.test/api/v1", c.minterUrl());
        assertEquals("auth-uuid", c.authorityId());
        assertEquals("99999", c.naan());
        assertEquals(Path.of("/dv/keys/dark"), c.keyPath());
        assertTrue(c.allowHttp());
        assertEquals(DarkConfig.MINTER_MAX_BATCH, c.batchSize(), "batch size is capped to the Minter maximum");
        assertEquals(DarkConfig.Target.LANDING, c.target());
    }

    @Test
    void usableOnlyWithConnectionSettingsAndAKey() throws Exception {
        Path keys = keyDirectoryWithKey();
        assertTrue(config(true, "https://minter.test", false, keys).isUsable());
        assertNull(config(true, "https://minter.test", false, keys).inactiveReason());

        assertFalse(config(false, "https://minter.test", false, keys).isUsable(), "not enabled");

        DarkConfig noDirectory = config(true, "https://minter.test", false, null);
        assertFalse(noDirectory.isUsable());
        assertTrue(noDirectory.inactiveReason().contains("key-directory is missing"), noDirectory.inactiveReason());

        DarkConfig noKey = config(true, "https://minter.test", false, root.resolve("empty"));
        assertFalse(noKey.isUsable());
        assertTrue(noKey.inactiveReason().contains("POST /api/admin/dark/key"), noKey.inactiveReason());

        DarkConfig noUrl = config(true, null, false, keys);
        assertFalse(noUrl.isUsable());
        assertTrue(noUrl.inactiveReason().contains("minter-url, authority-id or naan is missing"), noUrl.inactiveReason());
    }

    @Test
    void httpIsOnlyAcceptedForLocalhostOrWhenAllowedForDevelopment() throws Exception {
        Path keys = keyDirectoryWithKey();
        assertNull(config(true, "https://minter.test", false, keys).connectionProblem());
        assertNull(config(true, "http://localhost:18080", false, keys).connectionProblem());
        assertNull(config(true, "http://127.0.0.1:18080", false, keys).connectionProblem());
        assertNull(config(true, "http://[::1]:18080", false, keys).connectionProblem());
        assertNull(config(true, "http://dark-gateway:8080", true, keys).connectionProblem(), "allow-http");

        DarkConfig remoteHttp = config(true, "http://sandbox.dark-pid.net", false, keys);
        assertNotNull(remoteHttp.connectionProblem());
        assertFalse(remoteHttp.isUsable());
        assertTrue(remoteHttp.inactiveReason().contains("must be HTTPS"), remoteHttp.inactiveReason());
        assertNotNull(config(true, "ftp://minter.test", true, keys).connectionProblem(), "allow-http is only about http");
    }

    @Test
    void aMinterUrlWithCredentialsOrQueryIsRefused() throws Exception {
        Path keys = keyDirectoryWithKey();
        assertNotNull(config(true, "https://user:secret@minter.test", false, keys).connectionProblem());
        assertNotNull(config(true, "https://minter.test/api/v1?x=1", false, keys).connectionProblem());
        assertNotNull(config(true, "https:///api/v1", false, keys).connectionProblem());
        assertNotNull(config(true, "https://minter test", false, keys).connectionProblem());
    }

    @Test
    void targetValues() {
        assertEquals(DarkConfig.Target.PID, DarkConfig.Target.parse("pid"));
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
    void publisherFallsBackToRootCollection() {
        DarkConfig unset = new DarkConfig(true, "https://m", "a", "1", null, false, 10, null, null, 5, 5, "  ");
        assertNull(unset.publisher(), "blank means unset");
        assertEquals("Root", unset.resolvePublisher("Root"));
        assertNull(unset.resolvePublisher(" "));

        DarkConfig set = new DarkConfig(true, "https://m", "a", "1", null, false, 10, null, null, 5, 5, " My Repository ");
        assertEquals("My Repository", set.resolvePublisher("Root"));
    }
}
