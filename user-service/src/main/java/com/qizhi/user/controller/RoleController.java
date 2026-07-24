package com.qizhi.user.controller;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.user.dto.RoleDTO;
import com.qizhi.user.entity.SysRole;
import com.qizhi.user.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理接口
 * <p>
 * SRS 需求: US-02 角色管理
 * 预置普通员工、审批主管、系统管理员三种内置角色，支持自定义角色扩展。
 * Gateway 路由: /api/v1/role/** -> user-service（已配置）
 * </p>
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/api/v1/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /**
     * 角色下拉列表（全量启用状态，用于新增用户时选择角色）
     */
    @Operation(summary = "角色下拉列表")
    @GetMapping("/list")
    public R<List<SysRole>> list() {
        return R.ok(roleService.getRoleList());
    }

    /**
     * 角色分页查询（管理后台角色管理页）
     *
     * @param current 页码（默认1）
     * @param size    每页条数（默认10）
     * @param keyword 搜索关键词（角色名称/编码模糊匹配）
     */
    @Operation(summary = "角色分页查询")
    @GetMapping("/page")
    public R<PageResult<SysRole>> page(@RequestParam(required = false) Integer current,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer pageSize,
                                        @RequestParam(required = false) String keyword) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        return R.ok(roleService.getRolePage(c, s, keyword));
    }

    /**
     * 新增角色
     */
    @Operation(summary = "新增角色")
    @PostMapping
    public R<Void> add(@Valid @RequestBody RoleDTO dto) {
        roleService.createRole(dto);
        return R.ok();
    }

    /**
     * 编辑角色
     */
    @Operation(summary = "编辑角色")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody RoleDTO dto) {
        roleService.updateRole(id, dto);
        return R.ok();
    }

    /**
     * 删除角色（软删除，内置角色不可删）
     */
    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return R.ok();
    }
}
