package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.util.json.JsonUtil;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetAuthor;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.TermsOfUseAndAccess;
import edu.harvard.iq.dataverse.export.openaire.OpenAireExportUtil;
import edu.harvard.iq.dataverse.license.License;
import edu.harvard.iq.dataverse.util.json.JsonPrinter;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Builds the metadata sent to the Minter on {@code PUT /arks/{ark}}:
 * <ul>
 * <li>Level 1 ({@code minimal_metadata}): small JSON record, see {@link Level1}.</li>
 * <li>Level 2 ({@code original_metadata}): the {@code oai_datacite} (OpenAIRE) XML of the
 * version, generated on the fly (not read from the export cache, which may be
 * regenerating right after publication).</li>
 * </ul>
 */
public final class DarkMetadataBuilder {

    public static final String LEVEL2_SCHEMA = "oai_datacite";
    public static final String LEVEL2_MEDIA_TYPE = "application/xml";
    public static final String RESOURCE_TYPE = "Dataset";

    private DarkMetadataBuilder() {
    }

    /** Values of the Level 1 record, extracted from a {@link DatasetVersion}. */
    public record Level1(
            String ark,
            String title,
            List<String> authors,
            String date,
            String publisher,
            String language,
            String abstractText,
            List<String> subjects,
            String pidSchema,
            String pidValue,
            String landingUrl,
            String rights) {

        public Level1 {
            authors = authors == null ? List.of() : List.copyOf(authors);
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
        }

        /** @return the 4-digit year of {@link #date}, or null. */
        public String year() {
            return date != null && date.length() >= 4 ? date.substring(0, 4) : null;
        }
    }

    /**
     * Extracts Level 1 values from a published (or being-published) version.
     *
     * @param ark        compact ARK
     * @param publisher  see {@link DarkConfig#resolvePublisher}
     * @param landingUrl URL of the dataset page
     */
    public static Level1 fromVersion(DatasetVersion version, String ark, String publisher, String landingUrl) {
        Dataset dataset = version.getDataset();

        List<String> authors = new ArrayList<>();
        for (DatasetAuthor a : version.getDatasetAuthors()) {
            if (a.getName() != null) {
                String name = a.getName().getDisplayValue();
                if (name != null && !name.isBlank()) {
                    authors.add(name.trim());
                }
            }
        }

        Set<String> subjects = new LinkedHashSet<>();
        addAllNonBlank(subjects, version.getDatasetSubjects());
        addAllNonBlank(subjects, version.getKeywords());

        List<String> languages = version.getLanguages();
        String language = languages == null || languages.isEmpty() ? null : languages.get(0);

        String description = version.getDescriptionPlainText();

        return new Level1(
                ark,
                blankToNull(version.getTitle()),
                authors,
                publicationDate(version),
                blankToNull(publisher),
                blankToNull(language),
                blankToNull(description),
                new ArrayList<>(subjects),
                dataset.getGlobalId() == null ? null : DarkIds.pidSchema(dataset.getGlobalId().getProtocol()),
                DarkIds.pidValue(DarkIds.clientItemId(dataset)),
                landingUrl,
                rights(version));
    }

    /** {@code rights} value for datasets with Custom Dataset Terms instead of a license. */
    public static final String CUSTOM_TERMS = "Custom Dataset Terms";

    /**
     * Rights of the version: the license name (e.g. {@code CC0 1.0}); {@link #CUSTOM_TERMS}
     * when the version uses custom terms of use; null if neither is set.
     */
    static String rights(DatasetVersion version) {
        TermsOfUseAndAccess terms = version.getTermsOfUseAndAccess();
        if (terms == null) {
            return null;
        }
        License license = terms.getLicense();
        if (license != null) {
            return blankToNull(license.getName());
        }
        return blankToNull(terms.getTermsOfUse()) != null ? CUSTOM_TERMS : null;
    }

    /** Level 1 JSON ({@code minimal_metadata}); null/empty values are omitted. */
    public static JsonObject level1Json(Level1 l1) {
        JsonObjectBuilder b = JsonUtil.createObjectBuilder();
        b.add("ark", l1.ark());
        addIfPresent(b, "title", l1.title());
        if (!l1.authors().isEmpty()) {
            // Sent twice for compatibility, as DSpace does.
            b.add("authors", array(l1.authors()));
            b.add("creator", array(l1.authors()));
        }
        addIfPresent(b, "year", l1.year());
        addIfPresent(b, "date", l1.date());
        addIfPresent(b, "publisher", l1.publisher());
        b.add("resource_type", RESOURCE_TYPE);
        addIfPresent(b, "language", l1.language());
        addIfPresent(b, "abstract", l1.abstractText());
        addIfPresent(b, "rights", l1.rights());
        if (!l1.subjects().isEmpty()) {
            b.add("subjects", array(l1.subjects()));
        }
        if (l1.pidSchema() != null && l1.pidValue() != null) {
            b.add("alternate_identifiers", JsonUtil.createArrayBuilder()
                    .add(JsonUtil.createObjectBuilder().add("schema", l1.pidSchema()).add("value", l1.pidValue())));
        }
        if (l1.landingUrl() != null) {
            b.add("alternate_urls", JsonUtil.createArrayBuilder().add(l1.landingUrl()));
        }
        b.add("original_metadata", JsonUtil.createObjectBuilder()
                .add("schema", LEVEL2_SCHEMA)
                .add("media_type", LEVEL2_MEDIA_TYPE));
        return b.build();
    }

    /** Level 2: {@code oai_datacite} XML of the version, generated now. */
    public static String level2Xml(DatasetVersion version) throws DarkException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            OpenAireExportUtil.datasetJson2openaire(JsonPrinter.datasetAsJsonForDTO(version).build(), out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new DarkException("Could not build " + LEVEL2_SCHEMA + " metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Resolution target of the ARK. By default ({@link DarkConfig.Target#PID}) the resolver
     * URL of the dataset's own PID ({@link DarkIds#resolverUrl}), so the ARK does not depend
     * on the installation's hostname; with {@code landing}, the dataset page. Falls back to
     * the landing page if the PID has no resolver URL.
     */
    public static String target(DarkConfig.Target mode, String pidResolverUrl, String landingUrl) {
        if (mode == DarkConfig.Target.LANDING || pidResolverUrl == null || pidResolverUrl.isBlank()) {
            return landingUrl;
        }
        return pidResolverUrl;
    }

    /** Landing page of a dataset: {@code <siteUrl>/dataset.xhtml?persistentId=<pid>}. */
    public static String landingUrl(String siteUrl, Dataset dataset) {
        if (siteUrl == null || dataset.getGlobalId() == null) {
            return null;
        }
        String base = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        return base + "/dataset.xhtml?persistentId=" + dataset.getGlobalId().asString();
    }

    /** Publication date of the version (yyyy-MM-dd, UTC), falling back to the dataset's. */
    static String publicationDate(DatasetVersion version) {
        Date d = version.getReleaseTime();
        if (d == null && version.getDataset() != null) {
            d = version.getDataset().getPublicationDate();
        }
        if (d == null) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(d);
    }

    private static JsonArrayBuilder array(List<String> values) {
        JsonArrayBuilder a = JsonUtil.createArrayBuilder();
        values.forEach(a::add);
        return a;
    }

    private static void addIfPresent(JsonObjectBuilder b, String key, String value) {
        if (value != null && !value.isBlank()) {
            b.add(key, value);
        }
    }

    private static void addAllNonBlank(Set<String> target, List<String> values) {
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(target::add);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
