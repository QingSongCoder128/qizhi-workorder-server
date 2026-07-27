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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                                           @RequestParam(required = false) Integer current,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer pageSize,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String priority,
                                           @RequestParam(required = false) String keyword) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        return R.ok(workOrderService.getMyList(userId, c, s, status, type, priority, keyword));
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
     * 管理员全量工单分页列表（前端 AllListView.vue 调用）
     * 支持关键词、状态、类型筛选
     */
    @Operation(summary = "管理员工单列表")
    @GetMapping("/list")
    public R<PageResult<WorkOrder>> adminList(@RequestParam(required = false) Integer current,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String keyword) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        return R.ok(workOrderService.getAdminList(c, s, status, type, keyword));
    }

    /**
     * 重新提交（前端 RESTful 风格 PUT）
     */
    @Operation(summary = "重新提交（PUT 别名）")
    @PutMapping("/{id}/resubmit")
    public R<Void> resubmitPut(@PathVariable Long id,
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

    /**
     * 实时统计看板数据（供 statistics-service Feign 调用）
     */
    @Operation(summary = "工单统计数据（供 Feign 调用）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(
            @RequestParam(required = false) String deptCode,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String workType) {
        return R.ok(workOrderService.getStats(deptCode, startDate, endDate, workType));
    }

    /**
     * 工单撤销（仅待审批状态可撤销）
     */
    @Operation(summary = "撤销工单")
    @PostMapping("/{id}/revoke")
    public R<Void> revoke(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId) {
        workOrderService.revoke(id, userId);
        return R.ok();
    }

    /**
     * 工单重试（PENDING_AI 状态卡住时，提交人一键重新触发 AI + 审批链路）
     */
    @Operation(summary = "重试处理（待AI处理状态卡住时）")
    @PostMapping("/{id}/retry")
    public R<Void> retry(@PathVariable Long id,
                         @RequestHeader("X-User-Id") Long userId,
                         @RequestHeader("X-Username") String username,
                         @RequestHeader("X-User-Role") String role) {
        workOrderService.retryProcess(id, userId, username, role);
        return R.ok();
    }

    /**
     * 更新工单状态（供 approve-service Feign 回调）
     */
    @Operation(summary = "更新工单状态（内部调用）")
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        workOrderService.updateStatus(id, body.get("status"), body.get("remark"));
        return R.ok();
    }

    /** 附件上传到工单服务模块目录。 */
    @Operation(summary = "附件上传")
    @PostMapping("/attachment/upload")
    public R<Map<String, String>> uploadAttachment(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return R.ok(workOrderService.uploadAttachment(file));
    }

    @Operation(summary = "读取工单附件")
    @GetMapping("/attachment/{filename:.+}")
    public ResponseEntity<Resource> attachment(@PathVariable String filename) throws java.io.IOException {
        Path path = workOrderService.resolveAttachment(filename);
        String detected = Files.probeContentType(path);
        MediaType mediaType = detected == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(detected);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build().toString())
                .body(new FileSystemResource(path));
    }
}
