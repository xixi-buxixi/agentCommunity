package com.pulse.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.client.LLMClient;
import com.pulse.entity.Agent;
import com.pulse.entity.Post;
import com.pulse.enums.AuthorType;
import com.pulse.enums.PostTag;
import com.pulse.mapper.*;
import com.pulse.service.BountyService;
import com.pulse.service.PostTagClassifier;
import com.pulse.util.AesUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFeedSelectionTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final PostViewMapper postViewMapper = mock(PostViewMapper.class);
    private final LikeMapper likeMapper = mock(LikeMapper.class);
    private final DislikeMapper dislikeMapper = mock(DislikeMapper.class);

    private final AgentLoopScheduler scheduler = new AgentLoopScheduler(
            agentMapper,
            postMapper,
            commentMapper,
            agentLogMapper,
            postViewMapper,
            likeMapper,
            dislikeMapper,
            mock(LLMClient.class),
            mock(BountyService.class),
            mock(AesUtil.class),
            new ObjectMapper(),
            new PostTagClassifier()
    );

    @Test
    void blendsInterestHotAndLatestWithoutDuplicatePosts() throws Exception {
        Agent agent = new Agent();
        agent.setId(7L);
        agent.setOwnerId(1L);
        agent.setSystemPrompt("我关注 AI Agent 和软件工程");

        Post interest = post(10L, PostTag.AI_FRONTIER);
        Post duplicate = post(20L, PostTag.SOFTWARE_ENGINEERING);
        Post hot = post(30L, PostTag.COMMUNITY_CHAT);
        Post latest = post(40L, PostTag.PRODUCT_IDEA);

        when(postMapper.findInterestPostsForAgent(7L, List.of("AI_FRONTIER", "SOFTWARE_ENGINEERING"), 5))
                .thenReturn(List.of(interest, duplicate));
        when(postMapper.findHotPostsForAgent(7L, 5)).thenReturn(List.of(duplicate, hot));
        when(postMapper.findLatestUnviewedPostsForAgent(7L, 5)).thenReturn(List.of(latest));

        Method method = AgentLoopScheduler.class.getDeclaredMethod("selectPostsForAgent", Agent.class, int.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Post> selected = (List<Post>) method.invoke(scheduler, agent, 5);

        assertThat(selected).extracting(Post::getId)
                .containsExactly(10L, 20L, 30L, 40L);
    }

    private Post post(Long id, PostTag tag) {
        Post post = new Post();
        post.setId(id);
        post.setAuthorId(99L);
        post.setAuthorType(AuthorType.HUMAN.getCode());
        post.setContent("Post " + id);
        post.setTagCode(tag.getCode());
        post.setDeleted(0);
        return post;
    }
}
