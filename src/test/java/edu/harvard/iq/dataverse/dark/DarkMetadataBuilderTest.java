package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetField;
import edu.harvard.iq.dataverse.DatasetFieldCompoundValue;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetFieldValue;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.GlobalId;
import edu.harvard.iq.dataverse.TermsOfUseAndAccess;
import edu.harvard.iq.dataverse.license.License;
import edu.harvard.iq.dataverse.pidproviders.doi.AbstractDOIProvider;
import jakarta.json.JsonObject;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DarkMetadataBuilderTest {

    private static final String ARK = "ark:12345/abc123";

    // ---- Level 1 JSON ----

    @Test
    void level1JsonHasAllFields() {
        DarkMetadataBuilder.Level1 l1 = new DarkMetadataBuilder.Level1(ARK, "A title",
                List.of("Doe, Jane", "Roe, Richard"), "2026-09-28", "My Repository", "English",
                "An abstract", List.of("Social Sciences", "poverty"), "doi", "10.5072/fk2/abc123",
                "https://dv.example.org/dataset.xhtml?persistentId=doi:10.5072/FK2/ABC123", "CC0 1.0");

        JsonObject j = DarkMetadataBuilder.level1Json(l1);

        assertEquals(ARK, j.getString("ark"));
        assertEquals("CC0 1.0", j.getString("rights"));
        assertEquals("A title", j.getString("title"));
        assertEquals(2, j.getJsonArray("authors").size());
        assertEquals(j.getJsonArray("authors"), j.getJsonArray("creator"));
        assertEquals("2026", j.getString("year"));
        assertEquals("2026-09-28", j.getString("date"));
        assertEquals("My Repository", j.getString("publisher"));
        assertEquals("Dataset", j.getString("resource_type"));
        assertEquals("English", j.getString("language"));
        assertEquals("An abstract", j.getString("abstract"));
        assertEquals("poverty", j.getJsonArray("subjects").getString(1));
        JsonObject altId = j.getJsonArray("alternate_identifiers").getJsonObject(0);
        assertEquals("doi", altId.getString("schema"));
        assertEquals("10.5072/fk2/abc123", altId.getString("value"));
        assertEquals(l1.landingUrl(), j.getJsonArray("alternate_urls").getString(0));
        assertEquals("oai_datacite", j.getJsonObject("original_metadata").getString("schema"));
        assertEquals("application/xml", j.getJsonObject("original_metadata").getString("media_type"));
    }

    @Test
    void level1JsonOmitsEmptyValues() {
        DarkMetadataBuilder.Level1 l1 = new DarkMetadataBuilder.Level1(ARK, null, null, null, " ", null,
                null, null, null, null, null, null);

        JsonObject j = DarkMetadataBuilder.level1Json(l1);

        assertEquals(ARK, j.getString("ark"));
        assertEquals("Dataset", j.getString("resource_type"));
        for (String absent : List.of("title", "authors", "creator", "year", "date", "publisher", "language",
                "abstract", "rights", "subjects", "alternate_identifiers", "alternate_urls")) {
            assertFalse(j.containsKey(absent), absent + " should be omitted");
        }
    }

    @Test
    void level1JsonAlternateIdentifierFollowsThePidType() {
        DarkMetadataBuilder.Level1 handle = new DarkMetadataBuilder.Level1(ARK, "T", null, null, null, null,
                null, null, "handle", "20.500.12345/abc", null, null);
        JsonObject altId = DarkMetadataBuilder.level1Json(handle).getJsonArray("alternate_identifiers").getJsonObject(0);
        assertEquals("handle", altId.getString("schema"));
        assertEquals("20.500.12345/abc", altId.getString("value"));

        DarkMetadataBuilder.Level1 perma = new DarkMetadataBuilder.Level1(ARK, "T", null, null, null, null,
                null, null, "permalink", "DVaBc123", null, null);
        altId = DarkMetadataBuilder.level1Json(perma).getJsonArray("alternate_identifiers").getJsonObject(0);
        assertEquals("permalink", altId.getString("schema"));
        assertEquals("DVaBc123", altId.getString("value"));
    }

    // ---- rights ----

    @Test
    void rightsIsTheLicenseName() {
        DatasetVersion v = new DatasetVersion();
        TermsOfUseAndAccess terms = new TermsOfUseAndAccess();
        terms.setLicense(new License("CC0 1.0", "Public domain", URI.create("http://creativecommons.org/publicdomain/zero/1.0"),
                null, true, 0L));
        v.setTermsOfUseAndAccess(terms);
        assertEquals("CC0 1.0", DarkMetadataBuilder.rights(v));
    }

    @Test
    void rightsForCustomTermsOrNone() {
        DatasetVersion v = new DatasetVersion();
        TermsOfUseAndAccess terms = new TermsOfUseAndAccess();
        terms.setTermsOfUse("Only for research use.");
        v.setTermsOfUseAndAccess(terms);
        assertEquals(DarkMetadataBuilder.CUSTOM_TERMS, DarkMetadataBuilder.rights(v));

        v.setTermsOfUseAndAccess(new TermsOfUseAndAccess());
        assertNull(DarkMetadataBuilder.rights(v));
        v.setTermsOfUseAndAccess(null);
        assertNull(DarkMetadataBuilder.rights(v));
    }

    // ---- extraction from DatasetVersion ----

    @Test
    void fromVersionExtractsCitationFields() {
        Dataset ds = new Dataset();
        ds.setGlobalId(new GlobalId(AbstractDOIProvider.DOI_PROTOCOL, "10.5072", "FK2/ABC123", "/",
                AbstractDOIProvider.DOI_RESOLVER_URL, null));
        DatasetVersion v = ds.getLatestVersion();
        v.setVersionState(DatasetVersion.VersionState.RELEASED);
        v.setReleaseTime(Timestamp.from(Instant.parse("2026-09-28T10:15:30Z")));

        List<DatasetField> fields = new ArrayList<>();
        fields.add(primitive(DatasetFieldConstant.title, "A title"));
        fields.add(compound(DatasetFieldConstant.author, DatasetFieldConstant.authorName, "Doe, Jane", "Roe, Richard"));
        fields.add(primitive(DatasetFieldConstant.subject, "Social Sciences"));
        fields.add(compound(DatasetFieldConstant.keyword, DatasetFieldConstant.keywordValue, "poverty", "Social Sciences"));
        v.setDatasetFields(fields);

        String landing = DarkMetadataBuilder.landingUrl("https://dv.example.org/", ds);
        DarkMetadataBuilder.Level1 l1 = DarkMetadataBuilder.fromVersion(v, ARK, "My Repository", landing);

        assertEquals("A title", l1.title());
        assertEquals(List.of("Doe, Jane", "Roe, Richard"), l1.authors());
        assertEquals("2026-09-28", l1.date());
        assertEquals("2026", l1.year());
        assertEquals("My Repository", l1.publisher());
        assertEquals(List.of("Social Sciences", "poverty"), l1.subjects(), "subjects + keywords, deduplicated");
        assertEquals("doi", l1.pidSchema());
        assertEquals("10.5072/fk2/abc123", l1.pidValue());
        assertEquals("https://dv.example.org/dataset.xhtml?persistentId=doi:10.5072/FK2/ABC123", landing);
        assertNull(l1.language());
        assertNull(l1.abstractText());
    }

    // ---- target ----

    @Test
    void targetDefaultsToThePidResolver() {
        assertEquals("https://doi.org/10.5072/fk2/abc", DarkMetadataBuilder.target(DarkConfig.Target.PID,
                "https://doi.org/10.5072/fk2/abc", "https://dv/landing"));
        assertEquals("https://hdl.handle.net/20.500.12345/ABC", DarkMetadataBuilder.target(DarkConfig.Target.PID,
                "https://hdl.handle.net/20.500.12345/ABC", "https://dv/landing"));
    }

    @Test
    void targetLandingOrFallbackWithoutResolver() {
        assertEquals("https://dv/landing", DarkMetadataBuilder.target(DarkConfig.Target.LANDING,
                "https://doi.org/10.5072/fk2/abc", "https://dv/landing"));
        assertEquals("https://dv/landing", DarkMetadataBuilder.target(DarkConfig.Target.PID, null, "https://dv/landing"));
    }

    // ---- helpers ----

    private static DatasetField primitive(String typeName, String value) {
        DatasetField f = new DatasetField();
        f.setDatasetFieldType(new DatasetFieldType(typeName, DatasetFieldType.FieldType.TEXT, false));
        f.setDatasetFieldValues(Collections.singletonList(new DatasetFieldValue(f, value)));
        return f;
    }

    private static DatasetField compound(String parentName, String childName, String... values) {
        DatasetFieldType parentType = new DatasetFieldType(parentName, DatasetFieldType.FieldType.NONE, true);
        DatasetFieldType childType = new DatasetFieldType(childName, DatasetFieldType.FieldType.TEXT, false);
        childType.setParentDatasetFieldType(parentType);

        DatasetField parent = new DatasetField();
        parent.setDatasetFieldType(parentType);
        List<DatasetFieldCompoundValue> cvs = new ArrayList<>();
        for (String value : values) {
            DatasetFieldCompoundValue cv = new DatasetFieldCompoundValue();
            cv.setParentDatasetField(parent);
            DatasetField child = primitive(childName, value);
            child.setDatasetFieldType(childType);
            child.setParentDatasetFieldCompoundValue(cv);
            cv.setChildDatasetFields(List.of(child));
            cvs.add(cv);
        }
        parent.setDatasetFieldCompoundValues(cvs);
        return parent;
    }
}
