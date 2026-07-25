package com.qizhi.workorder.feign;

import com.qizhi.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 审批服务 Feign 客户端
 */
@FeignClient(name = "approve-service", path = "/api/v1/approve")
public interface ApproveFeignClient {

    @PostMapping("/create")
    R<?> createApproval(@RequestBody Object request);

    /** 审批中（APPROVING）实例数，供统计看板使用 */
    @GetMapping("/count/approving")
    R<Long> countApproving();
}
