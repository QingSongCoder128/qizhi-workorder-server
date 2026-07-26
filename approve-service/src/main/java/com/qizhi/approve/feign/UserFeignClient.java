package com.qizhi.approve.feign;

import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 用户服务 Feign 客户端
 */
@FeignClient(name = "user-service", path = "/api/v1/user", configuration = InternalCallFeignConfiguration.class)
public interface UserFeignClient {

    @GetMapping("/by-username")
    R<Map<String, Object>> getByUsername(@RequestParam("username") String username);

    /** 按角色编码查询用户列表（创建审批单时按角色匹配审批人） */
    @GetMapping("/by-role")
    R<List<Map<String, Object>>> getByRole(@RequestParam("roleCode") String roleCode);
}
