package com.qizhi.gateway.controller;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.qizhi.common.core.result.R;
import com.qizhi.gateway.filter.RateLimitFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 限流配置管理接口
 * <p>
 * 修改后同时写入 Nacos（gateway-service.yaml）实现持久化，
 * RateLimitFilter 通过 @RefreshScope 自动热加载新值，重启不丢失。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/config/rate-limit")
@CrossOrigin(origins = {"http://127.0.0.1:5173", "http://localhost:5173"})
@RequiredArgsConstructor
public class RateLimitConfigController {

    private static final String DATA_ID = "gateway-service.yaml";
    private static final String GROUP = "DEFAULT_GROUP";

    private final RateLimitFilter rateLimitFilter;
    private final NacosConfigManager nacosConfigManager;

    @GetMapping
    public R<Map<String, Object>> get() {
        return R.ok(rateLimitFilter.currentConfiguration());
    }

    @PutMapping
    public R<Map<String, Object>> update(@Valid @RequestBody UpdateRequest request) {
        // 1. 立即更新内存（即时生效）
        rateLimitFilter.updateLimits(request.userQps, request.ipQps, request.submitQps,
                request.whiteUsers, request.whiteIps);

        // 2. 持久化到 Nacos（重启不丢失）
        try {
            publishToNacos(request);
        } catch (Exception e) {
            log.error("限流配置写入Nacos失败（内存已生效，重启后将恢复旧值）: {}", e.getMessage());
        }

        return R.ok(rateLimitFilter.currentConfiguration());
    }

    /**
     * 将限流参数写入 Nacos gateway-service.yaml 的 gateway.rate-limit 节点
     */
    @SuppressWarnings("unchecked")
    private void publishToNacos(UpdateRequest request) throws Exception {
        ConfigService configService = nacosConfigManager.getConfigService();
        String existing = configService.getConfig(DATA_ID, GROUP, 5000);
        Map<String, Object> root = yaml().load(existing);
        if (root == null) {
            root = new LinkedHashMap<>();
        }

        Map<String, Object> gateway = child(root, "gateway");
        Map<String, Object> rateLimit = child(gateway, "rate-limit");
        rateLimit.put("user-qps", request.userQps);
        rateLimit.put("ip-qps", request.ipQps);
        rateLimit.put("submit-qps", request.submitQps);
        rateLimit.put("white-users", String.join(",", request.whiteUsers));
        rateLimit.put("white-ips", String.join(",", request.whiteIps));

        String content = yaml().dump(root);
        if (!configService.publishConfig(DATA_ID, GROUP, content, "yaml")) {
            throw new IllegalStateException("Nacos publishConfig 返回 false");
        }
        log.info("限流配置已持久化到Nacos: userQps={}, ipQps={}, submitQps={}",
                request.userQps, request.ipQps, request.submitQps);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }

    @Data
    public static class UpdateRequest {
        @Min(1)
        private int userQps;
        @Min(1)
        private int ipQps;
        @Min(1)
        private int submitQps;
        private List<String> whiteUsers = List.of();
        private List<String> whiteIps = List.of();
    }
}
