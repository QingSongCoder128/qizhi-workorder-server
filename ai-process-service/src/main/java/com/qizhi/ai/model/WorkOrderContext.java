package com.qizhi.ai.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工单 AI 处理上下文（A2A 共享上下文）
 * <p>
 * SRS 需求: AI-05 A2A Agent 结构化通信
 * 三个 Agent 通过共享上下文传递数据，避免紧耦合：
 *   - CategoryAgent 输出: category, confidence
 *   - RatingAgent 读取 category，输出: priority, priorityReason
 *   - PreAuditAgent 读取全部上下文，输出: suggestion, sensitiveWords, pass
 * </p>
 * <p>
 * 数据流: 原始输入 → CategoryAgent → context → RatingAgent → context → PreAuditAgent → context → 最终结果
 * </p>
 */
@Data
public class WorkOrderContext {

    // ---- 输入字段（由调用方填入） ----

    /** 工单 ID */
    private Long workOrderId;

    /** 工单类型 */
    private String type;

    /** 工单标题 */
    private String title;

    /** 工单详情 */
    private String detail;

    /** 是否紧急 */
    private Boolean urgent;

    // ---- CategoryAgent 输出 ----

    /** 分类结果（部门编码） */
    private String category;

    /** 分类置信度 */
    private Double confidence;

    // ---- RatingAgent 输出 ----

    /** 优先级 */
    private String priority;

    /** 优先级判定原因 */
    private String priorityReason;

    // ---- PreAuditAgent 输出 ----

    /** 预审建议 */
    private String suggestion;

    /** 敏感词（逗号分隔） */
    private String sensitiveWords;

    /** 预审是否通过 */
    private Boolean pass;

    /** AI 是否异常标记 */
    private Boolean aiAbnormal = false;

    // ---- 流程控制字段 ----

    /** 任务 ID */
    private String taskId;

    /** 最后成功执行的节点 */
    private String lastCompletedNode;

    /** 各 Agent 执行结果列表 */
    private List<AgentResult> agentResults = new ArrayList<>();

    /**
     * 添加 Agent 执行结果
     */
    public void addResult(AgentResult result) {
        agentResults.add(result);
        if ("FAILED".equals(result.getStatus())) {
            aiAbnormal = true;
        }
    }
}
