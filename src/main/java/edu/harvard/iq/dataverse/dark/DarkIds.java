package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.GlobalId;
import edu.harvard.iq.dataverse.pidproviders.doi.AbstractDOIProvider;
import edu.harvard.iq.dataverse.pidproviders.handle.HandlePidProvider;
import edu.harvard.iq.dataverse.pidproviders.perma.PermaLinkPidProvider;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalization helpers for ARKs and for the {@code client_item_id} sent to the Minter.
 * <p>
 * Every local dataset gets a dARK, whatever its PID type (DOI, Handle, PermaLink or a PID
 * provider plugin); only harvested datasets are excluded. The {@code client_item_id} is
 * the dataset's PID as a string ({@code protocol:authority<separator>identifier}):
 * <ul>
 * <li>DOI: {@code doi:} + lower case (DOIs are case-insensitive);</li>
 * <li>Handle: {@code hdl:} + lower case (Handles are case-insensitive for ASCII);</li>
 * <li>PermaLink and any other type: unchanged, since they may be case-sensitive and
 * lower-casing could make two different PIDs collide.</li>
 * </ul>
 * A PID longer than {@link #CLIENT_ITEM_ID_MAX_LENGTH} cannot be registered (Minter limit)
 * and is rejected rather than truncated, so that two PIDs never share a key.
 */
public final class DarkIds {

    /** Maximum length of {@code client_item_id} accepted by the Minter. */
    public static final int CLIENT_ITEM_ID_MAX_LENGTH = 100;

    private static final Pattern ARK_PATTERN = Pattern.compile("^(?:https?://[^/]+/)?ark:/?([^/]+)/(.+)$",
            Pattern.CASE_INSENSITIVE);

    private DarkIds() {
    }

    /**
     * Canonical compact form: {@code ark:/12345/x}, {@code ark:12345/x} or a resolver
     * URL ending in either are all turned into {@code ark:12345/x}.
     *
     * @return the compact ARK, or {@code null} if the value is not an ARK.
     */
    public static String normalizeArk(String ark) {
        if (ark == null) {
            return null;
        }
        Matcher m = ARK_PATTERN.matcher(ark.trim());
        if (!m.matches()) {
            return null;
        }
        return "ark:" + m.group(1) + "/" + m.group(2);
    }

    /**
     * Normalized DOI: {@code doi:} prefix plus lower case, limited to
     * {@link #CLIENT_ITEM_ID_MAX_LENGTH} without truncation. Accepts {@code doi:10.x/y},
     * {@code 10.x/y} and {@code https://doi.org/10.x/y}.
     *
     * @return the normalized id, or {@code null} if the value is not a DOI or is too long.
     */
    public static String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String d = doi.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("doi:")) {
            d = d.substring(4);
        } else if (d.startsWith("https://doi.org/") || d.startsWith("http://doi.org/")) {
            d = d.substring(d.indexOf("doi.org/") + 8);
        } else if (d.startsWith("https://dx.doi.org/") || d.startsWith("http://dx.doi.org/")) {
            d = d.substring(d.indexOf("doi.org/") + 8);
        }
        if (!d.startsWith("10.") || !d.contains("/")) {
            return null;
        }
        return withinLimit("doi:" + d);
    }

    /**
     * Normalized PID used as {@code client_item_id}, given its protocol and its string form
     * ({@code protocol:authority<separator>identifier}).
     *
     * @return the normalized id, or {@code null} if empty or longer than the Minter limit.
     */
    public static String normalizePid(String protocol, String pid) {
        if (protocol == null || pid == null || pid.isBlank()) {
            return null;
        }
        String p = protocol.trim().toLowerCase(Locale.ROOT);
        String value = pid.trim();
        if (AbstractDOIProvider.DOI_PROTOCOL.equals(p)) {
            return normalizeDoi(value);
        }
        if (HandlePidProvider.HDL_PROTOCOL.equals(p)) {
            return withinLimit(value.toLowerCase(Locale.ROOT));
        }
        // PermaLink and PID provider plugins: may be case-sensitive, keep as is.
        return withinLimit(value);
    }

    /**
     * @return the {@code client_item_id} for a dataset (its normalized PID), or {@code null}
     *         if the dataset has no PID yet or its PID exceeds the Minter limit.
     */
    public static String clientItemId(Dataset dataset) {
        GlobalId pid = dataset == null ? null : dataset.getGlobalId();
        if (pid == null) {
            return null;
        }
        return normalizePid(pid.getProtocol(), pid.asString());
    }

    /** @return the schema name sent in Level 1 {@code alternate_identifiers}. */
    public static String pidSchema(String protocol) {
        if (protocol == null) {
            return null;
        }
        String p = protocol.trim().toLowerCase(Locale.ROOT);
        if (AbstractDOIProvider.DOI_PROTOCOL.equals(p)) {
            return "doi";
        }
        if (HandlePidProvider.HDL_PROTOCOL.equals(p)) {
            return "handle";
        }
        if (PermaLinkPidProvider.PERMA_PROTOCOL.equals(p)) {
            return "permalink";
        }
        return p;
    }

    /**
     * @return the PID without its {@code protocol:} prefix, e.g. {@code 10.5072/fk2/abc} for
     *         {@code doi:10.5072/fk2/abc} or {@code 20.500.12345/abc} for {@code hdl:20.500.12345/abc}.
     */
    public static String pidValue(String clientItemId) {
        if (clientItemId == null) {
            return null;
        }
        int colon = clientItemId.indexOf(':');
        return colon >= 0 ? clientItemId.substring(colon + 1) : clientItemId;
    }

    /**
     * Resolver URL of the dataset's own PID, used as the default ARK target:
     * {@code https://doi.org/<doi>} (lower case, as before), {@code https://hdl.handle.net/<handle>},
     * or the PermaLink base URL + identifier.
     *
     * @return the URL, or {@code null} if the dataset has no PID.
     */
    public static String resolverUrl(Dataset dataset) {
        GlobalId pid = dataset == null ? null : dataset.getGlobalId();
        if (pid == null) {
            return null;
        }
        if (AbstractDOIProvider.DOI_PROTOCOL.equalsIgnoreCase(pid.getProtocol())) {
            String doi = normalizeDoi(pid.asString());
            if (doi != null) {
                return "https://doi.org/" + pidValue(doi);
            }
        }
        return pid.asURL();
    }

    private static String withinLimit(String clientItemId) {
        return clientItemId.length() > CLIENT_ITEM_ID_MAX_LENGTH ? null : clientItemId;
    }
}
