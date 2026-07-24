package com.qizhi.user.controller;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.user.dto.ProfileUpdateDTO;
import com.qizhi.user.dto.UserCreateDTO;
import com.qizhi.user.service.UserService;
import com.qizhi.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public R<UserVO> me(@RequestHeader("X-Session-Id") String sessionId) {
        return R.ok(userService.getCurrentUser(sessionId));
    }

    @Operation(summary = "用户列表（分页）")
    @GetMapping("/list")
    public R<PageResult<UserVO>> list(@RequestParam(defaultValue = "1") Integer current,
                                      @RequestParam(defaultValue = "10") Integer size,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(userService.getUserPage(current, size, keyword));
    }

    @Operation(summary = "新增用户")
    @PostMapping
    public R<Void> add(@Valid @RequestBody UserCreateDTO dto) {
        userService.createUser(dto);
        return R.ok();
    }

    @Operation(summary = "重置密码")
    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id);
        return R.ok();
    }

    @Operation(summary = "切换用户状态（启用/禁用）")
    @PutMapping("/{id}/status")
    public R<Void> toggleStatus(@PathVariable Long id) {
        userService.toggleStatus(id);
        return R.ok();
    }

    @Operation(summary = "修改个人信息")
    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestHeader("X-User-Id") Long userId,
                                 @RequestBody ProfileUpdateDTO dto) {
        userService.updateProfile(userId, dto);
        return R.ok();
    }
}
