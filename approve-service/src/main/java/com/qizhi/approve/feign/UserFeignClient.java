package com.qizhi.approve.feign;

import com.qizhi.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 用户服务 Feign 客户端（转交审批时按用户名查找用户）
 */
@FeignClient(name = "user-service", path = "/api/v1/user")
public interface UserFeignClient {

    @GetMapping("/by-username")
    R<Map<String, Object>> getByUsername(@RequestParam("username") String username);
}
