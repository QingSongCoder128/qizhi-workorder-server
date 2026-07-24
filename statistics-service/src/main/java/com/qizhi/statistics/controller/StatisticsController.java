package com.qizhi.statistics.controller;

import com.alibaba.excel.EasyExcel;
import com.qizhi.common.core.result.R;
import com.qizhi.statistics.dto.WorkOrderExportDTO;
import com.qizhi.statistics.feign.WorkOrderFeignClient;
import com.qizhi.statistics.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据统计控制器
 * <p>
 * SRS 需求: ST-01 ~ ST-03
 * 提供看板数据查询、Excel 导出、缓存刷新等接口。
 * </p>
 */
@Slf4j
@Tag(name = "数据统计")
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final WorkOrderFeignClient workOrderFeignClient;

    /** 看板数据（支持多维度筛选） */
    @Operation(summary = "看板数据")
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard(@RequestParam(required = false) String deptCode,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate,
                                            @RequestParam(required = false) String workType) {
        return R.ok(statisticsService.getDashboard(deptCode, startDate, endDate, workType));
    }

    /** 工单数据导出 Excel（EasyExcel 4.x） */
    @Operation(summary = "工单数据导出 Excel")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String deptCode,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = URLEncoder.encode("工单数据导出", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");

            List<WorkOrderExportDTO> exportData = fetchExportData(deptCode, type, startDate, endDate);
            EasyExcel.write(response.getOutputStream(), WorkOrderExportDTO.class)
                    .sheet("工单数据").doWrite(exportData);

            log.info("Excel导出成功: count={}, deptCode={}, type={}", exportData.size(), deptCode, type);
        } catch (IOException e) {
            log.error("Excel导出失败: {}", e.getMessage(), e);
            throw new RuntimeException("导出失败", e);
        }
    }

    @Operation(summary = "手动刷新缓存")
    @PostMapping("/refresh")
    public R<Void> refresh() {
        statisticsService.refreshCache();
        return R.ok();
    }

    /** 从 work-order-service 获取导出数据并转换为 ExportDTO */
    private List<WorkOrderExportDTO> fetchExportData(String deptCode, String type,
                                                      String startDate, String endDate) {
        List<WorkOrderExportDTO> result = new ArrayList<>();
        try {
            R<List<Map<String, Object>>> response = workOrderFeignClient.getExportList(
                    deptCode, type, startDate, endDate);
            if (response != null && response.getCode() == 200 && response.getData() != null) {
                for (Map<String, Object> row : response.getData()) {
                    WorkOrderExportDTO dto = new WorkOrderExportDTO();
                    dto.setOrderNo(str(row.get("orderNo")));
                    dto.setTitle(str(row.get("title")));
                    dto.setSubmitterName(str(row.get("submitterName")));
                    dto.setType(str(row.get("type")));
                    dto.setPriority(str(row.get("priority")));
                    dto.setStatus(str(row.get("status")));
                    dto.setCurrentApproverName(str(row.get("currentApproverName")));
                    dto.setDepartmentCode(str(row.get("departmentCode")));
                    dto.setCreatedAt(str(row.get("createdAt")));
                    dto.setCompletedAt(str(row.get("completedAt")));
                    result.add(dto);
                }
            }
        } catch (Exception e) {
            log.error("Feign调用获取导出数据失败: {}", e.getMessage(), e);
        }
        return result;
    }

    /** 安全转 String */
    private String str(Object obj) {
        return obj != null ? String.valueOf(obj) : "";
    }
}
