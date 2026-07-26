package com.qizhi.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private String phone;
    private String email;
    private String avatarUrl;
    private String deptCode;
    private String deptName;
    private String status;
    private String roleCode;
    private String roleName;
    private LocalDateTime createdAt;
    private java.util.List<String> permissions;
}
