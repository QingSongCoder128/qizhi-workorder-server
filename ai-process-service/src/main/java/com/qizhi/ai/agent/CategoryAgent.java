package com.qizhi.ai.agent;

import com.qizhi.ai.client.AiModelClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 分类 Agent - 调用 AI 大模型分析工单文本，识别归属部门
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryAgent {

    private final AiModelClient aiModelClient;

    private static final String SYSTEM_PROMPT = """
            你是一个工单分类助手。你的任务是根据工单信息确认其归属部门。
            
            【核心规则】工单类型与部门存在固定映射，这是最强分类依据：
            - OPS_REPAIR（运维报修）→ DEPT_IT（运维部）
            - ADMIN_PURCHASE（行政采购）→ DEPT_ADMIN（行政部）
            - HR_LEAVE（人事请假）→ DEPT_HR（人事部）
            - TECH_REQUEST（技术需求）→ DEPT_TECH（技术部）
            
            【判断逻辑】
            1. 必须首先根据工单类型确定部门，不得因标题或详情中的关键词而推翻类型映射
            2. 然后分析标题和详情内容，验证内容是否与所选类型一致
            3. 如果内容与类型一致：confidence 取 0.92~0.98
            4. 如果内容与类型明显不符（如类型是"人事请假"但内容是"修电脑"）：
               - 仍然按类型输出部门（不擅自推翻用户选择）
               - confidence 取 0.70~0.80
               - 在 mismatch 字段中说明："内容疑似属于XX类，建议核实工单类型"
            5. 如果内容正常且与类型一致，mismatch 返回空字符串
            
            【输出格式】仅返回JSON，不要有多余文字：
            {"category":"部门编码","confidence":0.95,"mismatch":""}""";

    public Map<String, Object> classify(String type, String title, String detail) {
        log.info("分类Agent开始处理: type={}", type);
        long start = System.currentTimeMillis();
    
        try {
            String typeLabel = switch (type) {
                case "OPS_REPAIR" -> "运维报修";
                case "ADMIN_PURCHASE" -> "行政采购";
                case "HR_LEAVE" -> "人事请假";
                case "TECH_REQUEST" -> "技术需求";
                default -> type;
            };
            String userPrompt = String.format(
                    "工单类型: %s（%s）\n工单标题: %s\n工单详情: %s",
                    type, typeLabel, title, detail);
            Map<String, Object> aiResult = aiModelClient.chatAsMap(SYSTEM_PROMPT, userPrompt);
    
            String category = String.valueOf(aiResult.getOrDefault("category", "DEPT_IT"));
            double confidence = aiResult.containsKey("confidence")
                    ? ((Number) aiResult.get("confidence")).doubleValue() : 0.8;
            String mismatch = String.valueOf(aiResult.getOrDefault("mismatch", ""));
    
            Map<String, Object> result = new HashMap<>();
            result.put("category", category);
            result.put("confidence", confidence);
            result.put("mismatch", mismatch);
            result.put("durationMs", System.currentTimeMillis() - start);
            log.info("分类Agent完成: category={}, confidence={}, mismatch={}", category, confidence, mismatch);
            return result;
        } catch (Exception e) {
            log.warn("分类Agent AI调用失败，使用规则兜底: {}", e.getMessage());
            return fallbackClassify(type, start);
        }
    }

    /** 规则兜底：AI不可用时使用类型映射 */
    private Map<String, Object> fallbackClassify(String type, long start) {
        Map<String, String> fallback = Map.of(
                "OPS_REPAIR", "DEPT_IT",
                "ADMIN_PURCHASE", "DEPT_ADMIN",
                "HR_LEAVE", "DEPT_HR",
                "TECH_REQUEST", "DEPT_TECH");
        String category = fallback.getOrDefault(type, "DEPT_IT");

        Map<String, Object> result = new HashMap<>();
        result.put("category", category);
        result.put("confidence", 0.6);
        result.put("durationMs", System.currentTimeMillis() - start);
        result.put("fallback", true);
        return result;
    }
}
