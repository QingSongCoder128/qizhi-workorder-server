package com.qizhi.approve.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 审批模板创建/编辑请求体
 * <p>
 * SRS 需求: AP-01 审批模板定义
 * 支持配置多级审批节点（如主管→总监→分管领导），
 * 节点信息可内联传入，存入 approval_node 表。
 * </p>
 */
@Data
public class TemplateDTO {

    /** 模板名称 */
    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    /** 适用部门编码（为空表示通用模板） */
    private String deptCode;

    /** 适用工单类型（为空表示通用） */
    private String workType;

    /** 适用优先级（为空表示通用） */
    private String priority;

    /** 审批节点列表（有序，第1个为第一级审批） */
    private List<NodeDef> nodes;

    /**
     * 审批节点定义（内嵌在模板请求中）
     */
    @Data
    public static class NodeDef {
        /** 节点序号（从1开始） */
        private Integer nodeOrder;
        /** 节点名称（如 "部门主管审批"） */
        @NotBlank(message = "节点名称不能为空")
        private String nodeName;
        /** 审批人角色编码（按角色自动匹配审批人） */
        private String approverRole;
        /** 指定审批人ID（优先级高于角色匹配） */
        private Long approverId;
    }
}
