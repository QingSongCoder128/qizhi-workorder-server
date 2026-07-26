package com.qizhi.ai.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * AI 大模型通用客户端
 * 兼容所有 OpenAI Chat Completions 格式的平台（DeepSeek / 阿里百炼 / Moonshot 等）
 * 通过 Nacos 配置 ai.model.api-url / api-key / model-name 即可切换平台
 */
@Slf4j
@Component
@RefreshScope
public class AiModelClient {

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.model.api-url:}")
    private String apiUrl;

    @Value("${ai.model.api-key:}")
    private String apiKey;

    @Value("${ai.model.model-name:}")
    private String modelName;

    @Value("${ai.model.temperature:0.7}")
    private double temperature;

    @Value("${ai.model.max-tokens:2000}")
    private int maxTokens;

    /** AI 接口调用超时（毫秒），从 Nacos 读取，默认 10 秒 */
    @Value("${ai.model.timeout-ms:10000}")
    private int timeoutMs;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 调用大模型 Chat Completions API
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的文本内容
     */
    public String chat(String systemPrompt, String userPrompt) {
        log.info("AI模型调用开始: model={}", modelName);
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
            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("AI 模型返回空响应");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();

            long elapsed = System.currentTimeMillis() - start;
            log.info("AI模型调用成功: 耗时={}ms, 内容长度={}", elapsed, content.length());

            return content;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("AI模型调用失败: 耗时={}ms, 错误={}", elapsed, e.getMessage());
            throw new RuntimeException("AI 模型 API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用大模型并解析 JSON 返回为 Map
     */
    public Map<String, Object> chatAsMap(String systemPrompt, String userPrompt) {
        String content = chat(systemPrompt, userPrompt);
        try {
            return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("解析AI返回JSON失败: contentLength={}, error={}",
                    content != null ? content.length() : 0, e.getMessage());
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
            throw new RuntimeException("无法解析AI返回结果为JSON", e);
        }
    }
}
