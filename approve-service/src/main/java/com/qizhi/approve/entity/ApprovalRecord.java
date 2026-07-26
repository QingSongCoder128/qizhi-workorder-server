package com.qizhi.approve.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批记录实体
 * <p>
 * 每一条记录对应一个审批节点。
 * 创建时 approverId/approverName 记录分配的审批人，
 * 操作后 operatorId/operatorName 记录实际操作人（转交时两者不同）。
 * status 字段用于支持加签/减签：PENDING/APPROVED/REJECTED/SKIPPED
 * </p>
 */
@Data
@TableName("approval_record")
public class ApprovalRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 审批实例 ID */
    private Long approvalId;

    /** 节点名称（如"部门主管审批"） */
    private String nodeName;

    /** 节点序号（从 1 开始） */
    private Integer nodeOrder;

    /** 节点状态：PENDING/APPROVED/REJECTED/SKIPPED */
    private String status;

    /** 分配的审批人 ID */
    private Long approverId;

    /** 分配的审批人姓名 */
    private String approverName;

    /** 实际操作人 ID（操作后填入） */
    private Long operatorId;

    /** 实际操作人姓名 */
    private String operatorName;

    /** 操作类型：APPROVE/REJECT/TRANSFER/ADD_NODE/REMOVE_NODE */
    private String action;

    /** 审批意见 */
    private String opinion;

    /** 附件 URL */
    private String attachmentUrl;

    /** 操作时间 */
    private LocalDateTime operatedAt;
}
