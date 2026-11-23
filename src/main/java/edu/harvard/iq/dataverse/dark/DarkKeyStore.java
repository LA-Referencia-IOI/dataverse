package edu.harvard.iq.dataverse.dark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Custody of the Ed25519 key pair that signs the requests to the Minter
 * ({@link DarkSigner}). The pair lives in one directory
 * ({@code dataverse.dark.key-directory}), readable only by the user that runs Dataverse:
 *
 * <pre>
 * key-directory/     rwx------
 *   private.pk8      rw-------  PKCS#8 (DER)
 *   public.der       rw-------  SubjectPublicKeyInfo (DER)
 * </pre>
 *
 * The private key is generated here and never leaves this class except as a
 * {@link PrivateKey} for signing; only the public key is ever shown. Every load checks
 * that the files are regular files, the owner and permissions, and fails otherwise.
 */
final class DarkKeyStore {

    private static final String PRIVATE_FILE = "private.pk8";
    private static final String PUBLIC_FILE = "public.der";
    private static final Set<String> KEY_FILES = Set.of(PRIVATE_FILE, PUBLIC_FILE);
    private static final String ALGORITHM = "Ed25519";

    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the 32 raw key bytes follow. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int RAW_KEY_LENGTH = 32;

    /**
     * A loaded key pair.
     *
     * @param publicKey what the Minter administrator registers: standard base64 of the 32 raw public key bytes
     * @param keyId     lowercase hex SHA-256 of those 32 bytes ({@code X-DARK-Key-Id})
     */
    record Credential(PrivateKey privateKey, String publicKey, String keyId) {
    }

    private DarkKeyStore() {
    }

    /** @return true if the directory holds a key pair; a cheap check that does not validate it. */
    static boolean exists(Path directory) {
        return Files.isRegularFile(directory.resolve(PRIVATE_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Generates a key pair in {@code directory}. The pair is written to a temporary sibling
     * directory and moved into place, so a failure never leaves half a credential.
     *
     * @param replace true to discard an existing pair; without it an existing pair is never overwritten
     * @throws DarkException 409 if a pair exists and {@code replace} is false
     */
    static Credential generate(Path directory, boolean replace) throws DarkException {
        Path target = directory.toAbsolutePath().normalize();
        boolean present = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (present && !replace) {
            throw new DarkException("A key already exists in " + target + "; it is never overwritten without replace=true", 409);
        }
        Path staging = target.resolveSibling("." + target.getFileName() + "-" + UUID.randomUUID());
        try {
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            Files.createDirectories(target.getParent(), attribute(OWNER_DIRECTORY));
            Files.createDirectory(staging, attribute(OWNER_DIRECTORY));
            write(staging.resolve(PRIVATE_FILE), pair.getPrivate().getEncoded());
            write(staging.resolve(PUBLIC_FILE), pair.getPublic().getEncoded());
            if (present) {
                delete(target);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | GeneralSecurityException | UnsupportedOperationException e) {
            throw new DarkException("Could not generate the key in " + target + ": " + reason(e), e);
        } finally {
            deleteQuietly(staging);
        }
        return load(target);
    }

    /** @throws DarkException if there is no key, or it does not pass the custody checks */
    static Credential load(Path directory) throws DarkException {
        Path target = directory.toAbsolutePath().normalize();
        try {
            requireOwnerOnly(target, true);
            byte[] privateBytes = read(target.resolve(PRIVATE_FILE));
            byte[] publicBytes = read(target.resolve(PUBLIC_FILE));
            if (publicBytes.length != SPKI_PREFIX.length + RAW_KEY_LENGTH
                    || !Arrays.equals(publicBytes, 0, SPKI_PREFIX.length, SPKI_PREFIX, 0, SPKI_PREFIX.length)) {
                throw new IOException(PUBLIC_FILE + " is not an Ed25519 public key");
            }
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            if (!isPair(privateKey, factory.generatePublic(new X509EncodedKeySpec(publicBytes)))) {
                throw new IOException(PRIVATE_FILE + " and " + PUBLIC_FILE + " do not belong together");
            }
            byte[] raw = Arrays.copyOfRange(publicBytes, SPKI_PREFIX.length, publicBytes.length);
            return new Credential(privateKey, Base64.getEncoder().encodeToString(raw), DarkSigner.sha256Hex(raw));
        } catch (IOException | GeneralSecurityException | UnsupportedOperationException e) {
            // Without the cause: a parser message must not carry key material into a log or last_error.
            throw new DarkException("The key in " + target + " cannot be used: " + reason(e));
        }
    }

    private static void write(Path file, byte[] content) throws IOException {
        Files.createFile(file, attribute(OWNER_FILE));
        Files.write(file, content);
    }

    private static byte[] read(Path file) throws IOException {
        requireOwnerOnly(file, false);
        return Files.readAllBytes(file);
    }

    /** A regular file or directory, not a symbolic link, with no permission for group or others. */
    private static void requireOwnerOnly(Path path, boolean directory) throws IOException {
        boolean expectedType = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!expectedType) {
            throw new IOException(path.getFileName() + " is missing or is not a regular " + (directory ? "directory" : "file"));
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!(directory ? OWNER_DIRECTORY : OWNER_FILE).containsAll(permissions)) {
            throw new IOException(path.getFileName() + " is accessible to other users ("
                    + PosixFilePermissions.toString(permissions) + ")");
        }
    }

    /** @return true if the two keys are halves of the same pair: a signature of one verifies with the other. */
    private static boolean isPair(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        byte[] challenge = "dataverse-dark-key-check".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(privateKey);
        signer.update(challenge);
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        return verifier.verify(signer.sign());
    }

    /** Deletes a key directory; refuses, before deleting anything, one that holds other files. */
    private static void delete(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(directory.getFileName() + " is not a directory");
        }
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.anyMatch(entry -> !KEY_FILES.contains(entry.getFileName().toString()))) {
                throw new IOException(directory.getFileName() + " holds files that are not a dARK key");
            }
        }
        for (String file : KEY_FILES) {
            Files.deleteIfExists(directory.resolve(file));
        }
        Files.delete(directory);
    }

    private static void deleteQuietly(Path staging) {
        try {
            if (Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                delete(staging);
            }
        } catch (IOException leftover) {
            // An empty or partial staging directory is never used for signing.
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> attribute(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    private static String reason(Exception e) {
        if (e instanceof UnsupportedOperationException) {
            return "the file system has no POSIX permissions";
        }
        return e instanceof GeneralSecurityException ? "invalid key file" : e.getMessage();
    }
}
