package com.qizhi.message.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_message")
public class SysMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long receiverId;

    private String title;

    private String content;

    /** APPROVE_NOTIFY/REJECT_NOTIFY/DELAY_REMIND/ESCALATION/SYSTEM */
    private String msgType;

    private String bizType;

    private Long bizId;

    private Boolean isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime readAt;
}
