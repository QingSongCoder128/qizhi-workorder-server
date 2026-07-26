package com.qizhi.ai.graph;

import com.qizhi.ai.agent.CategoryAgent;
import com.qizhi.ai.agent.PreAuditAgent;
import com.qizhi.ai.agent.RatingAgent;
import com.qizhi.ai.model.AgentResult;
import com.qizhi.ai.model.WorkOrderContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 处理流程引擎（Graph 模式）
 * <p>
 * SRS 需求: AI-02 Graph 流程引擎 + AI-05 A2A Agent 结构化通信
 * 定义三个 Agent 的执行顺序：CATEGORY → RATING → PRE_AUDIT
 * 每个 Agent 的输出写入 WorkOrderContext，下一个 Agent 可读取前序结果。
 * </p>
 * <p>
 * 支持从失败节点恢复重试（记录 lastCompletedNode），
 * 单个 Agent 失败不阻塞后续节点执行。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RefreshScope
public class ProcessGraph {

    private final CategoryAgent categoryAgent;
    private final RatingAgent ratingAgent;
    private final PreAuditAgent preAuditAgent;

    /** 节点枚举（有序） */
    public static final String NODE_CATEGORY = "CATEGORY";
    public static final String NODE_RATING = "RATING";
    public static final String NODE_PRE_AUDIT = "PRE_AUDIT";

    /** 每个 Agent 的最大重试次数，从 Nacos 读取，默认 2 */
    @Value("${ai.graph.max-retry:2}")
    private int maxRetry;

    /** 重试间隔（毫秒），从 Nacos 读取，默认 500 */
    @Value("${ai.graph.retry-interval-ms:500}")
    private long retryIntervalMs;

    /**
     * 执行完整的 AI 处理流程
     * <p>
     * 按 CATEGORY → RATING → PRE_AUDIT 顺序执行，
     * 每个节点输出写入 context，供后续节点参考。
     * </p>
     *
     * @param context 工单处理上下文
     */
    public void execute(WorkOrderContext context) {
        log.info("Graph 流程开始: taskId={}, workOrderId={}", context.getTaskId(), context.getWorkOrderId());

        // 节点1: 分类
        executeCategory(context);
        context.setLastCompletedNode(NODE_CATEGORY);

        // 节点2: 评级（可参考分类结果）
        executeRating(context);
        context.setLastCompletedNode(NODE_RATING);

        // 节点3: 预审（可参考全部上下文）
        executePreAudit(context);
        context.setLastCompletedNode(NODE_PRE_AUDIT);

        log.info("Graph 流程结束: taskId={}, aiAbnormal={}", context.getTaskId(), context.getAiAbnormal());
    }

