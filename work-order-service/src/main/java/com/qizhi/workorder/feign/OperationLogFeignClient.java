package com.qizhi.workorder.feign;

import com.qizhi.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "operation-log-service", path = "/internal/v1/log")
public interface OperationLogFeignClient {

    @PostMapping
    R<Long> create(@RequestBody Map<String, Object> request);
}
