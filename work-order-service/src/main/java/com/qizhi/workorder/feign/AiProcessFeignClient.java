package com.qizhi.workorder.feign;

import com.qizhi.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * AI 处理服务 Feign 客户端
 */
@FeignClient(name = "ai-process-service", path = "/api/v1/ai")
public interface AiProcessFeignClient {

    @PostMapping("/process")
    R<?> process(@RequestBody Object request);
}
