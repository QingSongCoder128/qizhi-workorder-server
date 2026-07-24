package com.qizhi.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 角色新增/编辑请求体
 * SRS 需求: US-02 角色管理
 */
@Data
public class RoleDTO {

    /** 角色编码（如 DEPT_MANAGER），全局唯一 */
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    /** 角色名称（如 部门经理） */
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    /** 角色描述 */
    private String description;
}
