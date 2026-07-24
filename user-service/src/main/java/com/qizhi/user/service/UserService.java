package com.qizhi.user.service;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.user.dto.LoginDTO;
import com.qizhi.user.dto.ProfileUpdateDTO;
import com.qizhi.user.dto.UserCreateDTO;
import com.qizhi.user.vo.LoginVO;
import com.qizhi.user.vo.UserVO;

public interface UserService {

    LoginVO login(LoginDTO dto);

    void logout(String sessionId);

    UserVO getCurrentUser(String sessionId);

    PageResult<UserVO> getUserPage(Integer current, Integer size, String keyword);

    void createUser(UserCreateDTO dto);

    void resetPassword(Long userId);

    void toggleStatus(Long userId);

    void updateProfile(Long userId, ProfileUpdateDTO dto);

    String getRoleCode(Long userId);
}
