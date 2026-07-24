package com.qizhi.approve.service;

import com.qizhi.approve.dto.TemplateDTO;
import com.qizhi.approve.entity.ApprovalNode;
import com.qizhi.approve.entity.ApprovalTemplate;
import com.qizhi.common.core.result.PageResult;

import java.util.List;

/**
 * 审批模板管理服务接口
 * <p>
 * SRS 需求: AP-01 审批模板定义
 * 支持配置多级审批节点（如主管→总监→分管领导），
 * 模板存入 Nacos/DB，修改后 approve-service 自动生效。
 * </p>
 */
public interface TemplateService {

    /** 模板列表（分页） */
    PageResult<ApprovalTemplate> getTemplatePage(Integer current, Integer size, String keyword);

    /** 获取模板的全部审批节点 */
    List<ApprovalNode> getTemplateNodes(Long templateId);

    /** 创建模板（含节点定义） */
    ApprovalTemplate createTemplate(TemplateDTO dto);

    /** 编辑模板（含节点定义） */
    void updateTemplate(Long id, TemplateDTO dto);

    /** 删除模板（软删除） */
    void deleteTemplate(Long id);

    /**
     * 根据部门+工单类型匹配最佳模板
     * 匹配优先级：精确匹配(deptCode+workType) > 部门通用 > 全局默认
     *
     * @param deptCode 部门编码
     * @param workType 工单类型
     * @return 匹配的模板，无匹配时返回默认模板
     */
    ApprovalTemplate matchTemplate(String deptCode, String workType);
}
