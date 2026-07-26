package com.qizhi.statistics.feign;

import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 工单服务 Feign 客户端（统计服务调用）
 * <p>
 * 提供工单列表查询和导出专用接口。
 * </p>
 */
@FeignClient(name = "work-order-service", path = "/api/v1/workorder", configuration = InternalCallFeignConfiguration.class)
public interface WorkOrderFeignClient {

    /** 工单列表查询（分页） */
    @GetMapping("/my-list")
    R<?> getWorkOrderList(@RequestParam("current") Integer current,
                          @RequestParam("size") Integer size,
                          @RequestParam(value = "status", required = false) String status);

    /**
     * 工单导出列表（全量，支持筛选条件）
     *
     * @param deptCode  部门编码（可选）
     * @param type      工单类型（可选）
     * @param startDate 开始日期 yyyy-MM-dd（可选）
     * @param endDate   结束日期 yyyy-MM-dd（可选）
     */
    @GetMapping("/export-list")
    R<List<Map<String, Object>>> getExportList(
            @RequestParam(value = "deptCode", required = false) String deptCode,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate);

    /** 实时统计看板数据 */
    @GetMapping("/stats")
    R<Map<String, Object>> getStats(
            @RequestParam(value = "deptCode", required = false) String deptCode,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "workType", required = false) String workType);
}
