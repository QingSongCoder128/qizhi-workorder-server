package com.qizhi.message.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dead_letter")
public class DeadLetter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String exchangeName;

    private String queueName;

    private String messageBody;

    private String errorReason;

    private Integer retryCount;

    /** UNRESOLVED/RESOLVED/FAILED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime retriedAt;
}
