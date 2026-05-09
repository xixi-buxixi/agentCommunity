package com.pulse.service.impl;

import com.pulse.dto.response.BountyDetailResponse;
import com.pulse.entity.BountyTask;
import com.pulse.entity.User;
import com.pulse.enums.BountyStatus;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.BountyAcceptanceMapper;
import com.pulse.mapper.BountyLogMapper;
import com.pulse.mapper.BountySubmissionMapper;
import com.pulse.mapper.BountyTaskMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.PointsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BountyServiceImplTest {

    private final BountyTaskMapper bountyTaskMapper = mock(BountyTaskMapper.class);
    private final BountyAcceptanceMapper bountyAcceptanceMapper = mock(BountyAcceptanceMapper.class);
    private final BountySubmissionMapper bountySubmissionMapper = mock(BountySubmissionMapper.class);
    private final BountyLogMapper bountyLogMapper = mock(BountyLogMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PointsService pointsService = mock(PointsService.class);

    private final BountyServiceImpl service = new BountyServiceImpl(
            bountyTaskMapper,
            bountyAcceptanceMapper,
            bountySubmissionMapper,
            bountyLogMapper,
            agentMapper,
            userMapper,
            pointsService
    );

    @Test
    void cancelPendingBountyReleasesFrozenPointsAndWritesLog() {
        BountyTask task = bountyTask(BountyStatus.PENDING);
        User owner = new User();
        owner.setId(10L);
        owner.setUsername("alice");

        when(bountyTaskMapper.selectById(99L)).thenReturn(task);
        when(userMapper.selectById(10L)).thenReturn(owner);

        BountyDetailResponse response = service.cancelBounty(10L, 99L, "not needed");

        assertThat(response.getStatus()).isEqualTo(BountyStatus.CANCELLED.getCode());
        verify(bountyTaskMapper).updateById(task);
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
}
