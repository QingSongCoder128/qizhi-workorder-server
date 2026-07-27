package com.qizhi.user.controller;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.user.dto.ProfileUpdateDTO;
import com.qizhi.user.dto.UserCreateDTO;
import com.qizhi.user.entity.SysUser;
import com.qizhi.user.mapper.SysUserMapper;
import com.qizhi.user.service.UserService;
import com.qizhi.user.vo.UserVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SysUserMapper userMapper;

    @Value("${storage.avatar.root:user-service/uploads/avatar}")
    private String avatarStorageRoot;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public R<UserVO> me(@RequestHeader("X-Session-Id") String sessionId) {
        return R.ok(userService.getCurrentUser(sessionId));
    }

    @Operation(summary = "用户列表（分页）")
    @GetMapping("/list")
    public R<PageResult<UserVO>> list(@RequestParam(required = false) Integer current,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String roleCode,
                                      @RequestParam(required = false) String deptCode) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        return R.ok(userService.getUserPage(c, s, keyword, roleCode, deptCode));
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
    public R<Void> toggleStatus(@PathVariable Long id,
                                @RequestBody(required = false) java.util.Map<String, Object> body) {
        if (body != null && body.containsKey("status")) {
            Object statusVal = body.get("status");
            // 前端传 0/1 或 "ENABLED"/"DISABLED"
            String targetStatus;
            if (statusVal instanceof Number) {
                targetStatus = ((Number) statusVal).intValue() == 1 ? "ENABLED" : "DISABLED";
            } else {
                targetStatus = "1".equals(String.valueOf(statusVal)) || "ENABLED".equalsIgnoreCase(String.valueOf(statusVal))
                        ? "ENABLED" : "DISABLED";
            }
            userService.setStatus(id, targetStatus);
        } else {
            userService.toggleStatus(id);
        }
        return R.ok();
    }

    @Operation(summary = "修改个人信息")
    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestHeader("X-User-Id") Long userId,
                                 @RequestBody ProfileUpdateDTO dto) {
        userService.updateProfile(userId, dto);
        return R.ok();
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public R<Void> changePassword(@RequestHeader("X-User-Id") Long userId,
                                  @RequestBody Map<String, String> body) {
        ProfileUpdateDTO dto = new ProfileUpdateDTO();
        dto.setOldPassword(body.get("oldPassword"));
        dto.setNewPassword(body.get("newPassword"));
        userService.updateProfile(userId, dto);
        return R.ok();
    }

    /**
     * 按用户名查询用户信息（供其他微服务 Feign 调用）
     */
    @Operation(summary = "按用户名查询（供 Feign 调用）")
    @GetMapping("/by-username")
    public R<Map<String, Object>> byUsername(@RequestParam String username) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            return R.fail("用户不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("realName", user.getRealName());
        result.put("deptCode", user.getDeptCode());
        result.put("status", user.getStatus());
        return R.ok(result);
    }

    /**
     * 编辑用户（管理员操作，前端 UserManage.vue 调用）
     */
    @Operation(summary = "编辑用户")
    @PutMapping("/{id}")
    public R<Void> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        userService.updateUser(id, body);
        return R.ok();
    }

    /**
     * 按角色编码查询用户列表（供审批转交/加签选择审批人）
     */
    @Operation(summary = "按角色编码查询用户列表")
    @GetMapping("/by-role")
    public R<List<UserVO>> getByRole(@RequestParam String roleCode) {
        return R.ok(userService.getUsersByRole(roleCode));
    }

    @Operation(summary = "用户统计")
    @GetMapping("/stats")
    public R<Map<String, Long>> stats() {
        return R.ok(userService.getUserStats());
    }

    @Operation(summary = "导出用户列表")
    @GetMapping("/export")
    public void exportUsers(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String roleCode,
                            @RequestParam(required = false) String deptCode,
                            HttpServletResponse response) {
        List<UserVO> users = userService.exportUsers(keyword, roleCode, deptCode);
        try {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=users.csv");
            // UTF-8 BOM for Excel compatibility
            response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            StringBuilder sb = new StringBuilder();
            sb.append("账号,姓名,部门,角色,手机号,邮箱,状态\n");
            for (UserVO u : users) {
                sb.append(csv(u.getUsername())).append(',');
                sb.append(csv(u.getRealName())).append(',');
                sb.append(csv(u.getDeptName())).append(',');
                sb.append(csv(u.getRoleCode())).append(',');
                sb.append(csv(u.getPhone())).append(',');
                sb.append(csv(u.getEmail())).append(',');
                sb.append("ENABLED".equals(u.getStatus()) ? "启用" : "禁用").append('\n');
            }
            response.getWriter().write(sb.toString());
            response.getWriter().flush();
        } catch (Exception e) {
            throw new BusinessException("导出失败");
        }
    }

    @Operation(summary = "批量操作")
    @PutMapping("/batch")
    public R<Void> batch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("ids");
        String action = (String) body.get("action");
        if (rawIds == null || rawIds.isEmpty() || action == null) {
            return R.fail("参数不完整");
        }
        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        userService.batchOperate(ids, action);
        return R.ok();
    }

    private String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 头像上传（存本地 uploads/avatar/ 目录，更新数据库 avatar_url 字段）
     */
    @Operation(summary = "头像上传")
    @PostMapping("/avatar/upload")
    public R<Map<String, String>> uploadAvatar(@RequestHeader("X-User-Id") Long userId,
                                               @RequestParam("file") MultipartFile file) {
        // 校验文件类型和大小
        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase() : "";
        if (!List.of(".jpg", ".jpeg", ".png", ".gif", ".webp").contains(ext)) {
            throw new BusinessException("头像仅支持 jpg/png/gif/webp 格式");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new BusinessException("头像文件不能超过 2MB");
        }
        try {
            Path root = avatarRoot();
            String fileName = java.util.UUID.randomUUID().toString().replace("-", "") + ext;
            Path destination = root.resolve(fileName).normalize();
            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

            String avatarUrl = "/api/v1/user/avatar/" + fileName;
            // 更新数据库
            SysUser user = new SysUser();
            user.setId(userId);
            user.setAvatarUrl(avatarUrl);
            userMapper.updateById(user);

            Map<String, String> result = new HashMap<>();
            result.put("url", avatarUrl);
            return R.ok(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("头像上传失败");
        }
    }

    /**
     * 获取头像文件（无需登录，img 标签无法携带 Session 头）
     */
    @Operation(summary = "获取头像文件")
    @GetMapping("/avatar/{filename}")
    public void getAvatar(@PathVariable String filename, HttpServletResponse response) {
        try {
            // 防止路径穿越
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                response.setStatus(400);
                return;
            }
            Path root = avatarRoot();
            Path file = root.resolve(filename).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                response.setStatus(404);
                return;
            }
            String ext = filename.substring(filename.lastIndexOf(".")).toLowerCase();
            String contentType = switch (ext) {
                case ".png" -> "image/png";
                case ".gif" -> "image/gif";
                case ".webp" -> "image/webp";
                default -> "image/jpeg";
            };
            response.setContentType(contentType);
            response.setContentLengthLong(Files.size(file));
            try (java.io.InputStream input = Files.newInputStream(file);
                 java.io.OutputStream os = response.getOutputStream()) {
                input.transferTo(os);
                os.flush();
            }
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    private Path avatarRoot() {
        try {
            Path root = Paths.get(avatarStorageRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            return root;
        } catch (Exception exception) {
            throw new BusinessException("头像存储目录不可用");
        }
    }
}
