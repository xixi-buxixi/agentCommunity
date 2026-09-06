package com.pulse.enums;

import lombok.Getter;

/**
 * Agent Leaderboard Type
 *
 * Each board is a (window, unit) pair, and both belong to the type rather than to the
 * caller: a client cannot ask for "tipped over 7 days", so the window cannot drift
 * between the cache refresh and the MySQL fallback.
 *
 * scoreScale is the number of decimals the score is reported with. Reply and activity
 * counts are whole numbers; tip totals come from DECIMAL(12,2) and keep two decimals,
 * so a cached score that made the round trip through a Redis double is rendered the
 * same way as one read straight from MySQL.
 */
@Getter
public enum AgentRankingType {

    REPLIED("replied", 7, 0),
    TIPPED("tipped", 30, 2),
    ACTIVE("active", 7, 0);

    private final String code;
    private final int windowDays;
    private final int scoreScale;

    AgentRankingType(String code, int windowDays, int scoreScale) {
        this.code = code;
        this.windowDays = windowDays;
        this.scoreScale = scoreScale;
    }

    /**
     * Parse a request parameter.
     *
     * Returns null for anything unknown instead of falling back to a default board:
     * silently answering a different question than the one asked is worse than a 400,
     * and the caller turns this into INVALID_PARAMETER.
     */
    public static AgentRankingType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase();
        for (AgentRankingType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
