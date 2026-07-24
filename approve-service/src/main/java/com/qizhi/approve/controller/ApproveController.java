package com.qizhi.approve.controller;

import com.qizhi.approve.dto.ApprovalActionDTO;
import com.qizhi.approve.dto.ApprovalCreateDTO;
import com.qizhi.approve.entity.ApprovalInstance;
import com.qizhi.approve.entity.ApprovalRecord;
import com.qizhi.approve.service.ApproveService;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审批管理控制器
 * <p>
 * SRS 需求: AP-02 ~ AP-04
 * 提供审批单创建（Feign）、待办列表、审批操作、审批记录查询等接口。
 * 路径前缀: /api/v1/approve
 * </p>
 */
@Tag(name = "审批管理")
@RestController
@RequestMapping("/api/v1/approve")
@RequiredArgsConstructor
public class ApproveController {

    private final ApproveService approveService;

    /**
     * 创建审批单（供 work-order-service Feign 内部调用）
     */
    @Operation(summary = "创建审批单（供 Feign 调用）")
    @PostMapping("/create")
    public R<ApprovalInstance> create(@RequestBody ApprovalCreateDTO request) {
        return R.ok(approveService.createApproval(request));
    }

    /**
     * 待审批列表（按当前登录用户过滤）
     */
    @Operation(summary = "待审批列表")
    @GetMapping("/pending")
    public R<PageResult<ApprovalInstance>> pending(@RequestHeader("X-User-Id") Long userId,
                                                    @RequestParam(defaultValue = "1") Integer current,
                                                    @RequestParam(defaultValue = "10") Integer size) {
        return R.ok(approveService.getPending(userId, current, size));
    }

    /**
     * 审批操作（通过/驳回/转交/加签/减签）
     * <p>
     * 五种操作类型: APPROVED / REJECTED / TRANSFER / ADD_NODE / REMOVE_NODE
     * </p>
     */
    @Operation(summary = "审批操作（通过/驳回/转交/加签/减签）")
    @PostMapping("/action")
    public R<Void> action(@Valid @RequestBody ApprovalActionDTO dto,
                          @RequestHeader("X-User-Id") Long userId,
                          @RequestHeader(value = "X-Username", required = false) String username) {
        approveService.action(dto, userId, username);
        return R.ok();
    }

    /**
     * 查询审批单的全部审批记录（节点列表）
     *
     * @param approvalId 审批实例 ID
     */
    @Operation(summary = "审批记录列表")
    @GetMapping("/{approvalId}/records")
    public R<List<ApprovalRecord>> records(@PathVariable Long approvalId) {
        return R.ok(approveService.getRecords(approvalId));
    }
}
