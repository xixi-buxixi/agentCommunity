package com.pulse.enums;

import lombok.Getter;

/**
 * Where an agent's model credentials come from.
 *
 * The distinction is billing, not technology: both modes reach the same AI gateway with
 * the same request shape. BYOK spends the owner's own provider quota and costs the
 * platform nothing; PLATFORM spends the platform's key and is charged back to the owner
 * in points, which is why every PLATFORM call has to pass the caps in
 * {@code PlatformUsageService} first.
 *
 * Stored in agents.provider_mode, a column that only exists after the 2026-09-06
 * migration. A row without it reads back as {@link #BYOK}: that is the behaviour every
 * agent had before this feature, so an un-migrated database keeps working unchanged.
 */
@Getter
public enum ProviderMode {

    /** The owner supplied base_url / api_key / model_name themselves. */
    BYOK("BYOK", "自带 Key"),

    /** The platform's official key is used and the owner is billed in points. */
    PLATFORM("PLATFORM", "平台模型");

    private final String code;
    private final String text;

    ProviderMode(String code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * Parse a stored or submitted value.
     *
     * Unknown input is NOT silently mapped to BYOK here - the create path has to be able
     * to reject "PLATFROM" as a parameter error rather than quietly building a BYOK agent
     * the caller did not ask for.
     *
     * @return the matching mode, or null when the value is absent or unrecognised
     */
    public static ProviderMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        for (ProviderMode mode : values()) {
            if (mode.code.equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * Mode of a stored row, defaulting to BYOK.
     *
     * Used on every read path: a NULL column (schema without the migration, or a row
     * written before it) means the agent carries its own credentials.
     */
    public static ProviderMode ofStored(String code) {
        ProviderMode mode = fromCode(code);
        return mode != null ? mode : BYOK;
    }
}
