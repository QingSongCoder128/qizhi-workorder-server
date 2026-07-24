package com.qizhi.approve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 审批操作请求体
 * <p>
 * SRS 需求: AP-03 审批操作
 * 支持五种操作类型:
 *   - APPROVED: 通过（进入下一节点或完结）
 *   - REJECTED: 驳回（退回提交人）
 *   - TRANSFER: 转交（变更审批人）
 *   - ADD_NODE: 加签（插入新审批节点）
 *   - REMOVE_NODE: 减签（跳过后续节点）
 * </p>
 */
@Data
public class ApprovalActionDTO {

    /** 审批实例 ID */
    @NotNull(message = "审批单ID不能为空")
    private Long approvalId;

    /**
     * 操作类型: APPROVED / REJECTED / TRANSFER / ADD_NODE / REMOVE_NODE
     */
    @NotBlank(message = "审批动作不能为空")
    private String action;

    /** 审批意见（驳回时必填） */
    private String opinion;

    // ---- 转交专用字段 ----
    /** 转交目标审批人 ID（action=TRANSFER 时必填） */
    private Long transferToUserId;
    /** 转交目标审批人姓名 */
    private String transferToUserName;

    // ---- 加签专用字段 ----
    /** 加签节点名称（action=ADD_NODE 时必填） */
    private String addNodeName;
    /** 加签审批人 ID */
    private Long addNodeApproverId;
    /** 加签审批人姓名 */
    private String addNodeApproverName;

    // ---- 减签专用字段 ----
    /** 要跳过的节点序号（action=REMOVE_NODE 时必填） */
    private Integer removeNodeOrder;
}
