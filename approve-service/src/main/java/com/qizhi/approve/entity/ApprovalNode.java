package com.qizhi.approve.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("approval_node")
public class ApprovalNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Integer nodeOrder;

    private String nodeName;

    private String approverRole;

    private Long approverId;
}
