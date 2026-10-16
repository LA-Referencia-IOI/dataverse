package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.DatasetField;
import edu.harvard.iq.dataverse.DatasetFieldCompoundValue;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetFieldValue;
import edu.harvard.iq.dataverse.DatasetVersion;
import java.util.List;

/**
 * Reads and writes the dARK entry of the citation block's Other ID field
 * ({@code otherId} with {@code otherIdAgency} + {@code otherIdValue}) in memory.
 * No persistence here: callers merge the version.
 */
public final class DarkOtherId {

    private DarkOtherId() {
    }

    /** @return the ARK stored under the given agency, or null. */
    public static String find(DatasetVersion version, String agency) {
        DatasetField otherId = field(version);
        if (otherId == null) {
            return null;
        }
        for (DatasetFieldCompoundValue cv : otherId.getDatasetFieldCompoundValues()) {
            if (agency.equalsIgnoreCase(trim(childValue(cv, DatasetFieldConstant.otherIdAgency)))) {
                String value = trim(childValue(cv, DatasetFieldConstant.otherIdValue));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** @return true if the version already exposes this ARK under the given agency. */
    public static boolean has(DatasetVersion version, String agency, String ark) {
        String found = DarkIds.normalizeArk(find(version, agency));
        return found != null && found.equals(DarkIds.normalizeArk(ark));
    }

    /**
     * @return true if any Other ID entry of the version holds this ARK, whatever its agency
     *         (a manual correction of the agency is respected). Both {@code ark:12345/x} and
     *         {@code ark:/12345/x} forms match.
     */
    public static boolean containsArk(DatasetVersion version, String ark) {
        String wanted = DarkIds.normalizeArk(ark);
        DatasetField otherId = field(version);
        if (wanted == null || otherId == null) {
            return false;
        }
        for (DatasetFieldCompoundValue cv : otherId.getDatasetFieldCompoundValues()) {
            if (wanted.equals(DarkIds.normalizeArk(trim(childValue(cv, DatasetFieldConstant.otherIdValue))))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes sure the version exposes {@code ark} under {@code agency}. Reuses an existing
     * entry of that agency (fixing its value), then an empty entry, else appends one.
     *
     * @param otherIdType the {@code otherId} compound type (with its child types)
     * @return true if the version was modified
     */
    public static boolean put(DatasetVersion version, DatasetFieldType otherIdType, String agency, String ark) {
        if (containsArk(version, ark)) {
            return false;
        }
        DatasetField otherId = field(version);
        if (otherId == null) {
            otherId = DatasetField.createNewEmptyDatasetField(otherIdType, version);
            version.getDatasetFields().add(otherId);
        }

        DatasetFieldCompoundValue target = null;
        for (DatasetFieldCompoundValue cv : otherId.getDatasetFieldCompoundValues()) {
            if (agency.equalsIgnoreCase(trim(childValue(cv, DatasetFieldConstant.otherIdAgency)))) {
                target = cv;
                break;
            }
        }
        if (target == null) {
            for (DatasetFieldCompoundValue cv : otherId.getDatasetFieldCompoundValues()) {
                if (trim(childValue(cv, DatasetFieldConstant.otherIdAgency)) == null
                        && trim(childValue(cv, DatasetFieldConstant.otherIdValue)) == null) {
                    target = cv;
                    break;
                }
            }
        }
        if (target == null) {
            target = DatasetFieldCompoundValue.createNewEmptyDatasetFieldCompoundValue(otherId);
            target.setDisplayOrder(otherId.getDatasetFieldCompoundValues().size());
            otherId.getDatasetFieldCompoundValues().add(target);
        }
        setChildValue(target, DatasetFieldConstant.otherIdAgency, agency);
        setChildValue(target, DatasetFieldConstant.otherIdValue, DarkIds.normalizeArk(ark));
        return true;
    }

    private static DatasetField field(DatasetVersion version) {
        for (DatasetField f : version.getDatasetFields()) {
            if (DatasetFieldConstant.otherId.equals(f.getDatasetFieldType().getName())) {
                return f;
            }
        }
        return null;
    }

    private static DatasetField child(DatasetFieldCompoundValue cv, String typeName) {
        for (DatasetField c : cv.getChildDatasetFields()) {
            if (typeName.equals(c.getDatasetFieldType().getName())) {
                return c;
            }
        }
        return null;
    }

    private static String childValue(DatasetFieldCompoundValue cv, String typeName) {
        DatasetField c = child(cv, typeName);
        if (c == null) {
            return null;
        }
        List<DatasetFieldValue> values = c.getDatasetFieldValues();
        return values == null || values.isEmpty() ? null : values.get(0).getValue();
    }

    private static void setChildValue(DatasetFieldCompoundValue cv, String typeName, String value) {
        DatasetField c = child(cv, typeName);
        if (c == null) {
            DatasetFieldType childType = null;
            for (DatasetFieldType t : cv.getParentDatasetField().getDatasetFieldType().getChildDatasetFieldTypes()) {
                if (typeName.equals(t.getName())) {
                    childType = t;
                }
            }
            if (childType == null) {
                throw new IllegalStateException("otherId has no child field " + typeName);
            }
            c = DatasetField.createNewEmptyChildDatasetField(childType, cv);
            cv.getChildDatasetFields().add(c);
        }
        if (c.getDatasetFieldValues().isEmpty()) {
            c.getDatasetFieldValues().add(new DatasetFieldValue(c, value));
        } else {
            c.getDatasetFieldValues().get(0).setValue(value);
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
