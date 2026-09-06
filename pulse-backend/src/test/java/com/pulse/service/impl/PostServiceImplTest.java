package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.CommentCreateRequest;
import com.pulse.dto.response.CommentResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.Comment;
import com.pulse.entity.Post;
import com.pulse.entity.User;
import com.pulse.enums.AuthorType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.DislikeMapper;
import com.pulse.mapper.LikeMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.PostViewMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.AgentWakeEventService;
import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Notification;
import com.pulse.mapper.NotificationMapper;
import com.pulse.service.NotificationService;
import com.pulse.service.support.AuthorResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostServiceImplTest {

    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final LikeMapper likeMapper = mock(LikeMapper.class);
    private final DislikeMapper dislikeMapper = mock(DislikeMapper.class);
    private final PostViewMapper postViewMapper = mock(PostViewMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AuthorResolver authorResolver = new AuthorResolver(userMapper, agentMapper);
    private final AgentWakeEventService agentWakeEventService = mock(AgentWakeEventService.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    private final PostServiceImpl service = new PostServiceImpl(
            postMapper,
            commentMapper,
            likeMapper,
            dislikeMapper,
            postViewMapper,
            userMapper,
            agentMapper,
            authorResolver,
            agentWakeEventService,
            notificationService
    );

    @Test
    void postAuthorCannotCreateTopLevelCommentOnOwnPost() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(10L)).thenReturn(user(10L, "alice"));

        CommentCreateRequest request = commentRequest("I should reply instead", null);

        assertThatThrownBy(() -> service.createComment(10L, 88L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SELF_POST_DIRECT_COMMENT_FORBIDDEN.getCode());
    }

    @Test
    void postAuthorCanReplyToAnotherUsersComment() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(10L)).thenReturn(user(10L, "alice"));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        Comment parent = topLevelComment(5L, 88L, 20L);
        when(commentMapper.selectById(5L)).thenReturn(parent);

        CommentCreateRequest request = commentRequest("Thanks for the angle.", 5L);
        CommentResponse response = service.createComment(10L, 88L, request);

        assertThat(response.getParentCommentId()).isEqualTo(5L);
        assertThat(response.getRootCommentId()).isEqualTo(5L);
        assertThat(response.getReplyDepth()).isEqualTo(1);
        verify(commentMapper).insert(any(Comment.class));
        verify(postMapper).incrementCommentCount(88L);
    }

    @Test
    void nonAuthorCanCreateTopLevelComment() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));

        CommentCreateRequest request = commentRequest("I have a different view.", null);
        CommentResponse response = service.createComment(20L, 88L, request);

        assertThat(response.getParentCommentId()).isNull();
        assertThat(response.getRootCommentId()).isNull();
        assertThat(response.getReplyDepth()).isZero();
        verify(commentMapper).insert(any(Comment.class));
        verify(postMapper).incrementCommentCount(88L);
    }

    @Test
    void userCannotReplyToOwnComment() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        when(commentMapper.selectById(5L)).thenReturn(topLevelComment(5L, 88L, 20L));

        CommentCreateRequest request = commentRequest("Adding one more note.", 5L);

        assertThatThrownBy(() -> service.createComment(20L, 88L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SELF_COMMENT_REPLY_FORBIDDEN.getCode());
    }

    @Test
    void fourthLevelReplyIsRejected() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(30L)).thenReturn(user(30L, "cara"));
        Comment thirdLevelReply = replyComment(9L, 88L, 20L, 8L, 5L, 3);
        when(commentMapper.selectById(9L)).thenReturn(thirdLevelReply);

        CommentCreateRequest request = commentRequest("Too deep.", 9L);

        assertThatThrownBy(() -> service.createComment(30L, 88L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.COMMENT_REPLY_DEPTH_EXCEEDED.getCode());
    }

    @Test
    void commentsAreReturnedAsTreeWithReplies() {
        Comment root = topLevelComment(5L, 88L, 20L);
        Comment reply = replyComment(6L, 88L, 10L, 5L, 5L, 1);

        Page<Comment> page = new Page<>(1, 20, 1);
        page.setRecords(java.util.List.of(root));

        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(commentMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(commentMapper.findRepliesByRootIds(java.util.List.of(5L))).thenReturn(java.util.List.of(reply));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        when(userMapper.selectById(10L)).thenReturn(user(10L, "alice"));

        Page<CommentResponse> response = service.getComments(88L, 1, 20);

        assertThat(response.getRecords()).hasSize(1);
        CommentResponse rootResponse = response.getRecords().get(0);
        assertThat(rootResponse.getReplyDepth()).isZero();
        assertThat(rootResponse.getReplies()).hasSize(1);
        assertThat(rootResponse.getReplies().get(0).getParentCommentId()).isEqualTo(5L);
        assertThat(rootResponse.getReplies().get(0).getReplyDepth()).isEqualTo(1);
    }

    // ========== Wake queue enqueue (phase 3) ==========

    /**
     * A comment on an agent's post should bring that agent back to answer, and the
     * notification must name the comment so the same one cannot wake it twice.
     */
    @Test
    void commentingOnAnAgentPostQueuesAWakeEventForThatAgent() {
        Post agentPost = agentPost(88L, 30L);
        when(postMapper.selectById(88L)).thenReturn(agentPost);
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(900L);
            return 1;
        });

        service.createComment(20L, 88L, commentRequest("有意思的观点", null));

        verify(agentWakeEventService).recordCommentOnAgentPost(30L, 88L, 900L,
                AuthorType.HUMAN.getCode(), 20L);
    }

    /**
     * In a thread the person whose words were answered is the one who should come back -
     * not necessarily the post owner.
     */
    @Test
    void replyingToAnAgentCommentQueuesAWakeEventForTheCommentAuthor() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        Comment agentComment = topLevelComment(5L, 88L, 30L);
        agentComment.setAuthorType(AuthorType.AGENT.getCode());
        when(commentMapper.selectById(5L)).thenReturn(agentComment);
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(901L);
            return 1;
        });

        service.createComment(20L, 88L, commentRequest("我不同意", 5L));

        verify(agentWakeEventService).recordReplyToAgentComment(30L, 5L, 901L,
                AuthorType.HUMAN.getCode(), 20L);
    }

    @Test
    void commentingOnAHumanPostQueuesNothing() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));

        service.createComment(20L, 88L, commentRequest("普通评论", null));

        verifyNoInteractions(agentWakeEventService);
    }

    // ========== Notification producers ==========

    /**
     * The mirror of the wake queue for the human side: a person whose post was
     * commented on has no scheduler bringing them back, so this row is the only thing
     * that tells them.
     */
    @Test
    void commentingOnAHumanPostNotifiesThePostAuthor() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));

        service.createComment(20L, 88L, commentRequest("我有不同看法", null));

        verify(notificationService).notifyCommentOnPost(10L, AuthorType.HUMAN.getCode(), 20L,
                88L, "我有不同看法");
    }

    @Test
    void replyingToAHumanCommentNotifiesTheCommentAuthor() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        when(commentMapper.selectById(5L)).thenReturn(topLevelComment(5L, 88L, 10L));

        service.createComment(20L, 88L, commentRequest("同意你的说法", 5L));

        verify(notificationService).notifyReplyToComment(10L, AuthorType.HUMAN.getCode(), 20L,
                88L, "同意你的说法");
    }

    /**
     * The owner of an agent has no other way of hearing that a person walked up to it.
     *
     * The wake event that the same comment produces goes to the AGENT, and the agent's
     * answer is delivered to whoever it is replying to - here the agent's own post. So
     * the owner used to receive nothing at all, not the duplicate the old comment
     * claimed to be avoiding.
     */
    @Test
    void commentingOnAnAgentPostNotifiesTheOwner() {
        when(postMapper.selectById(88L)).thenReturn(agentPost(88L, 30L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        when(agentMapper.selectById(30L)).thenReturn(agent(30L, "Nova", 10L));

        service.createComment(20L, 88L, commentRequest("有意思", null));

        verify(notificationService).notifyCommentOnAgentPost(10L, 20L, 88L, "Nova", "有意思");
    }

    @Test
    void replyingToAnAgentCommentNotifiesTheOwner() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        Comment agentComment = topLevelComment(5L, 88L, 30L);
        agentComment.setAuthorType(AuthorType.AGENT.getCode());
        when(commentMapper.selectById(5L)).thenReturn(agentComment);
        when(agentMapper.selectById(30L)).thenReturn(agent(30L, "Nova", 11L));

        service.createComment(20L, 88L, commentRequest("我不同意", 5L));

        verify(notificationService).notifyReplyToAgentComment(11L, 20L, 88L, "Nova", "我不同意");
    }

    /**
     * An agent that no longer resolves produces no notification rather than a null
     * recipient - the comment itself still stands.
     */
    @Test
    void anUnresolvableAgentAuthorNotifiesNobody() {
        when(postMapper.selectById(88L)).thenReturn(agentPost(88L, 30L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        when(agentMapper.selectById(30L)).thenReturn(null);

        service.createComment(20L, 88L, commentRequest("有意思", null));

        verifyNoInteractions(notificationService);
    }

    /**
     * The owner replying to their own agent's comment writes nothing.
     *
     * Run against the REAL notification service, because the guard that drops it is the
     * shared "never tell somebody about their own action" rule inside it - the point of
     * the test is that the new call site is covered by that rule rather than having to
     * restate it.
     *
     * (The top-level case cannot arise: commenting directly on your own agent's post is
     * refused upstream with SELF_POST_DIRECT_COMMENT_FORBIDDEN.)
     */
    @Test
    void theOwnerReplyingToTheirOwnAgentIsNotNotified() {
        NotificationMapper notificationMapper = mock(NotificationMapper.class);
        SchemaCapabilities capabilities = mock(SchemaCapabilities.class);
        when(capabilities.isNotificationsTable()).thenReturn(true);
        PostServiceImpl withRealNotifications = new PostServiceImpl(
                postMapper, commentMapper, likeMapper, dislikeMapper, postViewMapper,
                userMapper, agentMapper, authorResolver, agentWakeEventService,
                new NotificationServiceImpl(notificationMapper, capabilities, authorResolver));

        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));
        Comment agentComment = topLevelComment(5L, 88L, 30L);
        agentComment.setAuthorType(AuthorType.AGENT.getCode());
        when(commentMapper.selectById(5L)).thenReturn(agentComment);
        // The agent belongs to the very person writing the reply
        when(agentMapper.selectById(30L)).thenReturn(agent(30L, "Nova", 20L));

        withRealNotifications.createComment(20L, 88L, commentRequest("我自己接一句", 5L));

        verifyNoInteractions(notificationMapper);
    }

    /**
     * Commenting on your own post is refused outright, and replying to your own comment
     * too, so a person can never be notified about their own words.
     */
    @Test
    void selfInteractionsNeverReachTheNotificationService() {
        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(10L)).thenReturn(user(10L, "alice"));

        assertThatThrownBy(() -> service.createComment(10L, 88L, commentRequest("自己评论", null)))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(notificationService);
    }

    /**
     * End to end with the REAL notification service and a database that rejects the
     * insert: the comment and its counter must still stand. A notification outage may
     * not become a comment outage.
     */
    @Test
    void aFailingNotificationWriteDoesNotBreakTheComment() {
        NotificationMapper failingMapper = mock(NotificationMapper.class);
        when(failingMapper.insert(any(Notification.class)))
                .thenThrow(new RuntimeException("notifications is gone"));
        SchemaCapabilities capabilities = mock(SchemaCapabilities.class);
        when(capabilities.isNotificationsTable()).thenReturn(true);

        PostServiceImpl withRealNotifications = new PostServiceImpl(
                postMapper, commentMapper, likeMapper, dislikeMapper, postViewMapper,
                userMapper, agentMapper, authorResolver, agentWakeEventService,
                new NotificationServiceImpl(failingMapper, capabilities, authorResolver));

        when(postMapper.selectById(88L)).thenReturn(humanPost(88L, 10L));
        when(userMapper.selectById(20L)).thenReturn(user(20L, "bob"));

        CommentResponse response = withRealNotifications.createComment(20L, 88L,
                commentRequest("普通评论", null));

        assertThat(response).isNotNull();
        verify(commentMapper).insert(any(Comment.class));
        verify(postMapper).incrementCommentCount(88L);
    }

    private Agent agent(Long id, String name, Long ownerId) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        agent.setOwnerId(ownerId);
        return agent;
    }

    private Post agentPost(Long id, Long authorId) {
        Post post = humanPost(id, authorId);
        post.setAuthorType(AuthorType.AGENT.getCode());
        return post;
    }

    private CommentCreateRequest commentRequest(String content, Long parentCommentId) {
        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent(content);
        request.setParentCommentId(parentCommentId);
        return request;
    }

    private Post humanPost(Long id, Long authorId) {
        Post post = new Post();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setAuthorType(AuthorType.HUMAN.getCode());
        post.setContent("Post content");
        post.setCommentCount(0);
        post.setIsSystemMessage(false);
        post.setDeleted(0);
        return post;
    }

    private Comment topLevelComment(Long id, Long postId, Long authorId) {
        return replyComment(id, postId, authorId, null, null, 0);
    }

    private Comment replyComment(Long id, Long postId, Long authorId, Long parentId, Long rootId, Integer depth) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setAuthorId(authorId);
        comment.setAuthorType(AuthorType.HUMAN.getCode());
        comment.setContent("Comment " + id);
        comment.setParentCommentId(parentId);
        comment.setRootCommentId(rootId);
        comment.setReplyDepth(depth);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setDeleted(0);
        return comment;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
