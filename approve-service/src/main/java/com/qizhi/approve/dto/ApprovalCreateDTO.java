package com.qizhi.approve.dto;

import lombok.Data;

/**
 * 创建审批单请求体（供 Feign 内部调用）
 * <p>
 * work-order-service 通过 Feign 调用此接口，
 * 传入工单信息后，approve-service 自动匹配审批模板并创建多级审批节点。
 * </p>
 */
@Data
public class ApprovalCreateDTO {

    /** 工单 ID */
    private Long workOrderId;

    /** 工单编号 */
    private String orderNo;

    /** 工单标题 */
    private String title;

    /** 工单详情 */
    private String detail;

    /** 提交人 ID */
    private Long submitterId;

    /** 提交人姓名 */
    private String submitterName;

    /** 部门编码（用于匹配审批模板） */
    private String departmentCode;

    /** 工单类型（用于匹配审批模板） */
    private String workType;

    /** 优先级：HIGH / MEDIUM / LOW */
    private String priority;
}
