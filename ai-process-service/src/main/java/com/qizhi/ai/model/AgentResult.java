package com.qizhi.ai.model;

import lombok.Data;

import java.util.Map;

/**
 * Agent 统一返回结果
 * <p>
 * SRS 需求: AI-05 A2A Agent 结构化通信
 * 封装每个 Agent 的执行结果，包含状态、数据、耗时和错误信息。
 * 三个 Agent（分类/评级/预审）统一使用此结构返回。
 * </p>
 */
@Data
public class AgentResult {

    /** Agent 名称（CATEGORY / RATING / PRE_AUDIT） */
    private String agentName;

    /** 执行状态: SUCCESS / FAILED / FALLBACK / SKIPPED */
    private String status;

    /** 返回数据（各 Agent 不同） */
    private Map<String, Object> data;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 重试次数 */
    private int retryCount;

    /** 错误信息（status=FAILED 时填充） */
    private String errorMsg;

    /**
     * 构建成功结果
     */
    public static AgentResult success(String agentName, Map<String, Object> data, long durationMs) {
        AgentResult result = new AgentResult();
        result.setAgentName(agentName);
        result.setStatus("SUCCESS");
        result.setData(data);
        result.setDurationMs(durationMs);
        return result;
    }

    /**
     * 构建降级结果（AI 不可用时使用规则兜底）
     */
    public static AgentResult fallback(String agentName, Map<String, Object> data, long durationMs) {
        AgentResult result = new AgentResult();
        result.setAgentName(agentName);
        result.setStatus("FALLBACK");
        result.setData(data);
        result.setDurationMs(durationMs);
        return result;
    }

    /**
     * 构建失败结果
     */
    public static AgentResult failed(String agentName, long durationMs, String errorMsg) {
        AgentResult result = new AgentResult();
        result.setAgentName(agentName);
        result.setStatus("FAILED");
        result.setDurationMs(durationMs);
        result.setErrorMsg(errorMsg);
        return result;
    }
}
