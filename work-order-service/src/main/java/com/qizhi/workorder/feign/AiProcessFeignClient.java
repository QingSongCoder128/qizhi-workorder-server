package com.qizhi.workorder.feign;

import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * AI 处理服务 Feign 客户端
 */
@FeignClient(name = "ai-process-service", path = "/api/v1/ai", configuration = InternalCallFeignConfiguration.class)
public interface AiProcessFeignClient {

    @PostMapping("/process")
    R<Map<String, Object>> process(@RequestBody Map<String, Object> request);
}
