package com.qizhi.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.R;
import com.qizhi.user.entity.SysDepartment;
import com.qizhi.user.entity.SysUser;
import com.qizhi.user.mapper.SysDepartmentMapper;
import com.qizhi.user.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "部门管理")
@RestController
@RequestMapping("/api/v1/dept")
@RequiredArgsConstructor
public class DeptController {

    private final SysDepartmentMapper departmentMapper;
    private final SysUserMapper userMapper;

    @Operation(summary = "部门树")
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<SysDepartment> all = departmentMapper.selectList(
                new LambdaQueryWrapper<SysDepartment>().eq(SysDepartment::getStatus, "ENABLED")
                        .orderByAsc(SysDepartment::getSortOrder));
        List<Map<String, Object>> tree = buildTree(all, 0L);
        return R.ok(tree);
    }

    @Operation(summary = "部门列表（平铺）")
    @GetMapping("/list")
    public R<List<SysDepartment>> list() {
        List<SysDepartment> all = departmentMapper.selectList(
                new LambdaQueryWrapper<SysDepartment>().eq(SysDepartment::getStatus, "ENABLED")
                        .orderByAsc(SysDepartment::getSortOrder));
        return R.ok(all);
    }

    @Operation(summary = "新增部门")
    @PostMapping
    public R<Void> add(@RequestBody SysDepartment dept) {
        // 自动生成部门编码（后端逻辑字段，无需用户填写）
        if (dept.getDeptCode() == null || dept.getDeptCode().isBlank()) {
            dept.setDeptCode("DEPT_" + System.currentTimeMillis());
        }
        if (dept.getParentId() == null) {
            dept.setParentId(0L);
        }
        departmentMapper.insert(dept);
        return R.ok();
    }

    @Operation(summary = "修改部门")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody SysDepartment dept) {
        dept.setId(id);
        departmentMapper.updateById(dept);
        return R.ok();
    }

    /**
     * 删除部门（软删除：状态设为 DISABLED）
     * SRS 需求: US-01 部门管理
     * 禁用前检查：是否有关联的启用状态用户
     */
    @Operation(summary = "删除部门")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        SysDepartment dept = departmentMapper.selectById(id);
        if (dept == null) {
            throw new BusinessException("部门不存在");
        }
        // 检查是否有关联用户
        Long userCount = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeptCode, dept.getDeptCode())
                        .eq(SysUser::getStatus, "ENABLED"));
        if (userCount > 0) {
            throw new BusinessException("该部门下还有 " + userCount + " 个启用用户，请先转移");
        }
        // 软删除
        dept.setStatus("DISABLED");
        departmentMapper.updateById(dept);
        return R.ok();
    }

    private List<Map<String, Object>> buildTree(List<SysDepartment> all, Long parentId) {
        return all.stream()
                .filter(d -> parentId.equals(d.getParentId()))
                .map(d -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", d.getId());
                    node.put("deptCode", d.getDeptCode());
                    node.put("deptName", d.getDeptName());
                    node.put("parentId", d.getParentId());
                    node.put("sortOrder", d.getSortOrder());
                    List<Map<String, Object>> children = buildTree(all, d.getId());
                    if (!children.isEmpty()) {
                        node.put("children", children);
                    }
                    return node;
                })
                .collect(Collectors.toList());
    }
}
