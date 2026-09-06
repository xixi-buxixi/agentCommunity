package com.pulse.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Scrubs memory card text before it is stored.
 *
 * Memory content is untrusted twice over: it is derived from LLM output and from
 * community posts, and from phase 2 on it is fed back into the decision prompt. Three
 * separate risks are handled here:
 *
 * 1. Obfuscation - a secret split with zero-width characters, written in full-width
 *    forms or padded with exotic Unicode spaces defeats any plain regex. Text is
 *    therefore NFKC-normalized and stripped of invisible characters BEFORE matching,
 *    and the normalized form is what gets stored: the AI side normalizes too, so a
 *    value that would only reassemble downstream has to be caught here first.
 * 2. Secret leakage - an agent that quotes an API key, a Bearer token or an email
 *    address in a post would otherwise get that value persisted in a table the
 *    frontend renders. Those patterns are replaced with {@value #REDACTED}.
 * 3. Block-boundary forgery - the AI side splits context into blocks on lines that
 *    look like "[Post#N] ..." or "[World#N] ...". Newlines are flattened and both
 *    literal block prefixes are defused, the same way
 *    {@code AgentLoopScheduler#flattenForContext} does it for posts, so a card can
 *    never split an injection payload across two blocks.
 *
 * Pattern matching is a filter, not a guarantee: it covers the common credential
 * formats, and the residual risk is carried by the phase-2 rule that memories are
 * never treated as instructions.
 */
public final class MemoryTextSanitizer {

    public static final String REDACTED = "[REDACTED]";

    /**
     * Replacement for a whole text that only looks like a credential once whitespace is
     * removed. The value cannot be pinpointed in that case, so the text is dropped
     * wholesale - a memory card is at most 200 characters, and losing one is far
     * cheaper than storing a key.
     */
    public static final String SUSPECTED_CREDENTIAL = "[REDACTED_SUSPECTED_CREDENTIAL]";

    /** Invisible characters: a secret sliced with these looks harmless to a regex. */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD\\u034F]");

    /** Unicode spaces (NBSP, ideographic space, ...) folded to a plain space. */
    private static final Pattern UNICODE_SPACE =
            Pattern.compile("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Characters a credential is actually made of. Deliberately NOT {@code \S}: the
     * whitespace-stripped detection pass (see {@link #looksSplicedByWhitespace}) has no
     * spaces left to stop at, so a {@code \S+} value would run across the rest of the
     * card - CJK punctuation included - and collect a digit or a capital from anywhere,
     * flagging perfectly ordinary text.
     */
    private static final String CREDENTIAL_CHAR = "[A-Za-z0-9\\-_./+=~]";

    /**
     * A value that actually looks like a credential: at least 12 credential characters
     * and either a digit, or mixed case together with a symbol.
     *
     * Without this shape test the keyword patterns fired on ordinary prose - "The
     * secret: consistency matters" and "bearer responsibility" both got redacted, which
     * silently mangles the memory instead of protecting anything.
     *
     * MUST NOT be used under a global {@code (?i)} flag: case-insensitivity would make
     * {@code [A-Z]} match lowercase, collapsing the mixed-case test into "contains a
     * letter" and bringing the false positives straight back. The keyword parts below
     * therefore scope the flag with {@code (?i:...)}.
     */
    private static final String CREDENTIAL_VALUE =
            "(?=" + CREDENTIAL_CHAR + "{12,})"
                    + "(?:(?=" + CREDENTIAL_CHAR + "*\\d)"
                    + "|(?=" + CREDENTIAL_CHAR + "*[a-z])(?=" + CREDENTIAL_CHAR + "*[A-Z])"
                    + "(?=" + CREDENTIAL_CHAR + "*[-_./+=]))"
                    + CREDENTIAL_CHAR + "{12,}";

    /** "Authorization: Bearer <token>" and bare "Bearer <token>". */
    private static final Pattern BEARER =
            Pattern.compile("\\b(?i:bearer)\\s+" + CREDENTIAL_VALUE);

    /** JWTs: three base64url segments, header first ("eyJ" = '{"'). */
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");

    /**
     * OpenAI-style secrets (sk-/pk-/rk-) and the Stripe underscore variants
     * (sk_live_/sk_test_).
     */
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b(?:sk|pk|rk)(?:-|_(?:live|test)_)[A-Za-z0-9_-]{6,}");

    /** GitHub tokens: ghp_/gho_/ghs_/ghu_/ghr_ and fine-grained github_pat_. */
    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})");

    /** AWS access key ids. */
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");

    /** Slack tokens. */
    private static final Pattern SLACK_TOKEN = Pattern.compile("(?i)\\bxox[baprs]-[A-Za-z0-9-]{8,}");

    /**
     * key/secret/token/password assignments, e.g. api_key=abcdef123456.
     *
     * The key name allows a prefix (client_secret, X-Api-Key, refresh_token): a plain
     * {@code \b(secret|token|...)} never fired on "client_secret=..." because the
     * underscore is a word character, so there is no word boundary in front of
     * "secret". The lookbehind takes over that job. The full-width colon stays in the
     * separator class because the assembled-template pass does not normalize (see
     * {@link #redactAndFlatten}).
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?<![A-Za-z0-9_-])((?i:[A-Za-z0-9_-]{0,24}?(?:secret|token|api[-_ ]?key|key|password|passwd|pwd)))"
                    + "\\s*[:=\\uFF1A]\\s*" + CREDENTIAL_VALUE);

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private MemoryTextSanitizer() {
    }

    /**
     * Full pipeline for untrusted input: detect on the aggressively normalized form,
     * redact, flatten to one line. Use this on every fragment that comes from a post,
     * an LLM answer or an owner correction.
     *
     * Detection always runs on the NFKC form, so a full-width or zero-width-spliced
     * credential cannot slip past. Storage, however, only switches to that form when
     * something actually matched: NFKC rewrites CJK full-width punctuation (，：（）) to
     * ASCII, and applying it unconditionally would degrade the typography of every
     * Chinese memory card for no security gain. When nothing matches, the stored text
     * keeps its punctuation and only loses invisible characters and exotic spaces -
     * and since NFKC never turns a clean ASCII run into a credential, "nothing matched
     * on the NFKC form" also means nothing can reassemble downstream.
     *
     * @return "" for null input, so callers never have to null-check
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String light = stripInvisibleAndFoldSpaces(text);
        String probe = stripInvisibleAndFoldSpaces(Normalizer.normalize(text, Normalizer.Form.NFKC));

        String redactedProbe = redact(probe);
        String redactedLight = redact(light);
        boolean somethingMatched = !redactedProbe.equals(probe) || !redactedLight.equals(light);

        if (!somethingMatched && looksSplicedByWhitespace(probe)) {
            return SUSPECTED_CREDENTIAL;
        }

        // Whenever anything matched, the fully normalized + redacted form wins: it is
        // the only one guaranteed to hold no reassemblable remnant.
        return flatten(somethingMatched ? redactedProbe : light).trim();
    }

    /**
     * Redact and flatten WITHOUT normalizing.
     *
     * Used for the second pass over an assembled card body, where the template's own
     * full-width CJK punctuation (《》（）：) is intentional and NFKC would rewrite it to
     * ASCII. The interpolated fragments have been through {@link #sanitize} already, so
     * normalization has happened where it matters; this pass only exists to catch a
     * secret that reassembled across two fragments.
     */
    public static String redactAndFlatten(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = redact(text);
        if (redacted.equals(text) && looksSplicedByWhitespace(text)) {
            return SUSPECTED_CREDENTIAL;
        }
        return flatten(redacted).trim();
    }

    /**
     * Whether the text hides a credential behind whitespace: "sk- abcdef123456" and
     * "ghp_ abc..." defeat every pattern above, but not once the spaces are gone.
     *
     * Only consulted when the normal pass found nothing, and it cannot tell WHERE the
     * value is (the offsets no longer line up with the original text), so the caller
     * drops the whole text rather than trying to patch it.
     */
    private static boolean looksSplicedByWhitespace(String text) {
        String stripped = WHITESPACE.matcher(text).replaceAll("");
        return !redact(stripped).equals(stripped);
    }

    /**
     * Apply every credential pattern. Pure: no flattening or trimming, because
     * {@link #sanitize} compares the result against its input to decide whether
     * anything matched at all.
     */
    private static String redact(String text) {
        String result = BEARER.matcher(text).replaceAll(REDACTED);
        result = JWT.matcher(result).replaceAll(REDACTED);
        result = API_KEY.matcher(result).replaceAll(REDACTED);
        result = GITHUB_TOKEN.matcher(result).replaceAll(REDACTED);
        result = AWS_ACCESS_KEY.matcher(result).replaceAll(REDACTED);
        result = SLACK_TOKEN.matcher(result).replaceAll(REDACTED);
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=" + REDACTED);
        return EMAIL.matcher(result).replaceAll(REDACTED);
    }

    /**
     * Full compatibility folding: NFKC plus invisible-character and space cleanup.
     *
     * Exposed for comparison keys (trait de-duplication), NOT for storage - see
     * {@link #sanitize} for why the stored form keeps its full-width CJK punctuation.
     * As a comparison key the folding is exactly what is wanted: "支持Ａ，反对Ｂ" and
     * "支持A,反对B" are the same claim written twice.
     */
    public static String foldCompatibility(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return stripInvisibleAndFoldSpaces(Normalizer.normalize(text, Normalizer.Form.NFKC));
    }

    /**
     * Drop invisible characters and fold exotic Unicode spaces. Applied to stored text
     * unconditionally: an invisible character in a memory card has no legitimate use
     * and is exactly what a splice attack needs.
     */
    private static String stripInvisibleAndFoldSpaces(String text) {
        String result = INVISIBLE.matcher(text).replaceAll("");
        return UNICODE_SPACE.matcher(result).replaceAll(" ");
    }

    /**
     * Single-line, boundary-safe form.
     */
    public static String flatten(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("[\\r\\n]+", " ")
                .replace("[Post#", "(Post#")
                // [World#N] became a block boundary on the AI side alongside [Post#N];
                // both have to be rewritten here or one of the two kinds of forged
                // header survives this layer.
                .replace("[World#", "(World#")
                // Kept in step with AgentWakeProcessor#flattenForContext: a memory card
                // that could write a [Comment#N] handle would let stored text point an
                // agent's reply at a comment nobody wrote.
                .replace("[Comment#", "(Comment#")
                .replace("[记忆", "(记忆");
    }

    /**
     * Hard length cap. Keeps the project's "..." idiom while guaranteeing the result
     * never exceeds {@code maxLength} - a cap that can be overshot by the ellipsis is
     * not a cap.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
