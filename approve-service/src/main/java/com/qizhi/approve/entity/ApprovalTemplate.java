package com.qizhi.approve.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_template")
public class ApprovalTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateName;

    private String deptCode;

    private String workType;

    private String priority;

    /** 审批节点JSON配置 */
    private String nodesJson;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
