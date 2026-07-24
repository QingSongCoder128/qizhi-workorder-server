package com.qizhi.statistics.scheduler;

import com.qizhi.common.core.result.R;
import com.qizhi.statistics.entity.StatDailySummary;
import com.qizhi.statistics.feign.WorkOrderFeignClient;
import com.qizhi.statistics.mapper.StatDailySummaryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 统计定时任务
 * <p>
 * SRS 需求: ST-02 定时汇总
 * 每日凌晨 2:00 从 work-order-service 拉取前日数据，
 * 按部门 + 工单类型分别汇总写入 stat_daily_summary 表。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RefreshScope
public class StatScheduler {

    private final WorkOrderFeignClient workOrderFeignClient;
    private final StatDailySummaryMapper summaryMapper;

    /** 部门编码列表，从 Nacos 读取，逗号分隔 */
    @Value("${stats.dept-codes:DEPT_IT,DEPT_ADMIN,DEPT_HR,DEPT_TECH,DEPT_FIN}")
    private List<String> deptCodes;

    /** 工单类型列表，从 Nacos 读取，逗号分隔 */
    @Value("${stats.work-types:REPAIR,PURCHASE,MAINTENANCE,OTHER}")
    private List<String> workTypes;

    /**
     * 每日凌晨 2:00 执行：汇总前一天的工单统计数据
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailySummary() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String dateStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE);
        log.info("开始执行每日统计汇总: statDate={}", dateStr);

        try {
            int totalInserted = 0;

            // 按部门 × 工单类型 交叉汇总
            for (String deptCode : deptCodes) {
                for (String workType : workTypes) {
                    int count = insertSummary(yesterday, dateStr, deptCode, workType);
                    totalInserted += count;
                }
                // 部门维度汇总（workType=ALL）
                totalInserted += insertSummary(yesterday, dateStr, deptCode, "ALL");
            }
            // 全局汇总（deptCode=TOTAL, workType=ALL）
            totalInserted += insertSummary(yesterday, dateStr, "TOTAL", "ALL");

            log.info("每日统计汇总完成: statDate={}, 插入/更新记录数={}", dateStr, totalInserted);
        } catch (Exception e) {
            log.error("每日统计汇总失败: statDate={}, error={}", dateStr, e.getMessage(), e);
        }
    }

    /**
     * 拉取指定部门+类型的工单数据并写入汇总
     *
     * @return 插入记录数（0 或 1）
     */
    private int insertSummary(LocalDate statDate, String dateStr, String deptCode, String workType) {
        try {
            // 通过 Feign 拉取工单导出数据
            R<List<Map<String, Object>>> response = workOrderFeignClient.getExportList(
                    deptCode.equals("TOTAL") ? null : deptCode,
                    "ALL".equals(workType) ? null : workType,
                    dateStr, dateStr);

            List<Map<String, Object>> orders = new ArrayList<>();
            if (response != null && response.getCode() == 200 && response.getData() != null) {
                orders = response.getData();
            }

            // 统计各状态数量
            int totalCount = orders.size();
            int pendingCount = 0;
            int completedCount = 0;
            int rejectedCount = 0;

            for (Map<String, Object> order : orders) {
                String status = String.valueOf(order.getOrDefault("status", ""));
                switch (status) {
                    case "PENDING", "IN_PROGRESS", "APPROVING" -> pendingCount++;
                    case "COMPLETED", "CLOSED" -> completedCount++;
                    case "REJECTED" -> rejectedCount++;
                }
            }

            // 构建实体并写入（INSERT ON DUPLICATE KEY UPDATE）
            StatDailySummary summary = new StatDailySummary();
            summary.setStatDate(statDate);
            summary.setDeptCode(deptCode);
            summary.setWorkType(workType);
            summary.setTotalCount(totalCount);
            summary.setPendingCount(pendingCount);
            summary.setCompletedCount(completedCount);
            summary.setRejectedCount(rejectedCount);
            summary.setTimeoutCount(0); // 超时数暂由后续增强阶段计算
            summary.setAvgApproveMinutes(0);
            summary.setCreatedAt(LocalDateTime.now());
            summary.setUpdatedAt(LocalDateTime.now());

            summaryMapper.insert(summary);
            return 1;
        } catch (Exception e) {
            log.warn("写入统计汇总失败: deptCode={}, workType={}, error={}", deptCode, workType, e.getMessage());
            return 0;
        }
    }
}
