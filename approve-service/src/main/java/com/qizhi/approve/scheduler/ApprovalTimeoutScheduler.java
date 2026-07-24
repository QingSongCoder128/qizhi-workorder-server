package com.qizhi.approve.scheduler;

import com.qizhi.approve.service.ApproveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时定时任务
 * <p>
 * SRS 需求: AP-04 审批超时督办
 * 每 10 分钟扫描一次超时审批单（默认 48 小时阈值），
 * 对超时单据发送督办通知，提醒审批人尽快处理。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutScheduler {

    private final ApproveService approveService;

    /**
     * 定时检测审批超时
     * <p>
     * cron: 每 10 分钟执行一次（0 秒触发）
     * 查找 PENDING/APPROVING 状态且创建时间超过 48 小时的审批单，
     * 发送督办通知给当前审批人和提交人。
     * </p>
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void checkTimeout() {
        log.debug("开始检测审批超时...");
        try {
            approveService.handleTimeoutApprovals();
        } catch (Exception e) {
            log.error("审批超时检测异常: {}", e.getMessage(), e);
        }
    }
}
