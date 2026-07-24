package com.qizhi.approve.controller;

import com.qizhi.approve.dto.TemplateDTO;
import com.qizhi.approve.entity.ApprovalNode;
import com.qizhi.approve.entity.ApprovalTemplate;
import com.qizhi.approve.service.TemplateService;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审批模板管理控制器
 * <p>
 * SRS 需求: AP-01 审批模板定义
 * 提供模板的增删改查接口，支持管理员配置多级审批流程。
 * 路径前缀: /api/v1/approve/template
 * </p>
 */
@Tag(name = "审批模板管理")
@RestController
@RequestMapping("/api/v1/approve/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /**
     * 模板分页列表
     *
     * @param current 当前页码（默认1）
     * @param size    每页条数（默认10）
     * @param keyword 模板名称关键字（可选）
     */
    @Operation(summary = "模板分页列表")
    @GetMapping("/list")
    public R<PageResult<ApprovalTemplate>> list(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        return R.ok(templateService.getTemplatePage(current, size, keyword));
    }

    /**
     * 获取模板的全部审批节点
     *
     * @param id 模板 ID
     */
    @Operation(summary = "获取模板审批节点")
    @GetMapping("/{id}/nodes")
    public R<List<ApprovalNode>> nodes(@PathVariable Long id) {
        return R.ok(templateService.getTemplateNodes(id));
    }

    /**
     * 创建审批模板（含节点定义）
     *
     * @param dto 模板信息 + 节点列表
     */
    @Operation(summary = "创建审批模板")
    @PostMapping
    public R<ApprovalTemplate> create(@Valid @RequestBody TemplateDTO dto) {
        return R.ok(templateService.createTemplate(dto));
    }

    /**
     * 编辑审批模板（全量替换节点）
     *
     * @param id  模板 ID
     * @param dto 新的模板信息 + 节点列表
     */
    @Operation(summary = "编辑审批模板")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody TemplateDTO dto) {
        templateService.updateTemplate(id, dto);
        return R.ok();
    }

    /**
     * 删除审批模板（软删除）
     *
     * @param id 模板 ID
     */
    @Operation(summary = "删除审批模板")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return R.ok();
    }
}
