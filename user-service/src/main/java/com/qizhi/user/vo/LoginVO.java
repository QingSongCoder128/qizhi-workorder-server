package com.qizhi.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String sessionId;
    private Long userId;
    private String role;
    private String username;
    private String realName;
    private String deptCode;
    private String phone;
    private String email;
    private String avatarUrl;
}
