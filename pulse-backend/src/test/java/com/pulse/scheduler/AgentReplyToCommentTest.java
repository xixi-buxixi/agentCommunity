package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentActionOutcome;
import com.pulse.entity.Agent;
import com.pulse.entity.Comment;
import com.pulse.entity.Post;
import com.pulse.enums.ActionType;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.DislikeMapper;
import com.pulse.mapper.LikeMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.service.AgentMentionService;
import com.pulse.service.AgentWakeEventService;
import com.pulse.service.NotificationService;
import com.pulse.service.impl.PostServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Replying to one specific comment.
 *
 * Two things are being protected here. The first is the comment tree: an agent reply
 * has to produce the same parent/root/depth triple a human reply does, or the frontend
 * renders a thread whose shape depends on who wrote the leaf. The second is that a bad
 * pointer never costs the sentence - every rejected target degrades to a top-level
 * comment, because the agent had something to say either way.
 */
class AgentReplyToCommentTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final LikeMapper likeMapper = mock(LikeMapper.class);
    private final DislikeMapper dislikeMapper = mock(DislikeMapper.class);
    private final AgentBountyExecutor agentBountyExecutor = mock(AgentBountyExecutor.class);
    private final AgentWakeEventService agentWakeEventService = mock(AgentWakeEventService.class);
    private final AgentMentionService agentMentionService = mock(AgentMentionService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final AgentActionExecutor executor = new AgentActionExecutor(
            agentMapper, postMapper, commentMapper, agentLogMapper,
            likeMapper, dislikeMapper, agentBountyExecutor, agentWakeEventService,
            agentMentionService, notificationService, schemaCapabilities);

    @BeforeEach
    void theInsertAssignsAnId() {
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(3001L);
            return 1;
        });
    }

    // ========== The comment tree ==========

    @Test
    void aTargetedReplyCarriesTheSameHierarchyAHumanReplyWould() {
        humanPost(88L, 55L);
        Comment parent = comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0);
        when(commentMapper.selectById(500L)).thenReturn(parent);

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "我不同意")), 500);

        Comment written = capturedComment();
        assertThat(written.getParentCommentId()).isEqualTo(500L);
        // the parent is itself top level, so it becomes the root of the thread
        assertThat(written.getRootCommentId()).isEqualTo(500L);
        assertThat(written.getReplyDepth()).isEqualTo(1);
    }

    @Test
    void aReplyDeeperInTheThreadKeepsTheExistingRoot() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(501L))
                .thenReturn(comment(501L, 88L, AuthorType.HUMAN.getCode(), 7L, 500L, 500L, 1));

        executor.applyDecisions(agent(), List.of(reply(88L, 501L, "接着说")), 500);

        Comment written = capturedComment();
        assertThat(written.getRootCommentId()).isEqualTo(500L);
        assertThat(written.getReplyDepth()).isEqualTo(2);
    }

    @Test
    void aReplyWithoutACommentTargetIsStillTopLevel() {
        humanPost(88L, 55L);

        executor.applyDecisions(agent(), List.of(reply(88L, null, "顶层评论")), 500);

        Comment written = capturedComment();
        assertThat(written.getParentCommentId()).isNull();
        assertThat(written.getRootCommentId()).isNull();
        verify(commentMapper, never()).countAgentRepliesToComment(any(), any());
    }

    // ========== Degradation ==========

    @Test
    void anUnknownCommentIdDegradesToATopLevelComment() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(999L)).thenReturn(null);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(reply(88L, 999L, "回复")), 500).get(0);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(capturedComment().getParentCommentId()).isNull();
    }

    @Test
    void aDeletedCommentDegradesToATopLevelComment() {
        humanPost(88L, 55L);
        Comment deleted = comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0);
        deleted.setDeleted(1);
        when(commentMapper.selectById(500L)).thenReturn(deleted);

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "回复")), 500);

        assertThat(capturedComment().getParentCommentId()).isNull();
    }

    /**
     * The pair is what makes a targeted reply verifiable. A comment from another post
     * would attach this reply to a thread the agent never read.
     */
    @Test
    void aCommentFromAnotherPostDegradesToATopLevelComment() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 77L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "回复")), 500);

        assertThat(capturedComment().getParentCommentId()).isNull();
    }

    @Test
    void theDepthCeilingIsTheSameOneTheHumanPathEnforces() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(503L)).thenReturn(comment(503L, 88L,
                AuthorType.HUMAN.getCode(), 7L, 502L, 500L, PostServiceImpl.MAX_REPLY_DEPTH));

        executor.applyDecisions(agent(), List.of(reply(88L, 503L, "再深一层")), 500);

        assertThat(capturedComment().getParentCommentId()).isNull();
    }

    @Test
    void theLastAllowedDepthIsStillAccepted() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(502L)).thenReturn(comment(502L, 88L,
                AuthorType.HUMAN.getCode(), 7L, 501L, 500L, PostServiceImpl.MAX_REPLY_DEPTH - 1));

        executor.applyDecisions(agent(), List.of(reply(88L, 502L, "刚好到底")), 500);

        assertThat(capturedComment().getReplyDepth()).isEqualTo(PostServiceImpl.MAX_REPLY_DEPTH);
    }

    /**
     * A self-reply produces no wake event and no notification, so it is a thread the
     * agent would be talking to itself in.
     */
    @Test
    void anAgentMayNotAnswerItsOwnComment() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.AGENT.getCode(), 1L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "自问自答")), 500);

        assertThat(capturedComment().getParentCommentId()).isNull();
    }

    @Test
    void anotherAgentsCommentIsAValidTarget() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.AGENT.getCode(), 77L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "我有不同看法")), 500);

        assertThat(capturedComment().getParentCommentId()).isEqualTo(500L);
    }

    /**
     * A degraded reply falls back into the post-level guard, which is the right answer:
     * without a usable target it is an ordinary top-level comment and gets the ordinary
     * rule.
     */
    @Test
    void aDegradedReplyIsStillSubjectToTheOneCommentPerPostGuard() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(999L)).thenReturn(null);
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(1);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(reply(88L, 999L, "回复")), 500).get(0);

        assertThat(outcome.isSuccess()).isFalse();
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    // ========== Duplicate rules ==========

    /**
     * The relaxation the feature needs: "one comment per post" was a proxy for "do not
     * repeat yourself", and it stops being one as soon as an agent can answer individual
     * people in a thread.
     */
    @Test
    void aTargetedReplyIsNotBlockedByAnEarlierCommentOnTheSamePost() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(3);
        when(commentMapper.countAgentRepliesToComment(1L, 500L)).thenReturn(0);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(reply(88L, 500L, "回答你这一条")), 500).get(0);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getSourceId()).isEqualTo(3001L);
    }

    @Test
    void theSameCommentIsNeverAnsweredTwice() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));
        when(commentMapper.countAgentRepliesToComment(1L, 500L)).thenReturn(1);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(reply(88L, 500L, "再说一遍")), 500).get(0);

        assertThat(outcome.isSuccess()).isFalse();
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void aTargetedReplyDoesNotConsultThePostLevelGuardAtAll() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "回复")), 500, Set.of());

        verify(commentMapper, never()).countAgentCommentsOnPost(any(), any());
    }

    // ========== Notifications and wake events ==========

    /**
     * AGENT_REPLIED_COMMENT had no producer until now: every agent comment was top level,
     * so only the post branch could fire.
     */
    @Test
    void answeringAHumansCommentNotifiesThatHuman() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "我不同意")), 500);

        verify(notificationService).notifyReplyToComment(7L, AuthorType.AGENT.getCode(), 1L, 88L,
                "我不同意");
        // the post's author is NOT notified as well: the person answered is the one who
        // should come back, exactly as on the human comment path
        verify(notificationService, never()).notifyCommentOnPost(any(), any(), any(), any(), any());
    }

    @Test
    void answeringAnotherAgentsCommentQueuesAReplyWakeEvent() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.AGENT.getCode(), 77L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "我有不同看法")), 500);

        // same producer the human path uses, so the dedup key keeps its existing shape
        verify(agentWakeEventService).recordReplyToAgentComment(77L, 500L, 3001L,
                AuthorType.AGENT.getCode(), 1L);
        verifyNoInteractions(notificationService);
    }

    /**
     * The post's author is not woken by a targeted reply. Their post was not what was
     * answered, and waking them anyway would make one reply cost two wake-ups.
     */
    @Test
    void answeringACommentUnderAnAgentsPostDoesNotAlsoWakeThePostAuthor() {
        Post target = new Post();
        target.setId(88L);
        target.setAuthorId(77L);
        target.setAuthorType(AuthorType.AGENT.getCode());
        when(postMapper.selectById(88L)).thenReturn(target);
        when(commentMapper.selectById(500L))
                .thenReturn(comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));

        executor.applyDecisions(agent(), List.of(reply(88L, 500L, "回答这条评论")), 500);

        verify(agentWakeEventService, never()).recordCommentOnAgentPost(any(), any(), any(),
                any(), any());
        verify(notificationService).notifyReplyToComment(7L, AuthorType.AGENT.getCode(), 1L, 88L,
                "回答这条评论");
    }

    /**
     * The memory card has to record who was actually answered. A card saying "I answered
     * the post author" about a reply to somebody else's comment is a false memory, and
     * memory cards are what the persona is distilled from.
     */
    @Test
    void theOutcomePointsAtTheCommentAuthorNotThePostAuthor() {
        humanPost(88L, 55L);
        when(commentMapper.selectById(500L)).thenReturn(
                comment(500L, 88L, AuthorType.HUMAN.getCode(), 7L, null, null, 0));

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(reply(88L, 500L, "我不同意")), 500).get(0);

        assertThat(outcome.getTargetPostId()).isEqualTo(88L);
        assertThat(outcome.getTargetAuthorId()).isEqualTo(7L);
        assertThat(outcome.getTargetAuthorType()).isEqualTo(AuthorType.HUMAN.getCode());
        assertThat(outcome.getTargetSummary()).isEqualTo("父评论正文");
    }

    @Test
    void aTopLevelReplyStillPointsAtThePostAuthor() {
        humanPost(88L, 55L);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(reply(88L, null, "顶层评论")), 500).get(0);

        assertThat(outcome.getTargetAuthorId()).isEqualTo(55L);
        assertThat(outcome.getTargetSummary()).isEqualTo("帖子正文");
    }

    // ========== helpers ==========

    private Comment capturedComment() {
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void humanPost(Long postId, Long authorId) {
        Post target = new Post();
        target.setId(postId);
        target.setAuthorId(authorId);
        target.setAuthorType(AuthorType.HUMAN.getCode());
        target.setContent("帖子正文");
        when(postMapper.selectById(postId)).thenReturn(target);
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setOwnerId(9L);
        agent.setName("Pulse");
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        return agent;
    }

    private AgentActionDecision reply(Long postId, Long commentId, String content) {
        return AgentActionDecision.builder()
                .action(ActionType.REPLY)
                .targetPostId(postId)
                .targetCommentId(commentId)
                .content(content)
                .build();
    }

    private Comment comment(Long id, Long postId, String authorType, Long authorId,
                            Long parentId, Long rootId, Integer depth) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setAuthorType(authorType);
        comment.setAuthorId(authorId);
        comment.setParentCommentId(parentId);
        comment.setRootCommentId(rootId);
        comment.setReplyDepth(depth);
        comment.setContent("父评论正文");
        comment.setDeleted(0);
        return comment;
    }
}
