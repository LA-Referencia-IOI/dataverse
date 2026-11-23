package edu.harvard.iq.dataverse.dark;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DarkSignerTest {

    // Interoperability vector published in the Minter client guide (section 25).
    // Its private seed is public: demonstration only, never a key to register.
    private static final String SEED_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final String KEY_ID = "56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c";
    private static final String AUTHORITY = "11111111-1111-4111-8111-111111111111";
    private static final byte[] BODY = ("{\"authority_id\":\"" + AUTHORITY + "\",\"naan\":\"12345\"}").getBytes(StandardCharsets.UTF_8);
    private static final long TIMESTAMP = 1700000000L;
    private static final UUID NONCE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static PrivateKey vectorKey() throws Exception {
        byte[] pkcs8 = HexFormat.of().parseHex("302e020100300506032b657004220420" + SEED_HEX);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static Map<String, String> sign(String method, String url, byte[] body) throws Exception {
        return DarkSigner.headers(vectorKey(), KEY_ID, method, URI.create(url), AUTHORITY, body, TIMESTAMP, NONCE);
    }

    @Test
    void reproducesThePublishedVector() throws Exception {
        Map<String, String> headers = sign("POST", "https://minter.example.org/api/v1/arks", BODY);

        assertEquals(List.of("X-DARK-Authority-Id", "X-DARK-Key-Id", "X-DARK-Timestamp", "X-DARK-Nonce",
                "X-DARK-Content-SHA256", "X-DARK-Signature"), List.copyOf(headers.keySet()));
        assertEquals(AUTHORITY, headers.get("X-DARK-Authority-Id"));
        assertEquals(KEY_ID, headers.get("X-DARK-Key-Id"));
        assertEquals("1700000000", headers.get("X-DARK-Timestamp"));
        assertEquals("22222222-2222-4222-8222-222222222222", headers.get("X-DARK-Nonce"));
        assertEquals("b4625ce6bb01deb70ccc17b509765e81040ba2307c8921fecce86ca53e2f9cc0", headers.get("X-DARK-Content-SHA256"));
        assertEquals("6sEEbLK0VBs69+R4WrWzK4SXickUEYqAtreVoigl1Clq4g2SgAgOGPKBn0vLS/tqm8MpHyPpCILspzRF9hlIAQ==",
                headers.get("X-DARK-Signature"));
    }

    @Test
    void theMethodIsSignedInUpperCase() throws Exception {
        assertEquals(sign("POST", "https://minter.example.org/api/v1/arks", BODY).get("X-DARK-Signature"),
                sign("post", "https://minter.example.org/api/v1/arks", BODY).get("X-DARK-Signature"));
    }

    @Test
    void hostAndSchemeAreNotSigned() throws Exception {
        assertEquals(sign("POST", "https://minter.example.org/api/v1/arks", BODY).get("X-DARK-Signature"),
                sign("POST", "http://localhost:18080/api/v1/arks", BODY).get("X-DARK-Signature"));
    }

    @Test
    void pathQueryAndBodyAreSigned() throws Exception {
        String signature = sign("POST", "https://minter.example.org/api/v1/arks", BODY).get("X-DARK-Signature");
        assertNotEquals(signature, sign("POST", "https://minter.example.org/api/v1/arks/", BODY).get("X-DARK-Signature"));
        assertNotEquals(signature, sign("POST", "https://minter.example.org/api/v1/arks?a=1", BODY).get("X-DARK-Signature"));
        assertNotEquals(signature, sign("PUT", "https://minter.example.org/api/v1/arks", BODY).get("X-DARK-Signature"));
        assertNotEquals(signature, sign("POST", "https://minter.example.org/api/v1/arks", new byte[0]).get("X-DARK-Signature"));
    }

    @Test
    void theRawPathIsSignedAsSent() throws Exception {
        // An ARK goes in the path unencoded; an escaped query must not be decoded or reordered.
        Map<String, String> ark = sign("PUT", "https://minter.example.org/api/v1/arks/ark:12345/2s00005mvq5", BODY);
        Map<String, String> encoded = sign("PUT", "https://minter.example.org/api/v1/arks/ark%3A12345/2s00005mvq5", BODY);
        assertNotEquals(ark.get("X-DARK-Signature"), encoded.get("X-DARK-Signature"));
        assertNotEquals(sign("GET", "https://minter.example.org/x?label=hello%20world", BODY).get("X-DARK-Signature"),
                sign("GET", "https://minter.example.org/x?label=hello+world", BODY).get("X-DARK-Signature"));
    }

    @Test
    void anEmptyBodyHasTheDigestOfZeroBytes() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                sign("DELETE", "https://minter.example.org/api/v1/arks/ark:12345/x", new byte[0]).get("X-DARK-Content-SHA256"));
    }

    @Test
    void aFieldThatWouldBreakTheCanonicalMessageIsRefused() throws Exception {
        PrivateKey key = vectorKey();
        URI uri = URI.create("https://minter.example.org/api/v1/arks");
        for (String authority : List.of("", " " + AUTHORITY, AUTHORITY + "\n", "a\rb")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DarkSigner.headers(key, KEY_ID, "POST", uri, authority, BODY, TIMESTAMP, NONCE), "'" + authority + "'");
        }
    }
}
