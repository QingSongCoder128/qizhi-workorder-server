package com.qizhi.workorder.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("work_order")
public class WorkOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long submitterId;

    private String submitterName;

    /** OPS_REPAIR/ADMIN_PURCHASE/HR_LEAVE/TECH_REQUEST */
    private String type;

    private String title;

    private String detail;

    private String departmentCode;

    private Boolean urgent;

    /** URGENT/NORMAL/LOW */
    private String priority;

    /** PENDING_AI/PENDING_APPROVE/APPROVING/APPROVED/REJECTED/COMPLETED */
    private String status;

    private Long currentApproverId;

    private String currentApproverName;

    private String currentNode;

    private String aiCategory;

    private Double aiConfidence;

    private String aiPriorityReason;

    private String aiSuggestion;

    private String aiSensitiveWords;

    private Boolean aiAbnormal;

    private String seataXid;

    private Integer versionNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
