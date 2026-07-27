package com.qizhi.user.service;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.user.dto.RoleDTO;
import com.qizhi.user.entity.SysPermission;
import com.qizhi.user.entity.SysRole;

import java.util.List;

/**
 * 角色管理服务接口
 * <p>
 * SRS 需求: US-02 角色管理
 * 预置普通员工、审批主管、系统管理员三种内置角色，支持自定义角色扩展。
 * </p>
 */
public interface RoleService {

    /**
     * 角色下拉列表（全量启用状态角色，用于前端下拉选择）
     *
     * @return 角色列表
     */
    List<SysRole> getRoleList();

    /**
     * 角色分页查询（管理后台角色列表页）
     *
     * @param current 当前页码
     * @param size    每页条数
     * @param keyword 搜索关键词（角色名称/编码模糊匹配）
     * @return 分页结果
     */
    PageResult<SysRole> getRolePage(Integer current, Integer size, String keyword);

    /**
     * 新增角色
     *
     * @param dto 角色信息（roleCode 必填且唯一）
     * @throws com.qizhi.common.core.exception.BusinessException 角色编码已存在时抛出
     */
    void createRole(RoleDTO dto);

    /**
     * 编辑角色
     *
     * @param id  角色 ID
     * @param dto 角色信息
     */
    void updateRole(Long id, RoleDTO dto);

    /**
     * 删除角色（软删除，将状态设为 DISABLED）
     * 内置角色（EMPLOYEE/APPROVER/ADMIN）不允许删除。
     *
     * @param id 角色 ID
     */
    void deleteRole(Long id);

    /**
     * 查询全部权限列表
     */
    List<SysPermission> getAllPermissions();

    /**
     * 查询某角色已分配的权限编码
     */
    List<String> getRolePermissionCodes(Long roleId);

    /**
     * 更新角色权限分配（先删后插）
     */
    void updateRolePermissions(Long roleId, List<String> permissionCodes);
}
