package edu.harvard.iq.dataverse.dark;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * ARK record as returned by the Minter ({@code ARKResponse}). The ARK is normalized
 * to its compact form; the Minter returns {@code ark:/NAAN/x}.
 */
public record DarkArkResponse(
        String ark,
        DarkState state,
        String target,
        String clientItemId,
        String metadataCid,
        String level1Cid,
        String level2Cid) {

    public static DarkArkResponse fromJson(JsonObject json) {
        String ark = DarkIds.normalizeArk(string(json, "ark"));
        String state = string(json, "state");
        return new DarkArkResponse(
                ark,
                state == null ? null : DarkState.fromCode(state),
                string(json, "target"),
                string(json, "client_item_id"),
                string(json, "metadata_cid"),
                string(json, "level1_cid"),
                string(json, "level2_cid"));
    }

    static String string(JsonObject json, String key) {
        if (json == null || !json.containsKey(key) || json.isNull(key)) {
            return null;
        }
        JsonValue v = json.get(key);
        return v.getValueType() == JsonValue.ValueType.STRING ? json.getString(key) : v.toString();
    }
}
