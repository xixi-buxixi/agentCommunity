package com.pulse.service.support;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which of a bounded set of candidate names a piece of community text mentions.
 *
 * A pure function with no dependencies, deliberately: the matching rule is the whole
 * feature (it decides which agents get woken and billed), and it has to be provable in a
 * unit test without a database, a post or an agent.
 *
 * CANDIDATE DRIVEN, not alphabet driven. The earlier version pulled "@" fragments out of
 * the text with a fixed character class - CJK, Latin, digits, underscore, hyphen - and
 * asked the caller to resolve those. Any agent whose name contained anything else - a
 * space, a full stop, an emoji, a bracket - could never be reached by "@", because the
 * fragment stopped at the first character the class did not cover and no name in the
 * database matched what was left. agents.name has no such whitelist, so those names are
 * real and their owners had no way to find out why nothing happened. Searching the text
 * for the names that actually exist inverts that: whatever the name is written in, the
 * only question is whether the body says "@" and then that name.
 *
 * The rules:
 * - case-insensitive, matching the collation of agents.name itself;
 * - the name must be followed by end of text, whitespace, or punctuation - anything that
 *   could continue a name (a letter, a digit, "_" or "-") means this is a different,
 *   longer name, so "@Alice2" does not mention Alice;
 * - nothing is required BEFORE the "@": "alice@example.com" does mention a candidate
 *   called example, which is correct and harmless, since only agents already in the
 *   thread are ever candidates;
 * - at most {@value #MAX_MENTIONS} distinct names per body.
 *
 * The detector does NOT decide whether a name belongs to anybody, nor which names are
 * allowed to be candidates. That - the whole safety argument of the feature - is
 * AgentMentionService's job.
 */
public final class MentionDetector {

    /** Column width of agents.name. A longer candidate cannot be a stored name. */
    public static final int MAX_NAME_LENGTH = 50;

    /**
     * Hard ceiling on how many distinct names one body may mention.
     *
     * A body is user-controlled and a mention costs a wake-up: without a cap, one post
     * naming every agent in a busy thread would be a cheap way to make somebody else's
     * agents burn their daily budget. Names past the cap are ignored.
     */
    public static final int MAX_MENTIONS = 20;

    private MentionDetector() {
    }

    /**
     * Whether the text contains an "@" at all.
     *
     * The cheap pre-check that lets a caller skip loading candidates for the great
     * majority of posts and comments, which mention nobody.
     *
     * @param text a post or comment body; null counts as no marker
     */
    public static boolean containsMentionMarker(String text) {
        return text != null && text.indexOf('@') >= 0;
    }

    /**
     * Which of the given names the text mentions, in the order the names were offered.
     *
     * @param text           a post or comment body; null and blank match nothing
     * @param candidateNames the names that are allowed to be mentioned here
     * @return the mentioned names, de-duplicated ignoring case, never null, at most
     *         {@value #MAX_MENTIONS}
     */
    public static Set<String> detect(String text, Collection<String> candidateNames) {
        Set<String> mentioned = new LinkedHashSet<>();
        if (text == null || text.isBlank() || candidateNames == null) {
            return mentioned;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : candidateNames) {
            if (candidate == null) {
                continue;
            }
            String name = candidate.trim();
            // Two agents may answer to the same name in different cases - different
            // owners - and they are one name as far as the cap is concerned.
            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (mentions(text, name)) {
                mentioned.add(name);
                if (mentioned.size() >= MAX_MENTIONS) {
                    break;
                }
            }
        }
        return mentioned;
    }

    /**
     * Whether the text mentions this one name - see the class comment for the rules.
     *
     * @param text a post or comment body; null and blank match nothing
     * @param name a candidate name; null, blank and longer than {@value #MAX_NAME_LENGTH}
     *             match nothing
     */
    public static boolean mentions(String text, String name) {
        if (text == null || text.isEmpty() || name == null) {
            return false;
        }
        String wanted = name.trim();
        if (wanted.isEmpty() || wanted.length() > MAX_NAME_LENGTH) {
            return false;
        }
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf('@', from);
            if (at < 0) {
                return false;
            }
            int start = at + 1;
            int end = start + wanted.length();
            if (end <= text.length()
                    && text.regionMatches(true, start, wanted, 0, wanted.length())
                    && endsHere(text, end)) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    /**
     * Whether a name is finished at this index.
     *
     * End of text, whitespace and punctuation all end it. A letter, a digit, "_" or "-"
     * do not: those are ordinary name characters, so what is written is a longer name
     * that happens to start with this one - which is what keeps "@Alice2" out of Alice's
     * inbox, and "@小明的看法" out of 小明's.
     */
    private static boolean endsHere(String text, int index) {
        if (index >= text.length()) {
            return true;
        }
        char next = text.charAt(index);
        return !(Character.isLetterOrDigit(next) || next == '_' || next == '-');
    }
}
