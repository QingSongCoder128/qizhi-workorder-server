package com.qizhi.ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_task_log")
public class AiTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private Long workOrderId;

    /** CATEGORY/RATING/PRE_AUDIT */
    private String agentName;

    private String inputText;

    private String outputText;

    /** SUCCESS/FAILED/RETRYING */
    private String status;

    private Integer durationMs;

    private Integer retryCount;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
