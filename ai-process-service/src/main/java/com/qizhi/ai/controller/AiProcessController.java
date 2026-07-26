package com.qizhi.ai.controller;

import com.qizhi.ai.dto.AiProcessRequest;
import com.qizhi.ai.entity.AiTaskLog;
import com.qizhi.ai.graph.ProcessGraph;
import com.qizhi.ai.mapper.AiTaskLogMapper;
import com.qizhi.ai.model.AgentResult;
import com.qizhi.ai.model.WorkOrderContext;
import com.qizhi.ai.supervisor.AiSupervisor;
import com.qizhi.common.core.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
