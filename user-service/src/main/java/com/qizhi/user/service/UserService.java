package com.qizhi.user.service;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.user.dto.LoginDTO;
import com.qizhi.user.dto.ProfileUpdateDTO;
import com.qizhi.user.dto.UserCreateDTO;
import com.qizhi.user.vo.LoginVO;
import com.qizhi.user.vo.UserVO;

import java.util.List;
import java.util.Map;

public interface UserService {

    LoginVO login(LoginDTO dto);

    void logout(String sessionId);

    UserVO getCurrentUser(String sessionId);

    PageResult<UserVO> getUserPage(Integer current, Integer size, String keyword, String roleCode);

    void createUser(UserCreateDTO dto);

    void resetPassword(Long userId);

    void toggleStatus(Long userId);

    /** 精确设置用户状态（ENABLED/DISABLED） */
    void setStatus(Long userId, String status);

    void updateProfile(Long userId, ProfileUpdateDTO dto);

    String getRoleCode(Long userId);

    /** 管理员编辑用户（修改 realName/phone/email/deptCode/角色） */
    void updateUser(Long id, Map<String, Object> body);

    /** 按角色编码查询启用状态的用户列表 */
    List<UserVO> getUsersByRole(String roleCode);
}
