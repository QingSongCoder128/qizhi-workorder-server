package com.qizhi.user.dto;

import lombok.Data;

@Data
public class ProfileUpdateDTO {

    private String realName;
    private String phone;
    private String email;
    private String deptCode;

    /** 修改密码时传旧密码 */
    private String oldPassword;
    /** 修改密码时传新密码 */
    private String newPassword;
}
