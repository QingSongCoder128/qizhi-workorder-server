package com.qizhi.statistics.service;

import java.util.Map;

/**
 * 统计服务接口
 * <p>
 * SRS 需求: ST-01 看板数据 + ST-02 缓存机制
 * </p>
 */
public interface StatisticsService {

    /**
     * 看板数据查询（支持多维度筛选）
     *
     * @param deptCode  部门编码（可选）
     * @param startDate 开始日期（可选）
     * @param endDate   结束日期（可选）
     * @param workType  工单类型（可选）
     */
    Map<String, Object> getDashboard(String deptCode, String startDate, String endDate, String workType);

    /** 刷新缓存（清除所有统计缓存 key） */
    void refreshCache();
}
