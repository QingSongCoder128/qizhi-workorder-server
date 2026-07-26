package com.qizhi.statistics.service.impl;

import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.result.R;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.statistics.feign.WorkOrderFeignClient;
import com.qizhi.statistics.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 统计服务实现
 * <p>
 * SRS 需求: ST-01 看板数据 + ST-02 Redis 缓存 + 缓存防穿透
 * 数据来源：通过 Feign 调用 work-order-service 实时聚合工单表
 * 缓存策略：正常结果缓存 5 分钟，空结果缓存 1 分钟（防穿透）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class StatisticsServiceImpl implements StatisticsService {

    private final WorkOrderFeignClient workOrderFeignClient;
    private final RedisUtil redisUtil;

    /** 正常缓存 TTL（分钟），从 Nacos 读取，默认 5 */
    @Value("${stats.cache-ttl-minutes:5}")
    private long cacheTtlMinutes;

    /** 空结果缓存 TTL（分钟），防穿透，从 Nacos 读取，默认 1 */
    @Value("${stats.empty-cache-ttl-minutes:1}")
    private long emptyCacheTtlMinutes;

    /** 空结果标记 */
    private static final String EMPTY_MARKER = "__EMPTY__";

    /**
     * 看板数据查询（通过 Feign 实时聚合 + Redis 缓存）
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDashboard(String deptCode, String startDate, String endDate, String workType) {
        String cacheKey = buildCacheKey(deptCode, startDate, endDate, workType);

        // 1. 尝试从 Redis 缓存读取
        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            if (EMPTY_MARKER.equals(cached)) {
                log.debug("命中空结果缓存: key={}", cacheKey);
                return emptyDashboard();
            }
            if (cached instanceof Map) {
                return (Map<String, Object>) cached;
            }
        }

        // 2. 缓存未命中，通过 Feign 调用 work-order-service 实时聚合
        try {
            R<Map<String, Object>> response = workOrderFeignClient.getStats(
                    deptCode, startDate, endDate, workType);
            if (response != null && response.getCode() == 200 && response.getData() != null) {
                Map<String, Object> dashboard = response.getData();
                // 判断是否为空数据
                Object total = dashboard.get("totalCount");
                if (total == null || Integer.valueOf(0).equals(total)) {
                    redisUtil.set(cacheKey, EMPTY_MARKER, emptyCacheTtlMinutes, TimeUnit.MINUTES);
                    return emptyDashboard();
                }
                // 写入缓存
                redisUtil.set(cacheKey, dashboard, cacheTtlMinutes, TimeUnit.MINUTES);
                return dashboard;
            }
        } catch (Exception e) {
            log.error("Feign调用工单统计失败: {}", e.getMessage(), e);
        }

        return emptyDashboard();
    }

    @Override
    public void refreshCache() {
        redisUtil.deleteByPattern(CommonConstants.STATS_DASHBOARD_PREFIX + "*");
        log.info("统计缓存已清除");
    }

    /** 构建缓存 key */
    private String buildCacheKey(String deptCode, String startDate, String endDate, String workType) {
        StringBuilder sb = new StringBuilder(CommonConstants.STATS_DASHBOARD_PREFIX);
        sb.append(deptCode != null ? deptCode : "ALL");
        if (workType != null && !workType.isEmpty()) {
            sb.append(":").append(workType);
        }
        if (startDate != null && !startDate.isEmpty()) {
            sb.append(":").append(startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            sb.append(":").append(endDate);
        }
        return sb.toString();
    }

    /** 空看板数据 */
    private Map<String, Object> emptyDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalCount", 0);
        dashboard.put("pendingCount", 0);
        dashboard.put("approvedCount", 0);
        dashboard.put("completedCount", 0);
        dashboard.put("rejectedCount", 0);
        dashboard.put("timeoutCount", 0);
        dashboard.put("avgApproveMinutes", 0);
        dashboard.put("deptDistribution", Collections.emptyList());
        dashboard.put("trend", Collections.emptyList());
        return dashboard;
    }
}
