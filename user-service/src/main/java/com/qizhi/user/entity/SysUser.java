package com.qizhi.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String realName;

    private String phone;

    private String email;

    /** 头像文件URL */
    private String avatarUrl;

    private String deptCode;

    /** ENABLED/DISABLED/LOCKED */
    private String status;

    /** 连续登录失败次数 */
    private Integer loginFail;

    /** 锁定截止时间 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lockTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
