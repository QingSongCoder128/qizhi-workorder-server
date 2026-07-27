package com.qizhi.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.user.dto.RoleDTO;
import com.qizhi.user.entity.SysPermission;
import com.qizhi.user.entity.SysRole;
import com.qizhi.user.entity.SysRolePermission;
import com.qizhi.user.entity.SysUserRole;
import com.qizhi.user.mapper.SysPermissionMapper;
import com.qizhi.user.mapper.SysRoleMapper;
import com.qizhi.user.mapper.SysRolePermissionMapper;
import com.qizhi.user.mapper.SysUserRoleMapper;
import com.qizhi.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 角色管理服务实现
 * <p>
 * SRS 需求: US-02
 * 管理角色的增删改查，内置角色（EMPLOYEE/APPROVER/ADMIN）受保护不可删除。
 * 删除采用软删除方式（状态设为 DISABLED），保留数据完整性。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;

    /** 内置角色编码，受保护不允许删除 */
    private static final Set<String> BUILT_IN_ROLES = Set.of("EMPLOYEE", "APPROVER", "ADMIN");

    @Override
    public List<SysRole> getRoleList() {
        // 只返回启用状态的角色，用于前端下拉选择
        return roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getStatus, "ENABLED")
                        .orderByAsc(SysRole::getId));
    }

    @Override
    public PageResult<SysRole> getRolePage(Integer current, Integer size, String keyword) {
        Page<SysRole> page = new Page<>(current, size);
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();

        // 按角色名称或编码模糊搜索
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysRole::getRoleName, keyword)
                    .or().like(SysRole::getRoleCode, keyword));
        }
        wrapper.orderByAsc(SysRole::getId);

        Page<SysRole> result = roleMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public void createRole(RoleDTO dto) {
        // 校验角色编码唯一性
        Long count = roleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, dto.getRoleCode()));
        if (count > 0) {
            throw new BusinessException("角色编码已存在: " + dto.getRoleCode());
        }

        SysRole role = new SysRole();
        role.setRoleCode(dto.getRoleCode());
        role.setRoleName(dto.getRoleName());
        role.setDescription(dto.getDescription());
        role.setStatus("ENABLED");
        roleMapper.insert(role);

        log.info("新增角色: code={}, name={}", dto.getRoleCode(), dto.getRoleName());
    }

    @Override
    public void updateRole(Long id, RoleDTO dto) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 如果修改了角色编码，检查唯一性（排除自身）
        if (!role.getRoleCode().equals(dto.getRoleCode())) {
            Long count = roleMapper.selectCount(
                    new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, dto.getRoleCode()));
            if (count > 0) {
                throw new BusinessException("角色编码已存在: " + dto.getRoleCode());
            }
        }

        role.setRoleCode(dto.getRoleCode());
        role.setRoleName(dto.getRoleName());
        role.setDescription(dto.getDescription());
        roleMapper.updateById(role);

        log.info("编辑角色: id={}, code={}", id, dto.getRoleCode());
    }

    @Override
    public void deleteRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 内置角色不允许删除，保护系统基础权限结构
        if (BUILT_IN_ROLES.contains(role.getRoleCode())) {
            throw new BusinessException("内置角色不允许删除: " + role.getRoleCode());
        }

        // 检查是否有用户关联了该角色
        Long userCount = userRoleMapper.selectCount(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (userCount > 0) {
            throw new BusinessException("该角色下还有 " + userCount + " 个用户，请先移除用户关联");
        }

        // 软删除：将状态设为 DISABLED（保留数据，便于追溯）
        role.setStatus("DISABLED");
        roleMapper.updateById(role);

        log.info("删除角色: id={}, code={}", id, role.getRoleCode());
    }

    @Override
    public List<SysPermission> getAllPermissions() {
        return permissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSortOrder));
    }

    @Override
    public List<String> getRolePermissionCodes(Long roleId) {
        return rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId))
                .stream().map(SysRolePermission::getPermissionCode).toList();
    }

    @Override
    @Transactional
    public void updateRolePermissions(Long roleId, List<String> permissionCodes) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        // 先删除原有权限
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        // 再插入新权限
        if (permissionCodes != null && !permissionCodes.isEmpty()) {
            for (String code : permissionCodes) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionCode(code);
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("更新角色权限: roleId={}, permissions={}", roleId, permissionCodes);
    }
}

