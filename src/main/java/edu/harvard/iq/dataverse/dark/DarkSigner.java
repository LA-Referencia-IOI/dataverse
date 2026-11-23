package edu.harvard.iq.dataverse.dark;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * DARK2 request signature: the authentication the Minter requires on every request that
 * modifies data. Each request carries an Ed25519 signature of
 * eight lines:
 *
 * <pre>
 * DARK2
 * METHOD
 * raw path, plus "?" and the raw query when there is one
 * authority UUID
 * key id (see {@link DarkKeyStore.Credential#keyId})
 * Unix seconds
 * nonce (a new UUID per attempt)
 * lowercase hex SHA-256 of the exact body bytes
 * </pre>
 *
 * Pure function: the caller supplies the time and the nonce.
 */
final class DarkSigner {

    static final String AUTHORITY_HEADER = "X-DARK-Authority-Id";
    static final String ERROR_CODE_HEADER = "X-DARK-Error-Code";

    private static final String PROTOCOL = "DARK2";

    private DarkSigner() {
    }

    /**
     * @param uri  the URL exactly as it will be sent
     * @param body the exact bytes that will be sent; empty for a request without a body
     * @return the six {@code X-DARK-*} headers of the request
     */
    static Map<String, String> headers(PrivateKey key, String keyId, String method, URI uri, String authorityId,
            byte[] body, long epochSeconds, UUID nonce) throws GeneralSecurityException {
        String pathAndQuery = uri.getRawQuery() == null || uri.getRawQuery().isEmpty()
                ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
        String timestamp = Long.toString(epochSeconds);
        String digest = sha256Hex(body);
        List<String> lines = List.of(PROTOCOL, method.toUpperCase(Locale.ROOT), pathAndQuery, authorityId, keyId,
                timestamp, nonce.toString(), digest);
        for (String line : lines) {
            if (line.isBlank() || !line.equals(line.strip()) || line.contains("\n") || line.contains("\r")) {
                throw new IllegalArgumentException("Invalid DARK2 canonical field");
            }
        }
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AUTHORITY_HEADER, authorityId);
        headers.put("X-DARK-Key-Id", keyId);
        headers.put("X-DARK-Timestamp", timestamp);
        headers.put("X-DARK-Nonce", nonce.toString());
        headers.put("X-DARK-Content-SHA256", digest);
        headers.put("X-DARK-Signature", Base64.getEncoder().encodeToString(signature.sign()));
        return headers;
    }

    static String sha256Hex(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
