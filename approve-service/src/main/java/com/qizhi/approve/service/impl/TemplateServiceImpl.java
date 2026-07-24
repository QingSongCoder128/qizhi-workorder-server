package com.qizhi.approve.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.approve.dto.TemplateDTO;
import com.qizhi.approve.entity.ApprovalNode;
import com.qizhi.approve.entity.ApprovalTemplate;
import com.qizhi.approve.mapper.ApprovalNodeMapper;
import com.qizhi.approve.mapper.ApprovalTemplateMapper;
import com.qizhi.approve.service.TemplateService;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 审批模板管理服务实现
 * <p>
 * SRS 需求: AP-01
 * 模板 CRUD + 节点管理 + 自动匹配。
 * 创建/编辑模板时，节点列表与模板在同一事务中原子操作。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateServiceImpl implements TemplateService {

    private final ApprovalTemplateMapper templateMapper;
    private final ApprovalNodeMapper nodeMapper;

    @Override
    public PageResult<ApprovalTemplate> getTemplatePage(Integer current, Integer size, String keyword) {
        Page<ApprovalTemplate> page = new Page<>(current, size);
        LambdaQueryWrapper<ApprovalTemplate> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(ApprovalTemplate::getTemplateName, keyword);
        }
        wrapper.eq(ApprovalTemplate::getStatus, "ENABLED")
                .orderByDesc(ApprovalTemplate::getCreatedAt);
        Page<ApprovalTemplate> result = templateMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public List<ApprovalNode> getTemplateNodes(Long templateId) {
        return nodeMapper.selectList(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getTemplateId, templateId)
                        .orderByAsc(ApprovalNode::getNodeOrder));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalTemplate createTemplate(TemplateDTO dto) {
        // 保存模板
        ApprovalTemplate template = new ApprovalTemplate();
        template.setTemplateName(dto.getTemplateName());
        template.setDeptCode(dto.getDeptCode());
        template.setWorkType(dto.getWorkType());
        template.setPriority(dto.getPriority());
        template.setStatus("ENABLED");
        templateMapper.insert(template);

        // 保存审批节点（按 nodeOrder 排序）
        saveNodes(template.getId(), dto.getNodes());

        log.info("创建审批模板: id={}, name={}, 节点数={}",
                template.getId(), template.getTemplateName(),
                dto.getNodes() != null ? dto.getNodes().size() : 0);
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(Long id, TemplateDTO dto) {
        ApprovalTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException("模板不存在");
        }

        template.setTemplateName(dto.getTemplateName());
        template.setDeptCode(dto.getDeptCode());
        template.setWorkType(dto.getWorkType());
        template.setPriority(dto.getPriority());
        templateMapper.updateById(template);

        // 删除旧节点，重新插入新节点（全量替换策略）
        nodeMapper.delete(new LambdaQueryWrapper<ApprovalNode>()
                .eq(ApprovalNode::getTemplateId, id));
        saveNodes(id, dto.getNodes());

        log.info("编辑审批模板: id={}, name={}", id, dto.getTemplateName());
    }

    @Override
    public void deleteTemplate(Long id) {
        ApprovalTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException("模板不存在");
        }
        // 软删除
        template.setStatus("DISABLED");
        templateMapper.updateById(template);
        log.info("删除审批模板: id={}", id);
    }

    @Override
    public ApprovalTemplate matchTemplate(String deptCode, String workType) {
        // 优先级1: 精确匹配 deptCode + workType
        ApprovalTemplate matched = templateMapper.selectOne(
                new LambdaQueryWrapper<ApprovalTemplate>()
                        .eq(ApprovalTemplate::getDeptCode, deptCode)
                        .eq(ApprovalTemplate::getWorkType, workType)
                        .eq(ApprovalTemplate::getStatus, "ENABLED")
                        .last("LIMIT 1"));
        if (matched != null) return matched;

        // 优先级2: 部门通用模板（workType 为空）
        matched = templateMapper.selectOne(
                new LambdaQueryWrapper<ApprovalTemplate>()
                        .eq(ApprovalTemplate::getDeptCode, deptCode)
                        .isNull(ApprovalTemplate::getWorkType)
                        .eq(ApprovalTemplate::getStatus, "ENABLED")
                        .last("LIMIT 1"));
        if (matched != null) return matched;

        // 优先级3: 全局默认模板（deptCode 和 workType 均为空）
        matched = templateMapper.selectOne(
                new LambdaQueryWrapper<ApprovalTemplate>()
                        .isNull(ApprovalTemplate::getDeptCode)
                        .isNull(ApprovalTemplate::getWorkType)
                        .eq(ApprovalTemplate::getStatus, "ENABLED")
                        .last("LIMIT 1"));
        if (matched != null) return matched;

        // 无模板时返回 null，调用方使用默认单节点审批
        log.warn("未匹配到审批模板: deptCode={}, workType={}, 使用默认单节点审批", deptCode, workType);
        return null;
    }

    /**
     * 批量保存审批节点
     *
     * @param templateId 模板 ID
     * @param nodes      节点定义列表
     */
    private void saveNodes(Long templateId, List<TemplateDTO.NodeDef> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        for (int i = 0; i < nodes.size(); i++) {
            TemplateDTO.NodeDef def = nodes.get(i);
            ApprovalNode node = new ApprovalNode();
            node.setTemplateId(templateId);
            node.setNodeOrder(def.getNodeOrder() != null ? def.getNodeOrder() : i + 1);
            node.setNodeName(def.getNodeName());
            node.setApproverRole(def.getApproverRole());
            node.setApproverId(def.getApproverId());
            nodeMapper.insert(node);
        }
    }
}
