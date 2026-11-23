package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.DatasetField;
import edu.harvard.iq.dataverse.DatasetFieldCompoundValue;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetFieldValue;
import edu.harvard.iq.dataverse.DatasetVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DarkOtherIdTest {

    private static final String ARK = "ark:12345/abc";

    private DatasetFieldType otherIdType;
    private DatasetVersion version;

    @BeforeEach
    void setUp() {
        otherIdType = new DatasetFieldType(DatasetFieldConstant.otherId, DatasetFieldType.FieldType.NONE, true);
        DatasetFieldType agency = new DatasetFieldType(DatasetFieldConstant.otherIdAgency, DatasetFieldType.FieldType.TEXT, false);
        DatasetFieldType value = new DatasetFieldType(DatasetFieldConstant.otherIdValue, DatasetFieldType.FieldType.TEXT, false);
        agency.setParentDatasetFieldType(otherIdType);
        value.setParentDatasetFieldType(otherIdType);
        otherIdType.setChildDatasetFieldTypes(List.of(agency, value));
        version = new DatasetVersion();
        version.setDatasetFields(new ArrayList<>());
    }

    @Test
    void addsFieldWhenAbsent() {
        assertTrue(DarkOtherId.put(version, otherIdType, "dARK", "ark:/12345/abc"));
        assertEquals(ARK, DarkOtherId.find(version, "dARK"), "stored in compact form");
        assertEquals(1, version.getDatasetFields().size());
    }

    @Test
    void isIdempotent() {
        DarkOtherId.put(version, otherIdType, "dARK", ARK);
        assertFalse(DarkOtherId.put(version, otherIdType, "dARK", "ark:/12345/abc"));
        assertEquals(1, otherIdField().getDatasetFieldCompoundValues().size());
    }

    @Test
    void keepsOtherAgenciesAndAppends() {
        addExisting("ISBN", "978-3-16-148410-0");
        DarkOtherId.put(version, otherIdType, "dARK", ARK);

        List<DatasetFieldCompoundValue> values = otherIdField().getDatasetFieldCompoundValues();
        assertEquals(2, values.size());
        assertEquals(ARK, DarkOtherId.find(version, "dARK"));
        assertEquals(1, values.get(1).getDisplayOrder());
    }

    @Test
    void fixesAStaleDarkValue() {
        addExisting("dARK", "ark:12345/old");
        assertTrue(DarkOtherId.put(version, otherIdType, "dARK", ARK));
        assertEquals(1, otherIdField().getDatasetFieldCompoundValues().size());
        assertEquals(ARK, DarkOtherId.find(version, "dARK"));
    }

    @Test
    void reusesAnEmptyEntry() {
        DatasetField f = DatasetField.createNewEmptyDatasetField(otherIdType, version);
        version.getDatasetFields().add(f);
        DarkOtherId.put(version, otherIdType, "dARK", ARK);
        assertEquals(1, otherIdField().getDatasetFieldCompoundValues().size());
        assertEquals(ARK, DarkOtherId.find(version, "dARK"));
    }

    @Test
    void agencyMatchIsCaseInsensitive() {
        addExisting("DARK", ARK);
        assertEquals(ARK, DarkOtherId.find(version, "dARK"));
        assertNull(DarkOtherId.find(version, "Other"));
    }

    @Test
    void containsArkIgnoresAgencyAndSlashForm() {
        assertFalse(DarkOtherId.containsArk(version, ARK), "no Other ID field");
        addExisting("ARK manual", "ark:/12345/abc");
        assertTrue(DarkOtherId.containsArk(version, ARK));
        assertFalse(DarkOtherId.containsArk(version, "ark:12345/other"));
    }

    @Test
    void putSkipsAnArkAlreadyPresentUnderAnotherAgency() {
        addExisting("ARK manual", ARK);
        assertFalse(DarkOtherId.put(version, otherIdType, "dARK", ARK));
        assertEquals(1, otherIdField().getDatasetFieldCompoundValues().size());
        assertNull(DarkOtherId.find(version, "dARK"));
    }

    private DatasetField otherIdField() {
        return version.getDatasetFields().stream()
                .filter(f -> DatasetFieldConstant.otherId.equals(f.getDatasetFieldType().getName()))
                .findFirst().orElseThrow();
    }

    private void addExisting(String agency, String value) {
        DatasetField f = version.getDatasetFields().stream()
                .filter(x -> DatasetFieldConstant.otherId.equals(x.getDatasetFieldType().getName()))
                .findFirst().orElse(null);
        if (f == null) {
            f = new DatasetField();
            f.setDatasetFieldType(otherIdType);
            f.setDatasetVersion(version);
            version.getDatasetFields().add(f);
        }
        DatasetFieldCompoundValue cv = new DatasetFieldCompoundValue();
        cv.setParentDatasetField(f);
        cv.setDisplayOrder(f.getDatasetFieldCompoundValues().size());
        for (DatasetFieldType childType : otherIdType.getChildDatasetFieldTypes()) {
            DatasetField child = DatasetField.createNewEmptyChildDatasetField(childType, cv);
            String v = DatasetFieldConstant.otherIdAgency.equals(childType.getName()) ? agency : value;
            child.setDatasetFieldValues(new ArrayList<>(List.of(new DatasetFieldValue(child, v))));
            cv.getChildDatasetFields().add(child);
        }
        f.getDatasetFieldCompoundValues().add(cv);
    }
}
