package com.qizhi.workorder.feign;

import com.qizhi.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 消息通知服务 Feign 客户端
 */
@FeignClient(name = "message-service", path = "/api/v1/message")
public interface MessageFeignClient {

    @PostMapping("/send")
    R<Void> sendNotify(@RequestBody Object request);
}
