package com.qizhi.ai.config;

import java.util.List;

public record AiRuntimeConfig(
        String apiUrl,
        String apiKey,
        String modelName,
        double temperature,
        int maxTokens,
        int timeoutMs,
        int maxConcurrent,
        int maxRetry,
        long retryIntervalMs,
        List<String> nodeOrder) {
}
