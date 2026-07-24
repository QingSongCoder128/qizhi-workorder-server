package com.qizhi.ai.agent;

import com.qizhi.ai.client.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 评级 Agent - 调用 DeepSeek 判定工单优先级
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RatingAgent {

    private final DeepSeekClient deepSeekClient;

    private static final String SYSTEM_PROMPT = """
            你是一个工单优先级评定助手。根据工单信息判断其紧急程度。
            优先级选项：
            - URGENT：紧急（影响业务运行、多人受影响、有明确时限要求）
            - NORMAL：常规（日常事务、不影响核心业务）
            - LOW：低优先级（建议性需求、优化类）
            请只返回JSON格式，不要有多余文字。""";

    public Map<String, Object> rate(String type, Boolean urgent, String title) {
        log.info("评级Agent开始处理: type={}, urgent={}", type, urgent);
        long start = System.currentTimeMillis();

        try {
            String userPrompt = String.format(
                    "工单类型: %s\n是否标记紧急: %s\n工单标题: %s",
                    type, Boolean.TRUE.equals(urgent) ? "是" : "否", title);
            Map<String, Object> aiResult = deepSeekClient.chatAsMap(SYSTEM_PROMPT, userPrompt);

            String priority = String.valueOf(aiResult.getOrDefault("priority", "NORMAL"));
            String reason = String.valueOf(aiResult.getOrDefault("priorityReason", "AI综合评估"));

            Map<String, Object> result = new HashMap<>();
            result.put("priority", priority);
            result.put("priorityReason", reason);
            result.put("durationMs", System.currentTimeMillis() - start);
            log.info("评级Agent完成: priority={}", priority);
            return result;
        } catch (Exception e) {
            log.warn("评级Agent AI调用失败，使用规则兜底: {}", e.getMessage());
            return fallbackRate(type, urgent, start);
        }
    }

    /** 规则兜底：AI不可用时按紧急标记判定 */
    private Map<String, Object> fallbackRate(String type, Boolean urgent, long start) {
        String priority = Boolean.TRUE.equals(urgent) ? "URGENT" : "NORMAL";
        String reason = Boolean.TRUE.equals(urgent) ? "员工标记为紧急工单" : "默认常规优先级";

        Map<String, Object> result = new HashMap<>();
        result.put("priority", priority);
        result.put("priorityReason", reason);
        result.put("durationMs", System.currentTimeMillis() - start);
        return result;
    }
}
