package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.response.NotificationResponse;
import com.pulse.entity.Notification;
import com.pulse.entity.User;
import com.pulse.enums.AuthorType;
import com.pulse.enums.NotificationLinkType;
import com.pulse.enums.NotificationType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.NotificationMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.support.AuthorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The notification centre has two halves with opposite failure rules, and both are
 * tested here as such:
 *
 * - the producers run inside somebody else's transaction, so they must never throw,
 *   never notify a user about their own action, and write nothing when the table is
 *   absent;
 * - the read half must report a missing table instead of answering "you have no
 *   notifications", and must never be able to address another user's inbox.
 */
class NotificationServiceImplTest {

    private static final Long RECIPIENT_ID = 7L;
    private static final Long ACTOR_ID = 9L;
    private static final Long AGENT_ID = 42L;

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AuthorResolver authorResolver = new AuthorResolver(userMapper, agentMapper);

    private final NotificationServiceImpl service =
            new NotificationServiceImpl(notificationMapper, schemaCapabilities, authorResolver);

    @BeforeEach
    void schemaPresent() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(true);
    }

    // ========== Reading ==========

    @Test
    void listingReturnsMappedRowsWithTheActorNameResolved() {
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(ACTOR_ID, "alice")));
        Page<Notification> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(row(1L, NotificationType.HUMAN_REPLIED_POST, 0)));
        when(notificationMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        Page<NotificationResponse> result =
                service.getNotifications(RECIPIENT_ID, false, 1, 20);

        assertThat(result.getTotal()).isEqualTo(1);
        NotificationResponse item = result.getRecords().get(0);
        assertThat(item.getId()).isEqualTo(1L);
        assertThat(item.getType()).isEqualTo("HUMAN_REPLIED_POST");
        assertThat(item.getTypeText()).isEqualTo("有人评论了你的帖子");
        assertThat(item.getLinkType()).isEqualTo("POST");
        assertThat(item.getLinkId()).isEqualTo(88L);
        assertThat(item.getActorType()).isEqualTo("HUMAN");
        assertThat(item.getActorName()).isEqualTo("alice");
        assertThat(item.getIsRead()).isFalse();
        assertThat(item.getCreatedAt()).isNotNull();
    }

    /**
     * The recipient never comes from the request, so there is no way to page through
     * somebody else's inbox. The predicate is what enforces it.
     */
    @Test
    void listingIsAlwaysFilteredToTheCallersOwnRows() {
        stubEmptyPage();

        service.getNotifications(RECIPIENT_ID, false, 1, 20);

        LambdaQueryWrapper<Notification> wrapper = capturedWrapper();
        // Normalized: the column name reaches the segment as recipient_user_id or
        // recipientUserId depending on the naming strategy, and neither form is the
        // point of this test.
        String predicate = normalize(wrapper.getSqlSegment());
        assertThat(predicate).contains("recipientuserid");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(RECIPIENT_ID);
        // no unread predicate when the caller did not ask for one
        assertThat(predicate).doesNotContain("isread");
    }

    @Test
    void unreadOnlyAddsTheUnreadPredicate() {
        stubEmptyPage();

        service.getNotifications(RECIPIENT_ID, true, 1, 20);

        assertThat(normalize(capturedWrapper().getSqlSegment())).contains("isread");
    }

    /**
     * MyBatis Plus reads a non-positive page size as "no pagination", which would dump
     * the whole inbox in one response.
     */
    @Test
    void illegalPagingParametersAreClamped() {
        stubEmptyPage();

        service.getNotifications(RECIPIENT_ID, false, 0, -1);
        service.getNotifications(RECIPIENT_ID, false, -3, 9999);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<Notification>> captor = ArgumentCaptor.forClass(Page.class);
        verify(notificationMapper, times(2)).selectPage(captor.capture(), any(Wrapper.class));

        assertThat(captor.getAllValues().get(0).getCurrent()).isEqualTo(1);
        assertThat(captor.getAllValues().get(0).getSize()).isEqualTo(1);
        assertThat(captor.getAllValues().get(1).getCurrent()).isEqualTo(1);
        assertThat(captor.getAllValues().get(1).getSize()).isEqualTo(50);
    }

    @Test
    void unreadCountIsScopedToTheCaller() {
        when(notificationMapper.countUnread(RECIPIENT_ID)).thenReturn(3L);

        assertThat(service.getUnreadCount(RECIPIENT_ID)).isEqualTo(3L);
    }

    // ========== Marking read ==========

    @Test
    void markingOneReadUpdatesItWithTheRecipientPredicate() {
        when(notificationMapper.markRead(eq(5L), eq(RECIPIENT_ID), any(LocalDateTime.class)))
                .thenReturn(1);

        service.markRead(RECIPIENT_ID, 5L);

        verify(notificationMapper).markRead(eq(5L), eq(RECIPIENT_ID), any(LocalDateTime.class));
    }

    /**
     * Somebody else's notification and a non-existent one must be indistinguishable,
     * or the endpoint becomes an existence oracle for other people's inboxes.
     */
    @Test
    void markingSomebodyElsesNotificationReportsNotFound() {
        when(notificationMapper.markRead(eq(5L), eq(RECIPIENT_ID), any(LocalDateTime.class)))
                .thenReturn(0);
        when(notificationMapper.existsForUser(5L, RECIPIENT_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.markRead(RECIPIENT_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND.getCode());
    }

    @Test
    void markingAnAlreadyReadNotificationIsNotAnError() {
        when(notificationMapper.markRead(eq(5L), eq(RECIPIENT_ID), any(LocalDateTime.class)))
                .thenReturn(0);
        when(notificationMapper.existsForUser(5L, RECIPIENT_ID)).thenReturn(1);

        assertThatCode(() -> service.markRead(RECIPIENT_ID, 5L)).doesNotThrowAnyException();
    }

    @Test
    void readAllFlipsEveryUnreadRowOfTheCaller() {
        when(notificationMapper.markAllRead(eq(RECIPIENT_ID), any(LocalDateTime.class)))
                .thenReturn(4);

        assertThat(service.markAllRead(RECIPIENT_ID)).isEqualTo(4);
        verify(notificationMapper).markAllRead(eq(RECIPIENT_ID), any(LocalDateTime.class));
    }

    // ========== Missing table ==========

    /**
     * D-0008's rule applied to the inbox: an empty page would hide a deployment fault
     * behind "you have no notifications", which looks exactly like working software.
     */
    @Test
    void everyReadEndpointReportsTheMissingTableInsteadOfAnEmptyInbox() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(false);

        assertThatThrownBy(() -> service.getNotifications(RECIPIENT_ID, false, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOTIFICATIONS_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> service.getUnreadCount(RECIPIENT_ID))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.markRead(RECIPIENT_ID, 5L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.markAllRead(RECIPIENT_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(notificationMapper);
    }

    /**
     * The write half degrades the other way: a comment or a tip must not fail because
     * the notification table was never created.
     */
    @Test
    void producersWriteNothingAndDoNotThrowWhenTheTableIsAbsent() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(false);

        assertThatCode(() -> {
            service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "hi");
            service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", ACTOR_ID,
                    new BigDecimal("10"), "keep going");
            service.notifyAgentDied(RECIPIENT_ID, AGENT_ID, "Nova", "再见");
            service.notifyBountySubmitted(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫");
            service.notifyBountyAudited(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫", true,
                    new BigDecimal("50"), null);
        }).doesNotThrowAnyException();

        verifyNoInteractions(notificationMapper);
    }

    // ========== Producers ==========

    @Test
    void aHumanCommentOnAHumanPostIsWrittenAsHumanRepliedPost() {
        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L,
                "第一行\n第二行");

        Notification written = captured();
        assertThat(written.getRecipientUserId()).isEqualTo(RECIPIENT_ID);
        assertThat(written.getType()).isEqualTo("HUMAN_REPLIED_POST");
        assertThat(written.getLinkType()).isEqualTo(NotificationLinkType.POST.getCode());
        assertThat(written.getLinkId()).isEqualTo(88L);
        assertThat(written.getActorType()).isEqualTo("HUMAN");
        assertThat(written.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(written.getIsRead()).isZero();
        assertThat(written.getCreatedAt()).isNotNull();
        // newlines are flattened: the list renders the body inline
        assertThat(written.getBody()).isEqualTo("第一行 第二行");
    }

    @Test
    void anAgentCommentOnAHumanPostIsWrittenAsAgentRepliedPost() {
        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.AGENT.getCode(), AGENT_ID, 88L, "有意思");

        Notification written = captured();
        assertThat(written.getType()).isEqualTo("AGENT_REPLIED_POST");
        assertThat(written.getActorType()).isEqualTo("AGENT");
        assertThat(written.getActorId()).isEqualTo(AGENT_ID);
    }

    @Test
    void aReplyToAHumanCommentIsWrittenAsRepliedComment() {
        service.notifyReplyToComment(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "同意");
        assertThat(captured().getType()).isEqualTo("HUMAN_REPLIED_COMMENT");
    }

    /**
     * A person commenting on an agent's post reaches the agent's OWNER.
     *
     * The wake event the same comment produces goes to the agent, and its answer is
     * delivered to whoever it replies to - the agent's own post. Without this row the
     * owner learned nothing at all.
     */
    @Test
    void aHumanCommentOnAnAgentPostReachesTheOwner() {
        service.notifyCommentOnAgentPost(RECIPIENT_ID, ACTOR_ID, 88L, "Nova", "第一行\n第二行");

        Notification written = captured();
        assertThat(written.getRecipientUserId()).isEqualTo(RECIPIENT_ID);
        assertThat(written.getType()).isEqualTo("AGENT_POST_COMMENTED_BY_HUMAN");
        // The link opens the conversation, not the agent page
        assertThat(written.getLinkType()).isEqualTo(NotificationLinkType.POST.getCode());
        assertThat(written.getLinkId()).isEqualTo(88L);
        assertThat(written.getActorType()).isEqualTo("HUMAN");
        assertThat(written.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(written.getTitle()).isEqualTo("有人评论了你的 Agent 的帖子");
        assertThat(written.getBody()).isEqualTo("Agent [Nova] 的帖子收到新评论：第一行 第二行");
    }

    @Test
    void aHumanReplyToAnAgentCommentReachesTheOwner() {
        service.notifyReplyToAgentComment(RECIPIENT_ID, ACTOR_ID, 88L, "Nova", "我不同意");

        Notification written = captured();
        assertThat(written.getType()).isEqualTo("AGENT_COMMENT_REPLIED_BY_HUMAN");
        assertThat(written.getBody()).isEqualTo("Agent [Nova] 的评论收到回复：我不同意");
    }

    /**
     * The owner talking to their own agent writes nothing: the shared self-notification
     * guard covers the two new call sites like every other one.
     */
    @Test
    void theOwnerTalkingToTheirOwnAgentIsNotNotified() {
        service.notifyCommentOnAgentPost(RECIPIENT_ID, RECIPIENT_ID, 88L, "Nova", "自己评论");
        service.notifyReplyToAgentComment(RECIPIENT_ID, RECIPIENT_ID, 88L, "Nova", "自己回复");

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void aTipCarriesTheAmountAndTheNote() {
        service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", ACTOR_ID,
                new BigDecimal("10.00"), "写得好\n继续");

        Notification written = captured();
        assertThat(written.getType()).isEqualTo("AGENT_TIPPED");
        assertThat(written.getLinkType()).isEqualTo(NotificationLinkType.AGENT.getCode());
        assertThat(written.getLinkId()).isEqualTo(AGENT_ID);
        assertThat(written.getBody()).isEqualTo("Agent [Nova] 收到 10 积分，附言：写得好 继续");
    }

    @Test
    void aTipWithoutANoteOmitsTheNoteClause() {
        service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", ACTOR_ID, new BigDecimal("5"), "  ");

        assertThat(captured().getBody()).isEqualTo("Agent [Nova] 收到 5 积分");
    }

    /**
     * A tip note is community text: it is capped, so one long note cannot overflow the
     * body column and lose the whole notification to a truncation error.
     */
    @Test
    void aLongNoteIsTruncatedRatherThanRejected() {
        service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", ACTOR_ID, new BigDecimal("5"),
                "长".repeat(400));

        String body = captured().getBody();
        assertThat(body).endsWith("...");
        assertThat(body.length()).isLessThanOrEqualTo(NotificationServiceImpl.BODY_MAX_LENGTH);
    }

    @Test
    void agentDeathNotifiesTheOwnerWithTheFarewell() {
        service.notifyAgentDied(RECIPIENT_ID, AGENT_ID, "Nova", "[Nova] 能量耗尽，连接中断...");

        Notification written = captured();
        assertThat(written.getType()).isEqualTo("AGENT_DIED");
        assertThat(written.getActorType()).isEqualTo("AGENT");
        assertThat(written.getActorId()).isEqualTo(AGENT_ID);
        assertThat(written.getLinkType()).isEqualTo(NotificationLinkType.AGENT.getCode());
        assertThat(written.getBody()).contains("token 已耗尽").contains("能量耗尽");
    }

    @Test
    void bountySubmissionNotifiesThePublisher() {
        service.notifyBountySubmitted(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫");

        Notification written = captured();
        assertThat(written.getType()).isEqualTo("BOUNTY_SUBMITTED");
        assertThat(written.getLinkType()).isEqualTo(NotificationLinkType.BOUNTY.getCode());
        assertThat(written.getLinkId()).isEqualTo(3L);
        assertThat(written.getBody()).isEqualTo("悬赏《写个爬虫》收到新的提交，等待你审核");
    }

    @Test
    void anAcceptedSubmissionTellsTheHunterTheReward() {
        service.notifyBountyAudited(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫", true,
                new BigDecimal("50.00"), null);

        Notification written = captured();
        assertThat(written.getType()).isEqualTo("BOUNTY_AUDITED");
        assertThat(written.getBody()).isEqualTo("你在悬赏《写个爬虫》中的答案已被采纳，获得 50 积分");
    }

    /**
     * The rejection reason is private between publisher and hunter, so the public
     * activity feed cannot carry it - this notification is where the hunter sees it.
     */
    @Test
    void aRejectedSubmissionCarriesTheFeedback() {
        service.notifyBountyAudited(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫", false, null, "答非所问");

        assertThat(captured().getBody())
                .isEqualTo("你在悬赏《写个爬虫》中的答案未被采纳，理由：答非所问");
    }

    // ========== Producer invariants ==========

    @Test
    void nobodyIsNotifiedAboutTheirOwnAction() {
        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), RECIPIENT_ID, 88L, "hi");
        service.notifyReplyToComment(RECIPIENT_ID, AuthorType.HUMAN.getCode(), RECIPIENT_ID, 88L, "hi");
        service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", RECIPIENT_ID,
                new BigDecimal("10"), null);
        service.notifyBountySubmitted(RECIPIENT_ID, RECIPIENT_ID, 3L, "写个爬虫");
        service.notifyBountyAudited(RECIPIENT_ID, RECIPIENT_ID, 3L, "写个爬虫", true,
                new BigDecimal("50"), null);

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    /**
     * An AGENT actor is never "self", even for the owner: "your agent replied to your
     * post" and "your agent died" are both things the owner asked to be told.
     */
    @Test
    void anAgentActingIsNotTreatedAsTheOwnersOwnAction() {
        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.AGENT.getCode(), AGENT_ID, 88L, "hi");
        service.notifyAgentDied(RECIPIENT_ID, AGENT_ID, "Nova", "再见");

        verify(notificationMapper, times(2)).insert(any(Notification.class));
    }

    /**
     * Every producer runs inside a comment / tip / bounty transaction. A failed insert
     * must not surface there, or a notification outage becomes a community outage.
     */
    @Test
    void aFailedWriteNeverReachesTheCallersTransaction() {
        when(notificationMapper.insert(any(Notification.class)))
                .thenThrow(new RuntimeException("notifications is gone"));

        assertThatCode(() -> {
            service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "hi");
            service.notifyReplyToComment(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "hi");
            service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", ACTOR_ID,
                    new BigDecimal("10"), "note");
            service.notifyAgentDied(RECIPIENT_ID, AGENT_ID, "Nova", "再见");
            service.notifyBountySubmitted(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫");
            service.notifyBountyAudited(RECIPIENT_ID, ACTOR_ID, 3L, "写个爬虫", false, null, "no");
        }).doesNotThrowAnyException();
    }

    // ========== De-duplication ==========

    /**
     * One conversation used to produce one notification per message: an agent and a person
     * going back and forth under a post filled the owner's inbox with twenty identical
     * lines, and the twentieth said nothing the first had not.
     */
    @Test
    void anIdenticalUnreadNotificationSuppressesTheNextOne() {
        givenDedupWindow(10);
        when(notificationMapper.countRecentDuplicates(eq(RECIPIENT_ID), eq("HUMAN_REPLIED_POST"),
                eq("POST"), eq(88L), eq("HUMAN"), eq(ACTOR_ID), any(LocalDateTime.class)))
                .thenReturn(1);

        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "又一条");

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    /**
     * The tuple is what a person reads as one line. A different post, a different actor or
     * a different kind of event is a different line and must still be reported - the check
     * queries with the full tuple, so anything the query does not match is written.
     */
    @Test
    void aDifferentInteractionIsStillWritten() {
        givenDedupWindow(10);
        when(notificationMapper.countRecentDuplicates(any(), any(), any(), any(), any(), any(),
                any(LocalDateTime.class))).thenReturn(0);

        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 89L, "另一帖");

        assertThat(captured().getLinkId()).isEqualTo(89L);
    }

    /**
     * Only UNREAD rows suppress, and that is the mapper's predicate rather than this
     * service's - what is asserted here is that the window handed to it is the configured
     * one and not something derived from the row.
     */
    @Test
    void theWindowHandedToTheQueryIsTheConfiguredOne() {
        givenDedupWindow(30);
        LocalDateTime before = LocalDateTime.now().minusMinutes(30);

        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "hi");

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).countRecentDuplicates(any(), any(), any(), any(), any(), any(),
                since.capture());
        assertThat(since.getValue()).isBetween(before.minusSeconds(5), LocalDateTime.now().minusMinutes(29));
    }

    @Test
    void aWindowOfZeroSwitchesDeduplicationOffEntirely() {
        givenDedupWindow(0);

        service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "hi");

        verify(notificationMapper, never()).countRecentDuplicates(any(), any(), any(), any(),
                any(), any(), any(LocalDateTime.class));
        verify(notificationMapper).insert(any(Notification.class));
    }

    /**
     * The check is a noise filter, not an invariant. A broken count must degrade into a
     * possible extra notification, never into a silently dropped one.
     */
    @Test
    void aFailingDuplicateCheckWritesTheNotificationAnyway() {
        givenDedupWindow(10);
        when(notificationMapper.countRecentDuplicates(any(), any(), any(), any(), any(), any(),
                any(LocalDateTime.class))).thenThrow(new RuntimeException("index is gone"));

        assertThatCode(() -> service.notifyCommentOnPost(RECIPIENT_ID, AuthorType.HUMAN.getCode(),
                ACTOR_ID, 88L, "hi")).doesNotThrowAnyException();

        verify(notificationMapper).insert(any(Notification.class));
    }

    /**
     * A notification without a link (none exists today, but the write path allows it) must
     * still de-duplicate: the mapper compares the nullable columns null-safely, and the
     * service has to pass the nulls through rather than skipping the check.
     */
    @Test
    void aNotificationWithoutALinkStillGoesThroughTheCheck() {
        givenDedupWindow(10);

        service.notifyAgentTipped(RECIPIENT_ID, AGENT_ID, "Nova", ACTOR_ID,
                new BigDecimal("10"), null);

        verify(notificationMapper).countRecentDuplicates(eq(RECIPIENT_ID), eq("AGENT_TIPPED"),
                eq("AGENT"), eq(AGENT_ID), eq("HUMAN"), eq(ACTOR_ID), any(LocalDateTime.class));
    }

    @Test
    void aMissingRecipientIsDroppedRatherThanWritten() {
        service.notifyCommentOnPost(null, AuthorType.HUMAN.getCode(), ACTOR_ID, 88L, "hi");

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    // ========== Fixtures ==========

    private void givenDedupWindow(int minutes) {
        ReflectionTestUtils.setField(service, "dedupWindowMinutes", minutes);
    }

    private Notification captured() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Notification> capturedWrapper() {
        ArgumentCaptor<Wrapper<Notification>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(notificationMapper).selectPage(any(Page.class), captor.capture());
        return (LambdaQueryWrapper<Notification>) captor.getValue();
    }

    /** Column naming is not what these tests are about; fold it away. */
    private String normalize(String sqlSegment) {
        return sqlSegment.toLowerCase().replace("_", "");
    }

    private void stubEmptyPage() {
        Page<Notification> empty = new Page<>(1, 20, 0);
        empty.setRecords(List.of());
        when(notificationMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(empty);
    }

    private Notification row(Long id, NotificationType type, int isRead) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setRecipientUserId(RECIPIENT_ID);
        notification.setType(type.getCode());
        notification.setTitle(type.getText());
        notification.setBody("说得对");
        notification.setLinkType(NotificationLinkType.POST.getCode());
        notification.setLinkId(88L);
        notification.setActorType(AuthorType.HUMAN.getCode());
        notification.setActorId(ACTOR_ID);
        notification.setIsRead(isRead);
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
