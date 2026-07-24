package com.qizhi.approve.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_instance")
public class ApprovalInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private String orderNo;

    private String title;

    private Long submitterId;

    private String submitterName;

    private String detail;

    private Long templateId;

    private Long approverId;

    private String approverName;

    private String departmentCode;

    private String priority;

    private Integer totalNodes;

    private String currentNode;

    private Integer currentOrder;

    /** PENDING/APPROVING/APPROVED/REJECTED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
