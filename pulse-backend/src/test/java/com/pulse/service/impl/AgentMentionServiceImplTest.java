package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Agent;
import com.pulse.entity.Comment;
import com.pulse.entity.Post;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.service.AgentWakeEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who an "@name" may reach, and who it may not.
 *
 * The candidate set is the whole safety argument of this feature: a name lookup across
 * the community would make "@" a way to spend a stranger's tokens from a post they never
 * saw. Everything here is a statement about that boundary or about not waking the same
 * agent twice for one interaction.
 */
class AgentMentionServiceImplTest {

    private static final Long POST_ID = 88L;
    private static final Long COMMENT_ID = 500L;
    private static final Long SPEAKER_USER_ID = 10L;

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final AgentWakeEventService agentWakeEventService = mock(AgentWakeEventService.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final AgentMentionServiceImpl service = new AgentMentionServiceImpl(
            agentMapper, commentMapper, agentWakeEventService, schemaCapabilities);

    @BeforeEach
    void schemaPresentAndThreadEmpty() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(true);
        when(commentMapper.findAgentAuthorIdsByPost(anyLong(), anyInt())).thenReturn(List.of());
        when(agentMapper.findAliveAgentsByIds(any())).thenReturn(List.of());
        when(agentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    }

    // ========== the candidate set ==========

    @Test
    void thePostsOwnAgentAuthorCanBeMentioned() {
        givenThreadAgents(agent(30L, "Nova"));

        service.recordMentionsInComment(agentPost(30L), comment("@Nova 你怎么看"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService).recordMention(eq(30L), eq("COMMENT"), eq(COMMENT_ID),
                eq(AuthorType.HUMAN.getCode()), eq(SPEAKER_USER_ID));
    }

    @Test
    void anAgentThatAlreadyCommentedUnderThePostCanBeMentioned() {
        when(commentMapper.findAgentAuthorIdsByPost(eq(POST_ID), anyInt())).thenReturn(List.of(31L));
        when(agentMapper.findAliveAgentsByIds(any())).thenReturn(List.of(agent(31L, "Echo")));

        service.recordMentionsInComment(humanPost(), comment("@Echo 也说两句"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService).recordMention(eq(31L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    @Test
    void aHumanSpeakerCanMentionTheirOwnAgentEvenThoughItIsNotInTheThread() {
        when(agentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(agent(40L, "Mine")));

        service.recordMentionsInPost(humanPost("@Mine 来看看这个"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID);

        verify(agentWakeEventService).recordMention(eq(40L), eq("POST"), eq(POST_ID),
                eq(AuthorType.HUMAN.getCode()), eq(SPEAKER_USER_ID));
    }

    /**
     * The point of the bounded candidate set: a name that belongs to somebody who never
     * entered this conversation resolves to nobody, so "@" cannot reach a stranger's
     * agent.
     */
    @Test
    void aNameOutsideTheThreadAndOutsideTheSpeakersAgentsReachesNobody() {
        givenThreadAgents(agent(30L, "Nova"));

        service.recordMentionsInComment(agentPost(30L), comment("@Stranger 你在吗"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verifyNoInteractions(agentWakeEventService);
    }

    /**
     * An agent speaking does NOT get its owner's other agents added to the set - only a
     * person does. Otherwise one owner's agents could summon each other without either
     * ever entering the thread.
     */
    @Test
    void anAgentSpeakerDoesNotPullInOwnedAgents() {
        service.recordMentionsInComment(humanPost(), comment("@Mine 出来"),
                AuthorType.AGENT.getCode(), 30L, null);

        verify(agentMapper, never()).selectList(any(Wrapper.class));
        verifyNoInteractions(agentWakeEventService);
    }

    // ========== exclusions ==========

    @Test
    void anAgentNamingItselfIsNotWoken() {
        givenThreadAgents(agent(30L, "Nova"));

        service.recordMentionsInComment(agentPost(30L), comment("正如 @Nova 之前说的"),
                AuthorType.AGENT.getCode(), 30L, null);

        verifyNoInteractions(agentWakeEventService);
    }

    /**
     * One interaction, one wake-up. The agent this comment already queued a
     * COMMENTED / REPLIED event for is skipped: being named inside the reply that is
     * already bringing it back adds nothing it would not have seen.
     */
    @Test
    void theAgentAlreadyWokenByThisSameCommentIsNotWokenAgain() {
        givenThreadAgents(agent(30L, "Nova"));

        service.recordMentionsInComment(agentPost(30L), comment("@Nova 我回你一句"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, 30L);

        verifyNoInteractions(agentWakeEventService);
    }

    /**
     * ...but only that one agent. A third agent named in the same reply still gets its
     * wake-up.
     */
    @Test
    void theDeduplicationOnlyCoversTheAgentThatIsAlreadyBeingWoken() {
        when(commentMapper.findAgentAuthorIdsByPost(eq(POST_ID), anyInt()))
                .thenReturn(List.of(31L));
        when(agentMapper.findAliveAgentsByIds(any()))
                .thenReturn(List.of(agent(30L, "Nova"), agent(31L, "Echo")));

        service.recordMentionsInComment(agentPost(30L), comment("@Nova @Echo 都看看"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, 30L);

        verify(agentWakeEventService, never()).recordMention(eq(30L), anyString(), anyLong(),
                anyString(), anyLong());
        verify(agentWakeEventService).recordMention(eq(31L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    // ========== matching ==========

    /**
     * Case follows the collation of agents.name itself. Matching case sensitively here
     * would mean "@Alice" failing to reach an agent the database considers to be called
     * Alice, with no way for the owner to tell why.
     */
    @Test
    void matchingIgnoresCase() {
        givenThreadAgents(agent(30L, "Nova"));

        service.recordMentionsInComment(agentPost(30L), comment("@NOVA 看一下"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService).recordMention(eq(30L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    /**
     * agents.name accepts any characters, so a name with a space or a full stop in it is
     * a name an owner can really create. Matching against the candidates' own names is
     * what makes those reachable: the fixed alphabet this replaced cut "Dr. Ada" down to
     * "Dr" and then resolved nobody, with no way for the owner to tell why.
     */
    @Test
    void anAgentWhoseNameContainsASpaceOrAFullStopIsStillReached() {
        givenThreadAgents(agent(30L, "Dr. Ada"), agent(31L, "数据 分析师"));

        service.recordMentionsInComment(agentPost(30L), comment("@Dr. Ada 和 @数据 分析师，都看看"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService).recordMention(eq(30L), anyString(), anyLong(),
                anyString(), anyLong());
        verify(agentWakeEventService).recordMention(eq(31L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    /**
     * Two agents in one thread whose names share a prefix. "@Alice2" belongs to exactly
     * one of them, and waking the other would spend its owner's tokens on a conversation
     * nobody addressed to it.
     */
    @Test
    void aNameThatIsOnlyAPrefixOfTheNameWrittenIsNotWoken() {
        when(commentMapper.findAgentAuthorIdsByPost(eq(POST_ID), anyInt()))
                .thenReturn(List.of(30L, 31L));
        when(agentMapper.findAliveAgentsByIds(any()))
                .thenReturn(List.of(agent(30L, "Alice"), agent(31L, "Alice2")));

        service.recordMentionsInComment(humanPost(), comment("@Alice2 你怎么看"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService, never()).recordMention(eq(30L), anyString(), anyLong(),
                anyString(), anyLong());
        verify(agentWakeEventService).recordMention(eq(31L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    /**
     * Names are unique per owner, not globally, so one name can legitimately belong to
     * several agents. All of them answer.
     */
    @Test
    void everyAgentSharingTheNameIsWoken() {
        when(commentMapper.findAgentAuthorIdsByPost(eq(POST_ID), anyInt()))
                .thenReturn(List.of(30L, 31L));
        when(agentMapper.findAliveAgentsByIds(any()))
                .thenReturn(List.of(agent(30L, "Nova"), agent(31L, "Nova")));

        service.recordMentionsInComment(humanPost(), comment("@Nova 出来"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService).recordMention(eq(30L), anyString(), anyLong(),
                anyString(), anyLong());
        verify(agentWakeEventService).recordMention(eq(31L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    /**
     * The same agent can be both the post's author and a commenter under it; it must
     * still be woken exactly once.
     */
    @Test
    void anAgentListedTwiceInTheCandidateSetIsWokenOnce() {
        when(commentMapper.findAgentAuthorIdsByPost(eq(POST_ID), anyInt()))
                .thenReturn(List.of(30L));
        when(agentMapper.findAliveAgentsByIds(any()))
                .thenReturn(List.of(agent(30L, "Nova"), agent(30L, "Nova")));

        service.recordMentionsInComment(agentPost(30L), comment("@Nova 再看看"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(agentWakeEventService).recordMention(eq(30L), anyString(), anyLong(),
                anyString(), anyLong());
    }

    @Test
    void aBodyWithoutAnyMentionCostsNoQueriesAtAll() {
        service.recordMentionsInComment(humanPost(), comment("这个观点我同意"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(commentMapper, never()).findAgentAuthorIdsByPost(anyLong(), anyInt());
        verifyNoInteractions(agentWakeEventService);
    }

    // ========== degradation ==========

    @Test
    void withoutTheWakeQueueSchemaNothingIsReadOrWritten() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        service.recordMentionsInComment(agentPost(30L), comment("@Nova 你怎么看"),
                AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null);

        verify(commentMapper, never()).findAgentAuthorIdsByPost(anyLong(), anyInt());
        verifyNoInteractions(agentWakeEventService);
    }

    /**
     * This runs inside the transaction that created the comment. A mention is worth less
     * than the comment, so nothing here may escape.
     */
    @Test
    void aFailingLookupNeverReachesTheCaller() {
        when(commentMapper.findAgentAuthorIdsByPost(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("comments is gone"));

        assertThatCode(() -> service.recordMentionsInComment(agentPost(30L),
                comment("@Nova 你怎么看"), AuthorType.HUMAN.getCode(), SPEAKER_USER_ID, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(agentWakeEventService);
    }

    @Test
    void aNullPostOrCommentIsIgnored() {
        assertThatCode(() -> {
            service.recordMentionsInPost(null, AuthorType.HUMAN.getCode(), SPEAKER_USER_ID);
            service.recordMentionsInComment(null, comment("@Nova"), AuthorType.HUMAN.getCode(),
                    SPEAKER_USER_ID, null);
            service.recordMentionsInComment(humanPost(), null, AuthorType.HUMAN.getCode(),
                    SPEAKER_USER_ID, null);
        }).doesNotThrowAnyException();

        verifyNoInteractions(agentWakeEventService);
    }

    // ========== helpers ==========

    private void givenThreadAgents(Agent... agents) {
        when(agentMapper.findAliveAgentsByIds(any())).thenReturn(List.of(agents));
    }

    private Post humanPost() {
        return humanPost("原帖内容");
    }

    private Post humanPost(String content) {
        Post post = new Post();
        post.setId(POST_ID);
        post.setAuthorType(AuthorType.HUMAN.getCode());
        post.setAuthorId(SPEAKER_USER_ID);
        post.setContent(content);
        return post;
    }

    private Post agentPost(Long agentId) {
        Post post = new Post();
        post.setId(POST_ID);
        post.setAuthorType(AuthorType.AGENT.getCode());
        post.setAuthorId(agentId);
        post.setContent("Agent 的原帖");
        return post;
    }

    private Comment comment(String content) {
        Comment comment = new Comment();
        comment.setId(COMMENT_ID);
        comment.setPostId(POST_ID);
        comment.setContent(content);
        return comment;
    }

    private Agent agent(Long id, String name) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        return agent;
    }
}
