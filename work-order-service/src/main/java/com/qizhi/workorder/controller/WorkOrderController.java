package com.qizhi.workorder.controller;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.workorder.dto.WorkOrderSubmitDTO;
import com.qizhi.workorder.entity.WorkOrder;
import com.qizhi.workorder.service.WorkOrderService;
import com.qizhi.workorder.vo.WorkOrderDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "工单管理")
@RestController
@RequestMapping("/api/v1/workorder")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "提交工单")
    @PostMapping("/submit")
    public R<WorkOrder> submit(@Valid @RequestBody WorkOrderSubmitDTO dto,
                               @RequestHeader("X-User-Id") Long userId,
                               @RequestHeader("X-Username") String username) {
        return R.ok(workOrderService.submit(dto, userId, username));
    }

    @Operation(summary = "我的工单列表")
    @GetMapping("/my-list")
    public R<PageResult<WorkOrder>> myList(@RequestHeader("X-User-Id") Long userId,
                                           @RequestParam(defaultValue = "1") Integer current,
                                           @RequestParam(defaultValue = "10") Integer size,
                                           @RequestParam(required = false) String status) {
        return R.ok(workOrderService.getMyList(userId, current, size, status));
    }

    @Operation(summary = "工单详情")
    @GetMapping("/{id}")
    public R<WorkOrderDetailVO> detail(@PathVariable Long id) {
        return R.ok(workOrderService.getDetail(id));
    }

    @Operation(summary = "重新提交（驳回后）")
    @PostMapping("/resubmit/{id}")
    public R<Void> resubmit(@PathVariable Long id,
                            @Valid @RequestBody WorkOrderSubmitDTO dto,
                            @RequestHeader("X-User-Id") Long userId,
                            @RequestHeader("X-Username") String username) {
        workOrderService.resubmit(id, dto, userId, username);
        return R.ok();
    }

    /**
     * 查询工单状态（供 message-service Feign 调用，用于督办判断）
     *
     * @param id 工单 ID
     * @return 工单状态信息（status、orderNo、submitterId）
     */
    @Operation(summary = "工单状态查询（供 Feign 调用）")
    @GetMapping("/status/{id}")
    public R<Map<String, Object>> status(@PathVariable Long id) {
        WorkOrder order = workOrderService.getById(id);
        if (order == null) {
            return R.fail("工单不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", order.getId());
        result.put("status", order.getStatus());
        result.put("orderNo", order.getOrderNo());
        result.put("submitterId", order.getSubmitterId());
        result.put("currentApproverId", order.getCurrentApproverId());
        return R.ok(result);
    }

    /**
     * 工单导出列表（全量查询，供 statistics-service Feign 调用）
     * 支持部门、类型、时间范围筛选
     */
    @Operation(summary = "工单导出列表（供 Feign 调用）")
    @GetMapping("/export-list")
    public R<List<Map<String, Object>>> exportList(
            @RequestParam(required = false) String deptCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return R.ok(workOrderService.getExportList(deptCode, type, startDate, endDate));
    }
}