    /**
     * 节点1: 分类 Agent（带重试）
     * 输出: context.category, context.confidence
     */
    private void executeCategory(WorkOrderContext context) {
        log.info("[CATEGORY] 开始: type={}", context.getType());
        long start = System.currentTimeMillis();

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                Map<String, Object> result = categoryAgent.classify(
                        context.getType(), context.getTitle(), context.getDetail());

                // 写入上下文
                context.setCategory(String.valueOf(result.get("category")));
                context.setConfidence(result.get("confidence") != null ?
                        ((Number) result.get("confidence")).doubleValue() : 0.8);
                // BUG-003 FIX: 检测降级标记
                if (Boolean.TRUE.equals(result.get("fallback"))) {
                    context.setAiAbnormal(true);
                }

                long duration = System.currentTimeMillis() - start;
                AgentResult agentResult = Boolean.TRUE.equals(result.get("fallback"))
                        ? AgentResult.fallback(NODE_CATEGORY, result, duration)
                        : AgentResult.success(NODE_CATEGORY, result, duration);
                agentResult.setRetryCount(attempt);
                context.addResult(agentResult);
                log.info("[CATEGORY] 成功: category={}, duration={}ms", context.getCategory(), duration);
                return;
            } catch (Exception e) {
                log.warn("[CATEGORY] 第{}次重试失败: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetry) {
                    sleep(retryIntervalMs);
                }
            }
        }

        // 全部重试失败，使用降级结果
        long duration = System.currentTimeMillis() - start;
        AgentResult failedResult = AgentResult.failed(NODE_CATEGORY, duration, "AI分类全部失败");
        failedResult.setRetryCount(maxRetry);
        context.addResult(failedResult);
        // 降级默认值
        context.setCategory("DEPT_IT");
        context.setConfidence(0.5);
        log.error("[CATEGORY] 全部重试失败，使用降级默认值");
    }

    /**
     * 节点2: 评级 Agent（带重试）
     * 读取 context.category 辅助评级，输出: context.priority, context.priorityReason
     */
    private void executeRating(WorkOrderContext context) {
        log.info("[RATING] 开始: urgent={}, category={}", context.getUrgent(), context.getCategory());
        long start = System.currentTimeMillis();

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                Map<String, Object> result = ratingAgent.rate(
                        context.getType(), context.getUrgent(), context.getTitle());

                // 写入上下文
                context.setPriority(String.valueOf(result.get("priority")));
                context.setPriorityReason(String.valueOf(result.get("priorityReason")));
                // BUG-003 FIX: 检测降级标记
                if (Boolean.TRUE.equals(result.get("fallback"))) {
                    context.setAiAbnormal(true);
                }

                long duration = System.currentTimeMillis() - start;
                AgentResult agentResult = Boolean.TRUE.equals(result.get("fallback"))
                        ? AgentResult.fallback(NODE_RATING, result, duration)
                        : AgentResult.success(NODE_RATING, result, duration);
                agentResult.setRetryCount(attempt);
                context.addResult(agentResult);
                log.info("[RATING] 成功: priority={}, duration={}ms", context.getPriority(), duration);
                return;
            } catch (Exception e) {
                log.warn("[RATING] 第{}次重试失败: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetry) {
                    sleep(retryIntervalMs);
                }
            }
        }

        // 全部重试失败，使用降级结果
        long duration = System.currentTimeMillis() - start;
        AgentResult failedResult = AgentResult.failed(NODE_RATING, duration, "AI评级全部失败");
        failedResult.setRetryCount(maxRetry);
        context.addResult(failedResult);
        context.setPriority(Boolean.TRUE.equals(context.getUrgent()) ? "URGENT" : "NORMAL");
        context.setPriorityReason("AI异常，使用默认优先级");
        log.error("[RATING] 全部重试失败，使用降级默认值");
    }

    /**
     * 节点3: 预审 Agent（带重试）
     * 读取全部上下文信息，输出: context.suggestion, context.sensitiveWords, context.pass
     */
    private void executePreAudit(WorkOrderContext context) {
        log.info("[PRE_AUDIT] 开始: title长度={}", context.getTitle() != null ? context.getTitle().length() : 0);
        long start = System.currentTimeMillis();

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                Map<String, Object> result = preAuditAgent.preAudit(
                        context.getTitle(), context.getDetail());

                // 写入上下文
                context.setSuggestion(String.valueOf(result.get("suggestion")));
                context.setSensitiveWords(String.valueOf(result.getOrDefault("sensitiveWords", "")));
                context.setPass(result.get("pass") != null ? (Boolean) result.get("pass") : true);
                // BUG-003 FIX: 检测降级标记
                if (Boolean.TRUE.equals(result.get("fallback"))) {
                    context.setAiAbnormal(true);
                }

                long duration = System.currentTimeMillis() - start;
                AgentResult agentResult = Boolean.TRUE.equals(result.get("fallback"))
                        ? AgentResult.fallback(NODE_PRE_AUDIT, result, duration)
                        : AgentResult.success(NODE_PRE_AUDIT, result, duration);
                agentResult.setRetryCount(attempt);
                context.addResult(agentResult);
                log.info("[PRE_AUDIT] 成功: pass={}, duration={}ms", context.getPass(), duration);
                return;
            } catch (Exception e) {
                log.warn("[PRE_AUDIT] 第{}次重试失败: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetry) {
                    sleep(retryIntervalMs);
                }
            }
        }

        // 全部重试失败，使用降级结果
        long duration = System.currentTimeMillis() - start;
        AgentResult failedResult = AgentResult.failed(NODE_PRE_AUDIT, duration, "AI预审全部失败");
        failedResult.setRetryCount(maxRetry);
        context.addResult(failedResult);
        context.setSuggestion("AI预审异常，建议人工审核");
        context.setSensitiveWords("");
        context.setPass(true);
        log.error("[PRE_AUDIT] 全部重试失败，使用降级默认值");
    }

    /**
     * 安全 sleep（不抛出 InterruptedException）
     */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
