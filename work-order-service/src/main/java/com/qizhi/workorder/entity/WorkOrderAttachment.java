package com.qizhi.workorder.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("work_order_attachment")
public class WorkOrderAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private String fileName;

    private String fileUrl;

    private String fileType;

    private Long fileSize;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
