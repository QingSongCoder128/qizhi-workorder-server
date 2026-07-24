package com.qizhi.ai.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * DeepSeek API 客户端
 * 兼容 OpenAI Chat Completions 格式
 */
@Slf4j
@Component
@RefreshScope
public class DeepSeekClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ai.model.api-url:https://api.deepseek.com/chat/completions}")
    private String apiUrl;

    @Value("${ai.model.api-key:}")
    private String apiKey;

    @Value("${ai.model.model-name:deepseek-v4-flash}")
    private String modelName;

    @Value("${ai.model.temperature:0.7}")
    private double temperature;

    @Value("${ai.model.max-tokens:2000}")
    private int maxTokens;

    public DeepSeekClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 DeepSeek Chat Completions API
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的文本内容
     */
    public String chat(String systemPrompt, String userPrompt) {
        log.info("DeepSeek调用开始: model={}", modelName);
        long start = System.currentTimeMillis();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("response_format", Map.of("type", "json_object"));

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);

            String responseBody = response.getBody();
            log.info("DeepSeek原始响应: {}", responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();

            long elapsed = System.currentTimeMillis() - start;
            log.info("DeepSeek调用成功: 耗时={}ms, 内容长度={}", elapsed, content.length());

            return content;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("DeepSeek调用失败: 耗时={}ms, 错误={}", elapsed, e.getMessage());
            throw new RuntimeException("DeepSeek API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 DeepSeek 并解析 JSON 返回为 Map
     */
    public Map<String, Object> chatAsMap(String systemPrompt, String userPrompt) {
        String content = chat(systemPrompt, userPrompt);
        try {
            return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("解析DeepSeek返回JSON失败: content={}, error={}", content, e.getMessage());
            // 尝试从 content 中提取 JSON 部分
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                try {
                    return objectMapper.readValue(
                            content.substring(jsonStart, jsonEnd + 1),
                            new TypeReference<Map<String, Object>>() {});
                } catch (Exception e2) {
                    log.error("二次解析也失败: {}", e2.getMessage());
                }
            }
            throw new RuntimeException("无法解析AI返回结果为JSON: " + content, e);
        }
    }
}
