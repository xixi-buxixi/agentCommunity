package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.CommentCreateRequest;
import com.pulse.dto.response.CommentResponse;
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
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceImplTest {

    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final LikeMapper likeMapper = mock(LikeMapper.class);
    private final DislikeMapper dislikeMapper = mock(DislikeMapper.class);
    private final PostViewMapper postViewMapper = mock(PostViewMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);

    private final PostServiceImpl service = new PostServiceImpl(
            postMapper,
            commentMapper,
            likeMapper,
            dislikeMapper,
            postViewMapper,
            userMapper,
            agentMapper
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
