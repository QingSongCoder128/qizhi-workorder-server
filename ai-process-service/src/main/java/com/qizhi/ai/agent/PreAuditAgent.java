package com.qizhi.ai.agent;

import com.qizhi.ai.client.AiModelClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 预审 Agent - 调用 AI 大模型校验内容完整性和合规性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreAuditAgent {

    private final AiModelClient aiModelClient;

    private static final String SYSTEM_PROMPT = """
            你是一个工单预审助手。请检查工单内容是否完整、合规。
            检查项：
            1. 标题和详情是否足够清晰完整（详情至少应有具体描述，不能太笼统）
            2. 是否包含敏感词或不当内容（如暴力、歧视、投诉、举报、泄密等）
            3. 是否符合正常工单的提交规范
            请只返回JSON格式，不要有多余文字。""";

    public Map<String, Object> preAudit(String title, String detail) {
        log.info("预审Agent开始处理...");
        long start = System.currentTimeMillis();

        try {
            String userPrompt = String.format("工单标题: %s\n工单详情: %s", title, detail);
            Map<String, Object> aiResult = aiModelClient.chatAsMap(SYSTEM_PROMPT, userPrompt);

            String suggestion = String.valueOf(aiResult.getOrDefault("suggestion", "内容完整，建议通过"));
            String sensitiveWords = String.valueOf(aiResult.getOrDefault("sensitiveWords", ""));
            boolean pass = aiResult.containsKey("pass")
                    ? Boolean.parseBoolean(String.valueOf(aiResult.get("pass")))
                    : true;

            Map<String, Object> result = new HashMap<>();
            result.put("suggestion", suggestion);
            result.put("sensitiveWords", sensitiveWords);
            result.put("pass", pass);
            result.put("durationMs", System.currentTimeMillis() - start);
            log.info("预审Agent完成: pass={}, sensitiveWords={}", pass, sensitiveWords);
            return result;
        } catch (Exception e) {
            log.warn("预审Agent AI调用失败，使用规则兜底: {}", e.getMessage());
            return fallbackPreAudit(title, detail, start);
        }
    }

    /** 规则兜底：AI不可用时使用基础校验 */
    private Map<String, Object> fallbackPreAudit(String title, String detail, long start) {
        List<String> issues = new ArrayList<>();
        if (!StringUtils.hasText(title)) {
            issues.add("标题为空");
        }
        if (!StringUtils.hasText(detail) || detail.length() < 10) {
            issues.add("工单详情过短，请补充更多信息");
        }

        List<String> sensitiveWords = List.of("暴力", "歧视", "投诉", "举报", "泄密");
        List<String> found = new ArrayList<>();
        String fullText = (title != null ? title : "") + (detail != null ? detail : "");
        for (String word : sensitiveWords) {
            if (fullText.contains(word)) {
                found.add(word);
            }
        }

        String suggestion = issues.isEmpty() ? "内容完整，建议通过" : "建议补充：" + String.join("；", issues);

        Map<String, Object> result = new HashMap<>();
        result.put("suggestion", suggestion);
        result.put("sensitiveWords", found.isEmpty() ? "" : String.join(",", found));
        result.put("pass", issues.isEmpty());
        result.put("durationMs", System.currentTimeMillis() - start);
        result.put("fallback", true);
        return result;
    }
}
