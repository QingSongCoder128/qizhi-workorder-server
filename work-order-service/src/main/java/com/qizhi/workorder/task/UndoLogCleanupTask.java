package com.qizhi.workorder.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Seata undo_log 定时清理任务
 * <p>
 * SRS 需求: WO-15 undo_log 表启用定时清理策略，防止数据表膨胀
 * Seata AT 模式下，全局事务提交后 undo_log 记录不再需要，
 * 定时清理已过期的 undo_log 记录，保持数据库表精简。
 * </p>
 * <p>
 * 清理策略：删除 log_created 超过 N 天的记录（默认 7 天），
 * 执行频率：每天凌晨 2:00 执行一次。
 * 清理天数阈值从 Nacos 配置读取，支持热更新。
 * </p>
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class UndoLogCleanupTask {

    private final JdbcTemplate jdbcTemplate;

    /** undo_log 保留天数，超过此天数的记录将被清理，Nacos: seata.undo-log.retention-days */
    @Value("${seata.undo-log.retention-days:7}")
    private int retentionDays;

    /**
     * 定时清理 undo_log 表
     * 每天凌晨 2:00 执行，删除超过保留天数的已提交事务 undo_log
     */
    @Scheduled(cron = "${seata.undo-log.cleanup-cron:0 0 2 * * ?}")
    public void cleanupUndoLog() {
        try {
            String sql = "DELETE FROM undo_log WHERE log_created < DATE_SUB(NOW(), INTERVAL ? DAY)";
            int deleted = jdbcTemplate.update(sql, retentionDays);
            if (deleted > 0) {
                log.info("[WO-15] undo_log 定时清理完成: 删除 {} 条过期记录（保留天数={}）", deleted, retentionDays);
            } else {
                log.debug("[WO-15] undo_log 无需清理（保留天数={}）", retentionDays);
            }
        } catch (Exception e) {
            log.error("[WO-15] undo_log 清理任务执行失败: {}", e.getMessage(), e);
        }
    }
}
