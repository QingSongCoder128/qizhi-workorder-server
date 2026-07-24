package com.qizhi.user.controller;

import com.qizhi.common.core.result.R;
import com.qizhi.user.dto.LoginDTO;
import com.qizhi.user.service.UserService;
import com.qizhi.user.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        return R.ok(userService.login(loginDTO));
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        userService.logout(sessionId);
        return R.ok();
    }
}
