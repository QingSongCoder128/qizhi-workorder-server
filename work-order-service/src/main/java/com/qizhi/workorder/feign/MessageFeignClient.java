package com.qizhi.workorder.feign;

import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 消息通知服务 Feign 客户端
 */
@FeignClient(name = "message-service", path = "/api/v1/message", configuration = InternalCallFeignConfiguration.class)
public interface MessageFeignClient {

    @PostMapping("/send")
    R<Void> sendNotify(@RequestBody Object request);

    /**
     * 发送延迟督办消息（MS-04/MS-05）
     * 工单进入待审批状态后触发，根据优先级设置不同延迟时长
     */
    @PostMapping("/send-delay-remind")
    R<Void> sendDelayRemind(@RequestBody Object request);
}
