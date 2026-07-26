package com.qizhi.ai.controller;

import com.qizhi.ai.dto.AiProcessRequest;
import com.qizhi.ai.entity.AiTaskLog;
import com.qizhi.ai.graph.ProcessGraph;
import com.qizhi.ai.mapper.AiTaskLogMapper;
import com.qizhi.ai.model.AgentResult;
import com.qizhi.ai.model.WorkOrderContext;
import com.qizhi.ai.supervisor.AiSupervisor;
import com.qizhi.ai.config.AiRuntimeConfig;
import com.qizhi.ai.config.AiRuntimeConfigService;
import com.qizhi.ai.client.AiModelClient;
import com.qizhi.common.core.result.R;
import com.qizhi.common.redis.util.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * AI 智能处理控制器
 * <p>
 * SRS 需求: AI-01 ~ AI-05
 * 使用 Supervisor 控制并发 + Graph 流程引擎执行三 Agent 管线。
 * 流程: 接收请求 → Supervisor 检查并发 → Graph 执行 CATEGORY→RATING→PRE_AUDIT → 返回结果
 * </p>
 */
@Slf4j
@Tag(name = "AI 智能处理")
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiProcessController {

    private final ProcessGraph processGraph;
    private final AiSupervisor supervisor;
    private final AiTaskLogMapper taskLogMapper;
    private final AiRuntimeConfigService runtimeConfigService;
    private final AiModelClient aiModelClient;
    private final RedisUtil redisUtil;

    /**
     * AI 预处理工单（供 work-order-service Feign 同步调用）
     * <p>
     * Supervisor 控制最大并发数，Graph 按序执行三个 Agent，
     * 各 Agent 结果统一写入 WorkOrderContext，最终聚合返回。
     * </p>
     */
    @Operation(summary = "AI 预处理工单（供 Feign 同步调用）")
    @PostMapping("/process")
    public R<Map<String, Object>> process(@RequestBody AiProcessRequest request) {
        String taskId = "AI" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
        log.info("AI预处理开始: taskId={}, workOrderId={}, supervisor={}/{}",
                taskId, request.getWorkOrderId(), supervisor.getRunningCount(), supervisor.getMaxConcurrent());

        // Supervisor 并发控制
        if (!supervisor.tryAcquire()) {
            log.warn("AI并发已满，任务排队: taskId={}", taskId);
            // 返回 QUEUED 状态，调用方可选择稍后重试
            Map<String, Object> queuedResult = new HashMap<>();
            queuedResult.put("category", "DEPT_IT");
            queuedResult.put("confidence", 0.5);
            queuedResult.put("priority", Boolean.TRUE.equals(request.getUrgent()) ? "URGENT" : "NORMAL");
            queuedResult.put("priorityReason", "AI服务繁忙，使用默认值");
            queuedResult.put("suggestion", "AI服务繁忙，建议人工审核");
            queuedResult.put("sensitiveWords", "");
            queuedResult.put("pass", true);
            queuedResult.put("aiAbnormal", true);
            queuedResult.put("aiStatus", "QUEUED");
            queuedResult.put("taskId", taskId);
            queuedResult.put("agentResults", java.util.Collections.emptyList());
            return R.ok(queuedResult);
        }

        try {
            // 构建上下文
            WorkOrderContext context = new WorkOrderContext();
            context.setTaskId(taskId);
            context.setWorkOrderId(request.getWorkOrderId());
            context.setType(request.getType());
            context.setTitle(request.getTitle());
            context.setDetail(request.getDetail());
            context.setUrgent(request.getUrgent());

            // Graph 流程引擎执行三个 Agent
            processGraph.execute(context);
            redisUtil.set("ai:task:checkpoint:" + taskId, context, 24, TimeUnit.HOURS);

            // 保存各 Agent 的执行日志
            for (AgentResult agentResult : context.getAgentResults()) {
                saveLog(taskId, request.getWorkOrderId(), agentResult.getAgentName(),
                        buildInputText(context, agentResult.getAgentName()),
                        agentResult.getData() != null ? agentResult.getData().toString() : "",
                        agentResult.getStatus(),
                        (int) agentResult.getDurationMs(),
                        agentResult.getRetryCount(),
                        agentResult.getErrorMsg());
            }

            // 聚合返回结果
            Map<String, Object> aggregated = new HashMap<>();
            aggregated.put("category", context.getCategory());
            aggregated.put("confidence", context.getConfidence());
            aggregated.put("priority", context.getPriority());
            aggregated.put("priorityReason", context.getPriorityReason());
            aggregated.put("suggestion", context.getSuggestion());
            aggregated.put("sensitiveWords", context.getSensitiveWords());
            aggregated.put("pass", context.getPass());
            aggregated.put("aiAbnormal", context.getAiAbnormal());
            aggregated.put("aiStatus", "COMPLETED");
            aggregated.put("taskId", taskId);
            aggregated.put("agentResults", context.getAgentResults());

            log.info("AI预处理完成: taskId={}, aiAbnormal={}", taskId, context.getAiAbnormal());
            return R.ok(aggregated);

        } finally {
            // 释放 Supervisor 许可
            supervisor.release();
        }
    }

    /**
     * 查询 Supervisor 状态（运维接口）
     */
    @Operation(summary = "AI Supervisor 状态")
    @GetMapping("/supervisor/status")
    public R<Map<String, Object>> supervisorStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("runningTasks", supervisor.getRunningCount());
        status.put("maxConcurrent", supervisor.getMaxConcurrent());
        status.put("available", supervisor.getRunningCount() < supervisor.getMaxConcurrent());
        return R.ok(status);
    }

    @PostMapping("/tasks/{taskId}/resume")
    public R<Map<String, Object>> resume(@PathVariable String taskId) {
        Object checkpoint = redisUtil.get("ai:task:checkpoint:" + taskId);
        if (!(checkpoint instanceof WorkOrderContext context)) {
            return R.fail("AI任务检查点不存在或已过期");
        }
        if (!supervisor.tryAcquire()) {
            return R.fail(429, "AI并发已满，请稍后重试");
        }
        try {
            context.getAgentResults().removeIf(result -> "FAILED".equals(result.getStatus()));
            int before = context.getAgentResults().size();
            processGraph.resumeFailedNodes(context);
            for (int i = before; i < context.getAgentResults().size(); i++) {
                AgentResult result = context.getAgentResults().get(i);
                saveLog(taskId, context.getWorkOrderId(), result.getAgentName(),
                        buildInputText(context, result.getAgentName()),
                        result.getData() == null ? "" : result.getData().toString(),
                        result.getStatus(), (int) result.getDurationMs(),
                        result.getRetryCount(), result.getErrorMsg());
            }
            redisUtil.set("ai:task:checkpoint:" + taskId, context, 24, TimeUnit.HOURS);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("taskId", taskId);
            response.put("resumed", true);
            response.put("aiAbnormal", context.getAgentResults().stream()
                    .anyMatch(result -> !"SUCCESS".equals(result.getStatus())));
            response.put("agentResults", context.getAgentResults());
            response.put("category", context.getCategory());
            response.put("confidence", context.getConfidence());
            response.put("priority", context.getPriority());
            response.put("priorityReason", context.getPriorityReason());
            response.put("suggestion", context.getSuggestion());
            response.put("sensitiveWords", context.getSensitiveWords());
            response.put("pass", context.getPass());
            return R.ok(response);
        } finally {
            supervisor.release();
        }
    }

    @GetMapping("/config")
    public R<Map<String, Object>> getConfig() {
        AiRuntimeConfig config = runtimeConfigService.get();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("apiUrl", maskUrl(config.apiUrl()));
        result.put("keyConfigured", config.apiKey() != null && !config.apiKey().isBlank());
        result.put("modelName", config.modelName());
        result.put("temperature", config.temperature());
        result.put("maxTokens", config.maxTokens());
        result.put("timeoutMs", config.timeoutMs());
        result.put("maxConcurrent", config.maxConcurrent());
        result.put("maxRetry", config.maxRetry());
        result.put("retryIntervalMs", config.retryIntervalMs());
        result.put("nodeOrder", config.nodeOrder());
        return R.ok(result);
    }

    @PutMapping("/config")
    public R<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> update) throws Exception {
        runtimeConfigService.publish(update);
        return getConfig();
    }

    @PostMapping("/config/validate")
    public R<Map<String, Object>> validateConfig() {
        long start = System.currentTimeMillis();
        Map<String, Object> response = aiModelClient.chatAsMap(
                "你是连通性检查器，只返回JSON。",
                "返回 {\"ok\":true}，不要包含其他内容。");
        return R.ok(Map.of(
                "valid", Boolean.TRUE.equals(response.get("ok")),
                "elapsedMs", System.currentTimeMillis() - start));
    }

    private String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/***";
        } catch (Exception e) {
            return "***";
        }
    }

    /**
     * 根据 Agent 名称构建输入文本（用于日志记录）
     */
    private String buildInputText(WorkOrderContext context, String agentName) {
        return switch (agentName) {
            case "CATEGORY" -> String.format("type=%s, title=%s", context.getType(), context.getTitle());
            case "RATING" -> String.format("type=%s, urgent=%s, title=%s",
                    context.getType(), context.getUrgent(), context.getTitle());
            case "PRE_AUDIT" -> String.format("title=%s, detail长度=%d",
                    context.getTitle(), context.getDetail() != null ? context.getDetail().length() : 0);
            default -> "unknown";
        };
    }

    /**
     * 保存 AI 任务执行日志
     */
    private void saveLog(String taskId, Long workOrderId, String agentName,
                         String inputText, String outputText, String status,
                         int durationMs, int retryCount, String errorMsg) {
        try {
            AiTaskLog logEntry = new AiTaskLog();
            logEntry.setTaskId(taskId);
            logEntry.setWorkOrderId(workOrderId);
            logEntry.setAgentName(agentName);
            logEntry.setInputText(inputText);
            logEntry.setOutputText(outputText);
            logEntry.setStatus(status);
            logEntry.setDurationMs(durationMs);
            logEntry.setRetryCount(retryCount);
            logEntry.setErrorMsg(errorMsg);
            taskLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("保存AI日志失败: {}", e.getMessage());
        }
    }
}
