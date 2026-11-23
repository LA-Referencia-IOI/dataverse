package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.dark.DarkKeyStore.Credential;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DarkKeyStoreTest {

    @TempDir
    Path root;

    private Path directory;

    @BeforeEach
    void setUp() {
        directory = root.resolve("keys").resolve("dark");
    }

    private static String permissions(Path path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void generatesAPairOnlyItsOwnerCanRead() throws Exception {
        assertFalse(DarkKeyStore.exists(directory));

        Credential key = DarkKeyStore.generate(directory, false);

        assertTrue(DarkKeyStore.exists(directory));
        assertEquals("rwx------", permissions(directory));
        assertEquals("rwx------", permissions(directory.getParent()), "created parent");
        assertEquals("rw-------", permissions(directory.resolve("private.pk8")));
        assertEquals("rw-------", permissions(directory.resolve("public.der")));
        assertEquals(32, Base64.getDecoder().decode(key.publicKey()).length, "raw Ed25519 public key");
        assertTrue(key.keyId().matches("[0-9a-f]{64}"), key.keyId());
        assertEquals(DarkSigner.sha256Hex(Base64.getDecoder().decode(key.publicKey())), key.keyId());
        try (var files = Files.list(root.resolve("keys"))) {
            assertEquals(1, files.count(), "no staging directory left behind");
        }
    }

    @Test
    void theStoredPairSignsWhatItsPublicKeyVerifies() throws Exception {
        Credential key = DarkKeyStore.generate(directory, false);
        URI uri = URI.create("https://minter.example.org/api/v1/arks/batch");
        byte[] body = "{}".getBytes();
        UUID nonce = UUID.randomUUID();

        Map<String, String> headers = DarkSigner.headers(key.privateKey(), key.keyId(), "POST", uri, "authority", body, 1791676800L, nonce);

        String message = String.join("\n", "DARK2", "POST", "/api/v1/arks/batch", "authority", key.keyId(),
                "1791676800", nonce.toString(), DarkSigner.sha256Hex(body));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Files.readAllBytes(directory.resolve("public.der")))));
        verifier.update(message.getBytes());
        assertTrue(verifier.verify(Base64.getDecoder().decode(headers.get("X-DARK-Signature"))));
    }

    @Test
    void loadingReturnsTheSameKey() throws Exception {
        Credential generated = DarkKeyStore.generate(directory, false);
        Credential loaded = DarkKeyStore.load(directory);
        assertEquals(generated.publicKey(), loaded.publicKey());
        assertEquals(generated.keyId(), loaded.keyId());
    }

    @Test
    void anExistingKeyIsNeverOverwrittenByDefault() throws Exception {
        Credential first = DarkKeyStore.generate(directory, false);

        DarkException refused = assertThrows(DarkException.class, () -> DarkKeyStore.generate(directory, false));

        assertEquals(409, refused.getHttpStatus());
        assertEquals(first.keyId(), DarkKeyStore.load(directory).keyId());
    }

    @Test
    void replaceDiscardsTheOldKey() throws Exception {
        Credential first = DarkKeyStore.generate(directory, false);

        Credential second = DarkKeyStore.generate(directory, true);

        assertNotEquals(first.keyId(), second.keyId());
        assertEquals(second.keyId(), DarkKeyStore.load(directory).keyId());
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count(), "only the new pair");
        }
    }

    @Test
    void replaceRefusesADirectoryThatHoldsOtherFiles() throws Exception {
        Credential first = DarkKeyStore.generate(directory, false);
        Files.writeString(directory.resolve("notes.txt"), "not a key");

        assertThrows(DarkException.class, () -> DarkKeyStore.generate(directory, true));

        assertTrue(Files.exists(directory.resolve("notes.txt")));
        assertEquals(first.keyId(), DarkKeyStore.load(directory).keyId(), "the old key is intact");
    }

    @Test
    void aMissingKeyCannotBeLoaded() {
        DarkException e = assertThrows(DarkException.class, () -> DarkKeyStore.load(directory));
        assertTrue(e.getMessage().contains("cannot be used"), e.getMessage());
        assertFalse(e.isRejection());
    }

    @Test
    void aKeyOtherUsersCanReadIsRefused() throws Exception {
        DarkKeyStore.generate(directory, false);
        Files.setPosixFilePermissions(directory.resolve("private.pk8"), PosixFilePermissions.fromString("rw-r-----"));

        DarkException e = assertThrows(DarkException.class, () -> DarkKeyStore.load(directory));

        assertTrue(e.getMessage().contains("accessible to other users"), e.getMessage());
    }

    @Test
    void aDirectoryOtherUsersCanEnterIsRefused() throws Exception {
        DarkKeyStore.generate(directory, false);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThrows(DarkException.class, () -> DarkKeyStore.load(directory));
    }

    @Test
    void aSymbolicLinkIsRefused() throws Exception {
        DarkKeyStore.generate(directory, false);
        Path elsewhere = root.resolve("elsewhere.pk8");
        Files.move(directory.resolve("private.pk8"), elsewhere);
        Files.createSymbolicLink(directory.resolve("private.pk8"), elsewhere);

        assertFalse(DarkKeyStore.exists(directory));
        assertThrows(DarkException.class, () -> DarkKeyStore.load(directory));

        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, directory);
        assertThrows(DarkException.class, () -> DarkKeyStore.load(linked));
    }

    @Test
    void halvesOfDifferentPairsAreRefused() throws Exception {
        DarkKeyStore.generate(directory, false);
        Path other = root.resolve("keys").resolve("other");
        DarkKeyStore.generate(other, false);
        Files.copy(other.resolve("public.der"), directory.resolve("public.der"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        DarkException e = assertThrows(DarkException.class, () -> DarkKeyStore.load(directory));

        assertTrue(e.getMessage().contains("do not belong together"), e.getMessage());
    }

    @Test
    void aCorruptKeyIsRefusedWithoutEchoingItsContent() throws Exception {
        DarkKeyStore.generate(directory, false);
        Files.write(directory.resolve("private.pk8"), "secret-material".getBytes());

        DarkException e = assertThrows(DarkException.class, () -> DarkKeyStore.load(directory));

        assertTrue(e.getMessage().endsWith("invalid key file"), e.getMessage());
        assertFalse(e.getMessage().contains("secret-material"));
        assertEquals(null, e.getCause());
    }
}
