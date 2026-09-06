package com.pulse.service.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matching rule is the whole feature: it decides which agents are woken and whose
 * tokens are spent. Everything worth arguing about is a boundary, so the boundaries are
 * what this covers.
 *
 * Matching is candidate driven - the caller offers the names that exist, the detector
 * says which of them the body mentions - so every case here names its candidates.
 */
class MentionDetectorTest {

    @Test
    void aPlainLatinNameIsFound() {
        assertThat(MentionDetector.detect("@Nova 你怎么看", List.of("Nova")))
                .containsExactly("Nova");
    }

    @Test
    void aChineseNameEndsAtTheFirstPunctuationMark() {
        // The comma ends the name; without that rule "@小明，你看看这个" would have to
        // spell out the whole clause to reach 小明.
        assertThat(MentionDetector.detect("@小明，你看看这个", List.of("小明")))
                .containsExactly("小明");
    }

    @Test
    void aNameEndsAtWhitespaceAndAtEndOfText() {
        assertThat(MentionDetector.mentions("问问 @Nova", "Nova")).isTrue();
        assertThat(MentionDetector.mentions("@Nova\n换行了", "Nova")).isTrue();
    }

    @Test
    void digitsUnderscoresAndHyphensAreAllPartOfAName() {
        assertThat(MentionDetector.detect("@gpt-4_mini 说得对", List.of("gpt-4_mini")))
                .containsExactly("gpt-4_mini");
    }

    @Test
    void severalNamesAreReturnedInTheOrderTheyWereOffered() {
        assertThat(MentionDetector.detect("@Nova @小明 都来看看", List.of("Nova", "小明")))
                .containsExactly("Nova", "小明");
    }

    @Test
    void theSameNameTwiceIsOneEntry() {
        assertThat(MentionDetector.detect("@Nova 说的对，@Nova 再说一次", List.of("Nova", "Nova")))
                .containsExactly("Nova");
    }

    /**
     * Case follows the collation of agents.name itself: "@nova" reaches an agent the
     * database considers to be called Nova, and the two spellings are one name, so a body
     * using both still costs that agent a single wake-up.
     */
    @Test
    void matchingIgnoresCase() {
        assertThat(MentionDetector.mentions("@NOVA 看一下", "Nova")).isTrue();
        assertThat(MentionDetector.mentions("@nova 看一下", "Nova")).isTrue();
        assertThat(MentionDetector.detect("@Nova 和 @nova", List.of("Nova", "nova")))
                .containsExactly("Nova");
    }

    // ========== what the fixed alphabet used to make unreachable ==========

    /**
     * agents.name has no character whitelist, so these are all names an owner can
     * actually create. Under the old "@" + [CJK/Latin/digits/_/-] regex none of them
     * could ever be mentioned: the fragment stopped at the space or the full stop, and
     * nothing in the database matched what was left.
     */
    @Test
    void aNameContainingASpaceOrAFullStopIsStillFound() {
        assertThat(MentionDetector.mentions("@Dr. Ada 你怎么看", "Dr. Ada")).isTrue();
        assertThat(MentionDetector.mentions("@数据.分析师，来看看", "数据.分析师")).isTrue();
        assertThat(MentionDetector.mentions("@Ada Lovelace！", "Ada Lovelace")).isTrue();
    }

    @Test
    void aMixedChineseAndLatinNameIsFound() {
        assertThat(MentionDetector.detect("@小明 Nova 一起看", List.of("小明 Nova")))
                .containsExactly("小明 Nova");
    }

    // ========== prefixes ==========

    /**
     * The reason a name has to END where the detector says it does. Alice and Alice2 can
     * both be candidates in one thread, and "@Alice2" belongs to exactly one of them.
     */
    @Test
    void aLongerNameIsNotAMentionOfTheNameItStartsWith() {
        assertThat(MentionDetector.detect("@Alice2 你怎么看", List.of("Alice", "Alice2")))
                .containsExactly("Alice2");
        assertThat(MentionDetector.detect("@Alice 你怎么看", List.of("Alice", "Alice2")))
                .containsExactly("Alice");
    }

    @Test
    void aChineseNameFollowedByMoreChineseIsNotAMention() {
        // "@小明的看法是错的" names nobody: 小明 is not where the name ends.
        assertThat(MentionDetector.mentions("@小明的看法是错的", "小明")).isFalse();
    }

    // ========== nothing at all ==========

    @Test
    void aBareAtSignYieldsNothing() {
        assertThat(MentionDetector.detect("@ 没有名字", List.of("Nova"))).isEmpty();
        assertThat(MentionDetector.detect("邮箱是 a@ b", List.of("Nova"))).isEmpty();
    }

    @Test
    void nullAndBlankBodiesYieldNothing() {
        assertThat(MentionDetector.detect(null, List.of("Nova"))).isEmpty();
        assertThat(MentionDetector.detect("   ", List.of("Nova"))).isEmpty();
        assertThat(MentionDetector.mentions(null, "Nova")).isFalse();
    }

    @Test
    void textWithoutAnyAtSignYieldsNothing() {
        assertThat(MentionDetector.detect("今天天气不错", List.of("Nova"))).isEmpty();
        assertThat(MentionDetector.containsMentionMarker("今天天气不错")).isFalse();
        assertThat(MentionDetector.containsMentionMarker("@Nova")).isTrue();
        assertThat(MentionDetector.containsMentionMarker(null)).isFalse();
    }

    @Test
    void noCandidatesMeansNoMentions() {
        assertThat(MentionDetector.detect("@Nova 你怎么看", List.of())).isEmpty();
        assertThat(MentionDetector.detect("@Nova 你怎么看", null)).isEmpty();
    }

    /**
     * A candidate longer than the column width cannot be a stored name, so it is refused
     * rather than searched for. Blank and null candidates are simply skipped.
     */
    @Test
    void anOverlongOrEmptyCandidateIsIgnored() {
        String wall = "x".repeat(MentionDetector.MAX_NAME_LENGTH + 1);

        assertThat(MentionDetector.mentions("@" + wall, wall)).isFalse();

        List<String> names = new ArrayList<>();
        names.add(null);
        names.add("   ");
        names.add("Nova");
        assertThat(MentionDetector.detect("@Nova", names)).containsExactly("Nova");
    }

    /**
     * A body is user-controlled and every mention costs somebody a wake-up, so the number
     * of names one body can reach has to be bounded.
     */
    @Test
    void aBodyStuffedWithMentionsIsCappedRatherThanObeyed() {
        StringBuilder body = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < MentionDetector.MAX_MENTIONS + 30; i++) {
            body.append("@name").append(i).append(' ');
            names.add("name" + i);
        }

        assertThat(MentionDetector.detect(body.toString(), names))
                .hasSize(MentionDetector.MAX_MENTIONS);
    }

    /**
     * An email address is the one everyday string that looks like a mention. It does
     * reach an agent called "example" - which is correct and harmless: only agents
     * already in the thread are ever candidates, and one being pulled into a conversation
     * that spells out its name is what the feature is for.
     */
    @Test
    void anEmailAddressCanMentionAnAgentThatHappensToShareTheDomainName() {
        assertThat(MentionDetector.detect("写信到 alice@example.com", List.of("example")))
                .containsExactly("example");
    }
}
