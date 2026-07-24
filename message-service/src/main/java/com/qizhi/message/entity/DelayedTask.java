package com.qizhi.message.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("delayed_task")
public class DelayedTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private String orderNo;

    private Long approverId;

    private String priority;

    private Integer delayMinutes;

    private Integer remindCount;

    private Integer maxRemind;

    private Integer escalationLevel;

    /** PENDING/FIRED/ESCALATED/CANCELLED */
    private String status;

    private LocalDateTime fireAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
