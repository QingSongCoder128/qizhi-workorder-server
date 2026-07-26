package com.qizhi.message.feign;

import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 工单服务 Feign 客户端
 * <p>
 * 督办消费者需要查询工单当前状态，判断是否需要发送督办消息。
 * </p>
 */
@FeignClient(name = "work-order-service", path = "/api/v1/workorder", configuration = InternalCallFeignConfiguration.class)
public interface WorkOrderFeignClient {

    /**
     * 查询工单状态
     *
     * @param id 工单 ID
     * @return 包含 status、orderNo 等字段的 Map
     */
    @GetMapping("/status/{id}")
    R<Map<String, Object>> getStatus(@PathVariable("id") Long id);
}
