package com.pulse.service.impl;

import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.request.BountyAuditRequest;
import com.pulse.dto.response.BountyDetailResponse;
import com.pulse.dto.request.BountySubmitRequest;
import com.pulse.entity.BountyAcceptance;
import com.pulse.entity.BountySubmission;
import com.pulse.entity.BountyTask;
import com.pulse.entity.Notification;
import com.pulse.entity.User;
import com.pulse.enums.BountyStatus;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.BountyAcceptanceMapper;
import com.pulse.mapper.BountyLogMapper;
import com.pulse.mapper.BountySubmissionMapper;
import com.pulse.mapper.BountyTaskMapper;
import com.pulse.mapper.NotificationMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.NotificationService;
import com.pulse.service.PointsService;
import com.pulse.service.support.AuthorResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BountyServiceImplTest {

    @Mock
    private BountyTaskMapper bountyTaskMapper;
    @Mock
    private BountyAcceptanceMapper bountyAcceptanceMapper;
    @Mock
    private BountySubmissionMapper bountySubmissionMapper;
    @Mock
    private BountyLogMapper bountyLogMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PointsService pointsService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private BountyServiceImpl service;

    @Test
    void cancelPendingBountyReleasesFrozenPointsAndWritesLog() {
        BountyTask task = bountyTask(BountyStatus.PENDING);
        User owner = new User();
        owner.setId(10L);
        owner.setUsername("alice");

        when(bountyTaskMapper.selectById(99L)).thenReturn(task);
        when(bountyTaskMapper.updateStatusIfIn(eq(99L), eq(BountyStatus.CANCELLED.getCode()), anyList()))
                .thenReturn(1);
        when(userMapper.selectById(10L)).thenReturn(owner);

        BountyDetailResponse response = service.cancelBounty(10L, 99L, "not needed");

        assertThat(response.getStatus()).isEqualTo(BountyStatus.CANCELLED.getCode());
        verify(bountyTaskMapper).updateStatusIfIn(99L, BountyStatus.CANCELLED.getCode(),
                List.of(BountyStatus.PENDING.getCode(), BountyStatus.ACCEPTED.getCode()));
        verify(pointsService).refundPoints(10L, new BigDecimal("30.00"), 99L, "取消悬赏释放冻结积分: not needed");
        verify(bountyLogMapper).insert(any());
    }

    @Test
    void cancelReviewingBountyIsRejected() {
        when(bountyTaskMapper.selectById(99L)).thenReturn(bountyTask(BountyStatus.REVIEWING));

        assertThatThrownBy(() -> service.cancelBounty(10L, 99L, "late"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BOUNTY_STATUS_INVALID.getCode());
    }

    /**
     * The compare-and-set losing means somebody else already cancelled/expired the
     * task. Releasing the frozen reward again would create points out of nothing.
     */
    @Test
    void cancelDoesNotRefundWhenCompareAndSetLoses() {
        when(bountyTaskMapper.selectById(99L)).thenReturn(bountyTask(BountyStatus.PENDING));
        when(bountyTaskMapper.updateStatusIfIn(eq(99L), eq(BountyStatus.CANCELLED.getCode()), anyList()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.cancelBounty(10L, 99L, "double cancel"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BOUNTY_STATUS_INVALID.getCode());

        verify(pointsService, never()).refundPoints(any(), any(), any(), any());
    }

    // ========== Notification producers ==========

    /**
     * The publisher is the only person who can move a submitted task forward, and the
     * activity feed is not addressed to anybody in particular.
     */
    @Test
    void submittingAnAnswerNotifiesThePublisher() {
        stubSuccessfulSubmit(20L);

        service.submitBounty(20L, 99L, submitRequest());

        verify(notificationService).notifyBountySubmitted(10L, 20L, 99L, "Need help");
    }

    /**
     * The publisher submitting to their own bounty must not be told about themselves.
     * The guard lives in NotificationServiceImpl, so what is checked here is that the
     * call carries a recipient equal to the actor and nothing else is invented.
     */
    @Test
    void aPublisherSubmittingToTheirOwnBountyIsNotNotifiedTwice() {
        stubSuccessfulSubmit(10L);

        service.submitBounty(10L, 99L, submitRequest());

        // recipient == actor: the service drops it (see NotificationServiceImplTest)
        verify(notificationService).notifyBountySubmitted(10L, 10L, 99L, "Need help");
    }

    @Test
    void acceptingASubmissionNotifiesTheHunterWithTheReward() {
        BountyTask task = bountyTask(BountyStatus.REVIEWING);
        BountySubmission submission = submission();
        when(bountyTaskMapper.selectById(99L)).thenReturn(task);
        when(bountySubmissionMapper.selectById(7L)).thenReturn(submission);
        when(userMapper.selectById(20L)).thenReturn(hunter());
        when(bountyTaskMapper.updateStatusIfIn(eq(99L), eq(BountyStatus.COMPLETED.getCode()), anyList()))
                .thenReturn(1);
        when(userMapper.settleFrozenPointsAtomic(10L, new BigDecimal("30.00"))).thenReturn(1);

        service.auditSubmission(10L, 99L, auditRequest("ACCEPT", null));

        verify(notificationService).notifyBountyAudited(20L, 10L, 99L, "Need help", true,
                new BigDecimal("30.00"), null);
    }

    /**
     * The rejection reason is private between publisher and hunter, so the public log
     * cannot carry it - the notification is where the hunter reads it.
     */
    @Test
    void rejectingASubmissionNotifiesTheHunterWithTheFeedback() {
        when(bountyTaskMapper.selectById(99L)).thenReturn(bountyTask(BountyStatus.REVIEWING));
        when(bountySubmissionMapper.selectById(7L)).thenReturn(submission());
        when(userMapper.selectById(20L)).thenReturn(hunter());

        service.auditSubmission(10L, 99L, auditRequest("REJECT", "答非所问"));

        verify(notificationService).notifyBountyAudited(20L, 10L, 99L, "Need help", false,
                null, "答非所问");
    }

    /**
     * End to end with the REAL notification service and a database that rejects the
     * insert: the submission, its status transition and its log must all still stand.
     * A notification outage may not become a bounty outage.
     */
    @Test
    void aFailingNotificationWriteDoesNotBreakTheSubmission() {
        NotificationMapper failingMapper = mock(NotificationMapper.class);
        when(failingMapper.insert(any(Notification.class)))
                .thenThrow(new RuntimeException("notifications is gone"));
        SchemaCapabilities capabilities = mock(SchemaCapabilities.class);
        when(capabilities.isNotificationsTable()).thenReturn(true);

        BountyServiceImpl withRealNotifications = new BountyServiceImpl(
                bountyTaskMapper, bountyAcceptanceMapper, bountySubmissionMapper, bountyLogMapper,
                agentMapper, userMapper, pointsService,
                new NotificationServiceImpl(failingMapper, capabilities,
                        new AuthorResolver(userMapper, agentMapper)));

        stubSuccessfulSubmit(20L);

        assertThatCode(() -> withRealNotifications.submitBounty(20L, 99L, submitRequest()))
                .doesNotThrowAnyException();
        verify(bountySubmissionMapper).insert(any(BountySubmission.class));
        verify(bountyLogMapper).insert(any());
    }

    private void stubSuccessfulSubmit(Long hunterId) {
        BountyAcceptance acceptance = new BountyAcceptance();
        acceptance.setId(5L);
        acceptance.setTaskId(99L);
        acceptance.setHunterId(hunterId);

        when(bountyTaskMapper.selectById(99L)).thenReturn(bountyTask(BountyStatus.PENDING));
        when(bountyAcceptanceMapper.findByTaskAndHunter(99L, hunterId)).thenReturn(acceptance);
        when(bountySubmissionMapper.existsByTaskAndHunter(99L, hunterId)).thenReturn(false);
        when(bountyTaskMapper.updateStatusIfIn(eq(99L), eq(BountyStatus.REVIEWING.getCode()), anyList()))
                .thenReturn(1);
        when(userMapper.selectById(hunterId)).thenReturn(hunter());
    }

    private BountySubmitRequest submitRequest() {
        BountySubmitRequest request = new BountySubmitRequest();
        request.setContent("my answer");
        return request;
    }

    private BountyAuditRequest auditRequest(String decision, String feedback) {
        BountyAuditRequest request = new BountyAuditRequest();
        request.setSubmissionId(7L);
        request.setDecision(decision);
        request.setFeedback(feedback);
        return request;
    }

    private BountySubmission submission() {
        BountySubmission submission = new BountySubmission();
        submission.setId(7L);
        submission.setTaskId(99L);
        submission.setHunterId(20L);
        return submission;
    }

    private User hunter() {
        User user = new User();
        user.setId(20L);
        user.setUsername("bob");
        return user;
    }

    private BountyTask bountyTask(BountyStatus status) {
        BountyTask task = new BountyTask();
        task.setId(99L);
        task.setOwnerId(10L);
        task.setTitle("Need help");
        task.setAuthorType("HUMAN");
        task.setAuthorName("alice");
        task.setDescription("Please help with this bounty");
        task.setRewardPoints(new BigDecimal("30.00"));
        task.setTaskType("KNOWLEDGE");
        task.setCrisisLevel("LOW");
        task.setStatus(status.getCode());
        task.setAcceptedCount(0);
        task.setSubmissionCount(0);
        task.setDeadline(LocalDateTime.now().plusDays(1));
        return task;
    }

    /**
     * Adversarial-review regression: acceptBounty used to write ACCEPTED
     * unconditionally, so a task that cancel or the expiry sweep had already settled
     * (and refunded) could be pulled back into an active state.
     */
    @Test
    void acceptDoesNotResurrectATaskThatWasAlreadySettled() {
        BountyTask task = bountyTask(BountyStatus.PENDING);
        when(bountyTaskMapper.selectById(99L)).thenReturn(task);
        when(bountyAcceptanceMapper.findByTaskAndHunter(99L, 20L)).thenReturn(null);
        when(bountyTaskMapper.updateStatusIfIn(eq(99L), eq(BountyStatus.ACCEPTED.getCode()), anyList()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.acceptBounty(20L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BOUNTY_NOT_ACCEPTABLE.getCode());
    }

    /**
     * Adversarial-review regression: submitBounty used to write REVIEWING
     * unconditionally, which could pull a COMPLETED (already paid) task back into
     * review - and the audit path would then pay a second time.
     */
    @Test
    void submitDoesNotPullACompletedTaskBackIntoReview() {
        BountyTask task = bountyTask(BountyStatus.REVIEWING);
        BountyAcceptance acceptance = new BountyAcceptance();
        acceptance.setId(5L);
        acceptance.setTaskId(99L);
        acceptance.setHunterId(20L);

        when(bountyTaskMapper.selectById(99L)).thenReturn(task);
        when(bountyAcceptanceMapper.findByTaskAndHunter(99L, 20L)).thenReturn(acceptance);
        when(bountySubmissionMapper.existsByTaskAndHunter(99L, 20L)).thenReturn(false);
        when(bountyTaskMapper.updateStatusIfIn(eq(99L), eq(BountyStatus.REVIEWING.getCode()), anyList()))
                .thenReturn(0);

        BountySubmitRequest request = new BountySubmitRequest();
        request.setContent("my answer");

        assertThatThrownBy(() -> service.submitBounty(20L, 99L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BOUNTY_NOT_ACCEPTABLE.getCode());
    }
}
