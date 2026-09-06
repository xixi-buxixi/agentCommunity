package com.pulse.scheduler;

import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentActionOutcome;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentLog;
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
import com.pulse.service.AgentWakeEventService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the ids the executor hands back for the memory hook. A card whose
 * source_id is null (or whose action silently failed) is worse than no card: it
 * claims the agent did something the community cannot verify.
 */
class AgentActionExecutorTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final LikeMapper likeMapper = mock(LikeMapper.class);
    private final DislikeMapper dislikeMapper = mock(DislikeMapper.class);
    private final AgentBountyExecutor agentBountyExecutor = mock(AgentBountyExecutor.class);
    private final AgentWakeEventService agentWakeEventService = mock(AgentWakeEventService.class);

    private final AgentActionExecutor executor = new AgentActionExecutor(
            agentMapper, postMapper, commentMapper, agentLogMapper,
            likeMapper, dislikeMapper, agentBountyExecutor, agentWakeEventService);

    @Test
    void postOutcomeCarriesTheNewPostId() {
        when(postMapper.insert(any(Post.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Post.class).setId(1234L);
            return 1;
        });

        List<AgentActionOutcome> outcomes = executor.applyDecisions(agent(),
                List.of(decision(ActionType.POST, null, "小模型才是未来")), 500);

        assertThat(outcomes).hasSize(1);
        AgentActionOutcome outcome = outcomes.get(0);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getSourceType()).isEqualTo("POST");
        assertThat(outcome.getSourceId()).isEqualTo(1234L);
        assertThat(outcome.getSelfContent()).isEqualTo("小模型才是未来");
        verify(agentLogMapper).insert(any(AgentLog.class));
    }

    @Test
    void replyOutcomeCarriesCommentIdAndTargetAuthor() {
        Post target = new Post();
        target.setId(88L);
        target.setAuthorId(55L);
        target.setAuthorType(AuthorType.HUMAN.getCode());
        target.setContent("压缩就是未来");
        when(postMapper.selectById(88L)).thenReturn(target);
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(0);
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(3001L);
            return 1;
        });

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(decision(ActionType.REPLY, 88L, "我不同意")), 500).get(0);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getSourceType()).isEqualTo("COMMENT");
        assertThat(outcome.getSourceId()).isEqualTo(3001L);
        assertThat(outcome.getTargetPostId()).isEqualTo(88L);
        assertThat(outcome.getTargetAuthorType()).isEqualTo(AuthorType.HUMAN.getCode());
        assertThat(outcome.getTargetAuthorId()).isEqualTo(55L);
    }

    @Test
    void skippedDuplicateReplyIsReportedAsFailureSoNoCardIsWritten() {
        Post target = new Post();
        target.setId(88L);
        target.setAuthorId(55L);
        target.setAuthorType(AuthorType.HUMAN.getCode());
        when(postMapper.selectById(88L)).thenReturn(target);
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(1);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(decision(ActionType.REPLY, 88L, "重复回复")), 500).get(0);

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getSourceId()).isNull();
    }

    @Test
    void ignoreProducesASuccessfulOutcomeWithoutASource() {
        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(decision(ActionType.IGNORE, null, null)), 500).get(0);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getSourceType()).isNull();
        assertThat(outcome.getSourceId()).isNull();
    }

    /**
     * The closed loop the wake queue exists for: an agent woken *because* somebody replied
     * under its post must be able to answer there. It has commented on that post before by
     * definition, so the duplicate-reply guard has to step aside for exactly that post.
     */
    @Test
    void anAgentMayReplyAgainOnThePostThatTriggeredItsWake() {
        Post target = new Post();
        target.setId(88L);
        target.setAuthorId(1L);
        target.setAuthorType(AuthorType.AGENT.getCode());
        target.setContent("我的原帖");
        when(postMapper.selectById(88L)).thenReturn(target);
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(1);
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(3002L);
            return 1;
        });

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(decision(ActionType.REPLY, 88L, "谢谢回应")), 500, java.util.Set.of(88L)).get(0);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getSourceId()).isEqualTo(3002L);
    }

    @Test
    void theDuplicateGuardStillHoldsForEveryOtherPost() {
        Post target = new Post();
        target.setId(99L);
        target.setAuthorId(55L);
        target.setAuthorType(AuthorType.HUMAN.getCode());
        when(postMapper.selectById(99L)).thenReturn(target);
        when(commentMapper.countAgentCommentsOnPost(1L, 99L)).thenReturn(1);

        AgentActionOutcome outcome = executor.applyDecisions(agent(),
                List.of(decision(ActionType.REPLY, 99L, "再说一句")), 500, java.util.Set.of(88L)).get(0);

        assertThat(outcome.isSuccess()).isFalse();
    }

    /**
     * Agents have to be able to wake each other, or two agents can never hold a
     * conversation - only humans could ever trigger a reply.
     */
    @Test
    void replyingToAnotherAgentsPostQueuesAWakeEventForThatAgent() {
        Post target = new Post();
        target.setId(88L);
        target.setAuthorId(77L);
        target.setAuthorType(AuthorType.AGENT.getCode());
        target.setContent("别的 Agent 的帖子");
        when(postMapper.selectById(88L)).thenReturn(target);
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(0);
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(3003L);
            return 1;
        });

        executor.applyDecisions(agent(), List.of(decision(ActionType.REPLY, 88L, "我有不同看法")), 500);

        verify(agentWakeEventService).recordCommentOnAgentPost(77L, 88L, 3003L,
                AuthorType.AGENT.getCode(), 1L);
    }

    @Test
    void replyingToAHumanPostQueuesNothing() {
        Post target = new Post();
        target.setId(88L);
        target.setAuthorId(55L);
        target.setAuthorType(AuthorType.HUMAN.getCode());
        when(postMapper.selectById(88L)).thenReturn(target);
        when(commentMapper.countAgentCommentsOnPost(1L, 88L)).thenReturn(0);
        when(commentMapper.insert(any(Comment.class))).thenReturn(1);

        executor.applyDecisions(agent(), List.of(decision(ActionType.REPLY, 88L, "普通回复")), 500);

        org.mockito.Mockito.verifyNoInteractions(agentWakeEventService);
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setOwnerId(7L);
        agent.setName("Pulse");
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        return agent;
    }

    private AgentActionDecision decision(ActionType action, Long targetPostId, String content) {
        return AgentActionDecision.builder()
                .action(action)
                .targetPostId(targetPostId)
                .content(content)
                .build();
    }
}
