package com.qizhi.ai.agent;

import com.qizhi.ai.client.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 分类 Agent - 调用 DeepSeek 分析工单文本，识别归属部门
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryAgent {

    private final DeepSeekClient deepSeekClient;

    private static final String SYSTEM_PROMPT = """
            你是一个工单分类助手。根据工单的类型和内容，判断该工单应该归属哪个部门。
            可选的部门编码：
            - DEPT_IT：运维部（设备维修、网络故障、系统问题、IT运维）
            - DEPT_ADMIN：行政部（办公用品采购、行政事务、后勤管理）
            - DEPT_HR：人事部（请假、考勤、入离职、薪资）
            - DEPT_TECH：技术部（软件开发、技术需求、系统升级）
            - DEPT_FIN：财务部（报销、预算、财务审批）
            请只返回JSON格式，不要有多余文字。""";

    public Map<String, Object> classify(String type, String title, String detail) {
        log.info("分类Agent开始处理: type={}", type);
        long start = System.currentTimeMillis();

        try {
            String userPrompt = String.format("工单类型: %s\n工单标题: %s\n工单详情: %s", type, title, detail);
            Map<String, Object> aiResult = deepSeekClient.chatAsMap(SYSTEM_PROMPT, userPrompt);

            String category = String.valueOf(aiResult.getOrDefault("category", "DEPT_IT"));
            double confidence = aiResult.containsKey("confidence")
                    ? ((Number) aiResult.get("confidence")).doubleValue() : 0.8;

            Map<String, Object> result = new HashMap<>();
            result.put("category", category);
            result.put("confidence", confidence);
            result.put("durationMs", System.currentTimeMillis() - start);
            log.info("分类Agent完成: category={}, confidence={}", category, confidence);
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
        return result;
    }
}
