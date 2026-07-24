package com.qizhi.workorder.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("work_order_history")
public class WorkOrderHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private String fromStatus;

    private String toStatus;

    private Long operatorId;

    private String operatorName;

    private String remark;

    private LocalDateTime createdAt;
}
