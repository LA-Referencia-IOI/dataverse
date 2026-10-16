package edu.harvard.iq.dataverse.dark;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Lifecycle states of a dARK identifier, as reported by the dARK Minter.
 * Stored locally as a single character (same codes as the Minter).
 */
public enum DarkState {

    /** ARK reserved, no metadata yet; does not resolve. */
    RESERVED('R'),
    /** Metadata accepted, publication queued. */
    DRAFT('D'),
    /** Published, new metadata being processed. */
    UPDATE('U'),
    /** Published and resolvable. */
    PUBLISHED('P'),
    /** Tombstoned; irreversible. */
    TOMBSTONE('T');

    private final char code;

    DarkState(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    /** @return true for states in which the Minter is still processing metadata (a PUT would return 409). */
    public boolean isInProgress() {
        return this == DRAFT || this == UPDATE;
    }

    public static DarkState fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Empty dARK state");
        }
        String c = code.trim();
        for (DarkState s : values()) {
            if (c.length() == 1 && Character.toUpperCase(c.charAt(0)) == s.code) {
                return s;
            }
            if (c.equalsIgnoreCase(s.name())) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown dARK state: " + code);
    }

    @Converter(autoApply = false)
    public static class DbConverter implements AttributeConverter<DarkState, String> {
        @Override
        public String convertToDatabaseColumn(DarkState state) {
            return state == null ? null : String.valueOf(state.code);
        }

        @Override
        public DarkState convertToEntityAttribute(String code) {
            return code == null ? null : DarkState.fromCode(code);
        }
    }
}
