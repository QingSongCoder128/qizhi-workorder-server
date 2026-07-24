package com.qizhi.statistics.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.statistics.entity.StatDailySummary;
import com.qizhi.statistics.mapper.StatDailySummaryMapper;
import com.qizhi.statistics.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 统计服务实现
 * <p>
 * SRS 需求: ST-01 看板数据 + ST-02 Redis 缓存 + 缓存防穿透
 * 缓存策略：
 *   - 正常结果缓存 5 分钟
 *   - 空结果缓存 1 分钟（防穿透，避免恶意请求直接打数据库）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class StatisticsServiceImpl implements StatisticsService {

    private final StatDailySummaryMapper summaryMapper;
    private final RedisUtil redisUtil;

    /** 正常缓存 TTL（分钟），从 Nacos 读取，默认 5 */
    @Value("${stats.cache-ttl-minutes:5}")
    private long cacheTtlMinutes;

    /** 空结果缓存 TTL（分钟），防穿透，从 Nacos 读取，默认 1 */
    @Value("${stats.empty-cache-ttl-minutes:1}")
    private long emptyCacheTtlMinutes;

    /** 看板查询最大返回行数，从 Nacos 读取，默认 30 */
    @Value("${stats.dashboard-limit:30}")
    private int dashboardLimit;

    /** 空结果标记 */
    private static final String EMPTY_MARKER = "__EMPTY__";

    /**
     * 看板数据查询（支持多维度筛选 + 缓存防穿透）
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDashboard(String deptCode, String startDate, String endDate, String workType) {
        // 构建缓存 key（包含所有筛选条件）
        String cacheKey = buildCacheKey(deptCode, startDate, endDate, workType);

        // 1. 尝试从 Redis 缓存读取
        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            // 检查是否为空结果标记（防穿透）
            if (EMPTY_MARKER.equals(cached)) {
                log.debug("命中空结果缓存: key={}", cacheKey);
                return emptyDashboard();
            }
            if (cached instanceof Map) {
                return (Map<String, Object>) cached;
            }
        }

        // 2. 缓存未命中，从数据库查询
        LambdaQueryWrapper<StatDailySummary> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(deptCode)) {
            wrapper.eq(StatDailySummary::getDeptCode, deptCode);
        }
        if (StringUtils.hasText(workType)) {
            wrapper.eq(StatDailySummary::getWorkType, workType);
        }
        if (StringUtils.hasText(startDate)) {
            wrapper.ge(StatDailySummary::getStatDate, startDate);
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le(StatDailySummary::getStatDate, endDate);
        }
        wrapper.orderByDesc(StatDailySummary::getStatDate);
        wrapper.last("LIMIT " + dashboardLimit);
        List<StatDailySummary> summaries = summaryMapper.selectList(wrapper);

        // 3. 缓存防穿透：空结果也缓存
        if (summaries == null || summaries.isEmpty()) {
            log.debug("查询结果为空，缓存空结果标记: key={}", cacheKey);
            redisUtil.set(cacheKey, EMPTY_MARKER, emptyCacheTtlMinutes, TimeUnit.MINUTES);
            return emptyDashboard();
        }

        // 4. 汇总统计
        int totalCount = summaries.stream().mapToInt(s -> s.getTotalCount() != null ? s.getTotalCount() : 0).sum();
        int pendingCount = summaries.stream().mapToInt(s -> s.getPendingCount() != null ? s.getPendingCount() : 0).sum();
        int completedCount = summaries.stream().mapToInt(s -> s.getCompletedCount() != null ? s.getCompletedCount() : 0).sum();
        int rejectedCount = summaries.stream().mapToInt(s -> s.getRejectedCount() != null ? s.getRejectedCount() : 0).sum();
        int avgMinutes = summaries.isEmpty() ? 0 :
                summaries.stream().mapToInt(s -> s.getAvgApproveMinutes() != null ? s.getAvgApproveMinutes() : 0).sum() / summaries.size();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalCount", totalCount);
        dashboard.put("pendingCount", pendingCount);
        dashboard.put("completedCount", completedCount);
        dashboard.put("rejectedCount", rejectedCount);
        dashboard.put("avgApproveMinutes", avgMinutes);
        dashboard.put("dailySummaries", summaries);

        // 5. 写入缓存
        redisUtil.set(cacheKey, dashboard, cacheTtlMinutes, TimeUnit.MINUTES);

        return dashboard;
    }

    @Override
    public void refreshCache() {
        // 删除所有统计缓存 key（常用部门 + ALL）
        List<String> deptCodes = List.of("ALL", "DEPT_IT", "DEPT_ADMIN", "DEPT_HR", "DEPT_TECH", "DEPT_FIN");
        for (String dept : deptCodes) {
            redisUtil.delete(CommonConstants.STATS_DASHBOARD_PREFIX + dept);
        }
        log.info("统计缓存已清除");
    }

    /** 构建缓存 key */
    private String buildCacheKey(String deptCode, String startDate, String endDate, String workType) {
        StringBuilder sb = new StringBuilder(CommonConstants.STATS_DASHBOARD_PREFIX);
        sb.append(deptCode != null ? deptCode : "ALL");
        if (StringUtils.hasText(workType)) {
            sb.append(":").append(workType);
        }
        if (StringUtils.hasText(startDate)) {
            sb.append(":").append(startDate);
        }
        if (StringUtils.hasText(endDate)) {
            sb.append(":").append(endDate);
        }
        return sb.toString();
    }

    /** 空看板数据（查询无结果时返回） */
    private Map<String, Object> emptyDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalCount", 0);
        dashboard.put("pendingCount", 0);
        dashboard.put("completedCount", 0);
        dashboard.put("rejectedCount", 0);
        dashboard.put("avgApproveMinutes", 0);
        dashboard.put("dailySummaries", Collections.emptyList());
        return dashboard;
    }
}
