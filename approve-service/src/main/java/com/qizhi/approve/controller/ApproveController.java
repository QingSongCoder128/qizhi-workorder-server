package com.qizhi.approve.controller;

import com.qizhi.approve.dto.ApprovalActionDTO;
import com.qizhi.approve.dto.ApprovalCreateDTO;
import com.qizhi.approve.entity.ApprovalInstance;
import com.qizhi.approve.entity.ApprovalRecord;
import com.qizhi.approve.feign.UserFeignClient;
import com.qizhi.approve.service.ApproveService;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    private final UserFeignClient userFeignClient;

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
                                                    @RequestParam(required = false) Integer current,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer pageSize,
                                                    @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
                                                    @RequestParam(required = false, defaultValue = "asc") String order) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        return R.ok(approveService.getPending(userId, c, s, sortBy, order));
    }

    /**
     * 待审批统计（总数/紧急数/超时数/今日已处理数，供前端统计卡片展示）
     */
    @Operation(summary = "待审批统计")
    @GetMapping("/pending/stats")
    public R<Map<String, Object>> pendingStats(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(approveService.getPendingStats(userId));
    }

    /**
     * 统计 APPROVING（审批中）实例数（供 work-order-service 统计看板 Feign 调用）
     */
    @Operation(summary = "审批中实例数（供 Feign 调用）")
    @GetMapping("/count/approving")
    public R<Long> countApproving() {
        return R.ok(approveService.countApproving());
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

    /**
     * 根据工单 ID 查询审批记录（供 work-order-service Feign 调用，展示审批时间线）
     */
    @Operation(summary = "按工单ID查询审批记录（供 Feign 调用）")
    @GetMapping("/by-work-order/{workOrderId}/records")
    public R<List<ApprovalRecord>> recordsByWorkOrderId(@PathVariable Long workOrderId) {
        return R.ok(approveService.getRecordsByWorkOrderId(workOrderId));
    }

    // ==================== 前端适配端点 ====================

    /**
     * 审批详情（前端 ApproveDetail.vue 调用）
     * 返回 {workOrder: {...}, nodes: [...]}
     */
    @Operation(summary = "审批详情（前端适配）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(approveService.getDetail(id));
    }

    /**
     * 审批通过（前端 RESTful 风格）
     * 内部转发到 action() 方法，action=APPROVED
     */
    @Operation(summary = "审批通过")
    @PostMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id,
                           @RequestBody Map<String, String> body,
                           @RequestHeader("X-User-Id") Long userId,
                           @RequestHeader(value = "X-Username", required = false) String username) {
        ApprovalActionDTO dto = new ApprovalActionDTO();
        dto.setApprovalId(id);
        dto.setAction("APPROVE");
        dto.setOpinion(body.get("comment"));
        applyVersion(dto, body);
        approveService.action(dto, userId, username);
        return R.ok();
    }

    /**
     * 审批驳回（前端 RESTful 风格）
     * 内部转发到 action() 方法，action=REJECTED
     */
    @Operation(summary = "审批驳回")
    @PostMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id,
                          @RequestBody Map<String, String> body,
                          @RequestHeader("X-User-Id") Long userId,
                          @RequestHeader(value = "X-Username", required = false) String username) {
        ApprovalActionDTO dto = new ApprovalActionDTO();
        dto.setApprovalId(id);
        dto.setAction("REJECT");
        dto.setOpinion(body.get("comment"));
        applyVersion(dto, body);
        approveService.action(dto, userId, username);
        return R.ok();
    }

    /**
     * 转交审批（前端 RESTful 风格）
     * 支持两种传参方式：
     * 1. 直传 transferToUserId + transferToUserName（新版前端下拉选择）
     * 2. 传 targetUsername，后端 Feign 查询用户 ID（兼容旧版）
     */
    @Operation(summary = "转交审批")
    @PostMapping("/{id}/transfer")
    public R<Void> transfer(@PathVariable Long id,
                            @RequestBody Map<String, Object> body,
                            @RequestHeader("X-User-Id") Long userId,
                            @RequestHeader(value = "X-Username", required = false) String username) {
        ApprovalActionDTO dto = new ApprovalActionDTO();
        dto.setApprovalId(id);
        dto.setAction("TRANSFER");
        dto.setOpinion(body.get("reason") != null ? String.valueOf(body.get("reason")) : null);
        applyVersion(dto, body);

        // 方式1：前端直传 userId + userName
        if (body.get("transferToUserId") != null) {
            dto.setTransferToUserId(Long.valueOf(String.valueOf(body.get("transferToUserId"))));
            dto.setTransferToUserName(body.get("transferToUserName") != null
                    ? String.valueOf(body.get("transferToUserName")) : null);
        } else {
            // 方式2：通过用户名查询目标审批人 ID（兼容旧版）
            String targetUsername = body.get("targetUsername") != null
                    ? String.valueOf(body.get("targetUsername")) : null;
            dto.setTransferToUserName(targetUsername);
            if (targetUsername != null) {
                try {
                    R<Map<String, Object>> userResult = userFeignClient.getByUsername(targetUsername);
                    if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                        Object userIdObj = userResult.getData().get("id");
                        dto.setTransferToUserId(Long.valueOf(String.valueOf(userIdObj)));
                        Object realName = userResult.getData().get("realName");
                        if (realName != null) {
                            dto.setTransferToUserName(String.valueOf(realName));
                        }
                    }
                } catch (Exception e) {
                    // 用户查询失败时仍然继续
                }
            }
        }
        approveService.action(dto, userId, username);
        return R.ok();
    }

    /**
     * 加签（前端 RESTful 风格）
     */
    @Operation(summary = "加签")
    @PostMapping("/{id}/add-node")
    public R<Void> addNode(@PathVariable Long id,
                           @RequestBody Map<String, Object> body,
                           @RequestHeader("X-User-Id") Long userId,
                           @RequestHeader(value = "X-Username", required = false) String username) {
        ApprovalActionDTO dto = new ApprovalActionDTO();
        dto.setApprovalId(id);
        dto.setAction("ADD_NODE");
        dto.setAddNodeName((String) body.get("nodeName"));
        if (body.get("approverId") != null) {
            dto.setAddNodeApproverId(Long.valueOf(String.valueOf(body.get("approverId"))));
        }
        dto.setAddNodeApproverName((String) body.get("approverName"));
        dto.setOpinion(body.get("reason") != null ? String.valueOf(body.get("reason")) : null);
        applyVersion(dto, body);
        approveService.action(dto, userId, username);
        return R.ok();
    }

    /**
     * 减签（前端 RESTful 风格）
     */
    @Operation(summary = "减签")
    @PostMapping("/{id}/remove-node")
    public R<Void> removeNode(@PathVariable Long id,
                              @RequestBody Map<String, Object> body,
                              @RequestHeader("X-User-Id") Long userId,
                              @RequestHeader(value = "X-Username", required = false) String username) {
        ApprovalActionDTO dto = new ApprovalActionDTO();
        dto.setApprovalId(id);
        dto.setAction("REMOVE_NODE");
        if (body.get("nodeOrder") != null) {
            dto.setRemoveNodeOrder(Integer.valueOf(String.valueOf(body.get("nodeOrder"))));
        }
        dto.setOpinion(body.get("reason") != null ? String.valueOf(body.get("reason")) : null);
        applyVersion(dto, body);
        approveService.action(dto, userId, username);
        return R.ok();
    }

    private static void applyVersion(ApprovalActionDTO dto, Map<String, ?> body) {
        Object version = body.get("versionNo");
        if (version != null) {
            dto.setVersionNo(Integer.valueOf(String.valueOf(version)));
        }
    }
}
