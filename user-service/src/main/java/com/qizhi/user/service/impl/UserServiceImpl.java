package com.qizhi.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.user.dto.LoginDTO;
import com.qizhi.user.dto.ProfileUpdateDTO;
import com.qizhi.user.dto.UserCreateDTO;
import com.qizhi.user.entity.SysDepartment;
import com.qizhi.user.entity.SysRole;
import com.qizhi.user.entity.SysUser;
import com.qizhi.user.entity.SysUserRole;
import com.qizhi.user.entity.SysRolePermission;
import com.qizhi.user.mapper.SysDepartmentMapper;
import com.qizhi.user.mapper.SysRoleMapper;
import com.qizhi.user.mapper.SysUserMapper;
import com.qizhi.user.mapper.SysUserRoleMapper;
import com.qizhi.user.mapper.SysRolePermissionMapper;
import com.qizhi.user.service.UserService;
import com.qizhi.user.util.PasswordValidator;
import com.qizhi.user.vo.LoginVO;
import com.qizhi.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysDepartmentMapper departmentMapper;
    private final RedisUtil redisUtil;

    private static final BCryptPasswordEncoder BCrypt = new BCryptPasswordEncoder();

    /** 会话踢出模式，从 Nacos 读取，kick-old=踢旧 / reject-new=拒绝新登录 */
    @Value("${user.session.kick-mode:kick-old}")
    private String kickMode;

    /** 会话过期时间（分钟），从 Nacos 读取，默认 30 */
    @Value("${user.session.expire-minutes:30}")
    private int sessionExpireMinutes;

    /** 连续登录失败锁定次数，从 Nacos 读取，默认 5 */
    @Value("${user.login.lock-count:5}")
    private int loginLockCount;

    /** 账号锁定时长（分钟），从 Nacos 读取，默认 15 */
    @Value("${user.login.lock-minutes:15}")
    private int loginLockMinutes;

    /** 重置密码默认值，从 Nacos 读取 */
    @Value("${user.default-password:Admin123}")
    private String defaultPassword;

    @Override
    // Authentication failures are business outcomes: their counters and lock
    // timestamps must commit even though the method returns a BusinessException.
    @Transactional(rollbackFor = Exception.class, noRollbackFor = BusinessException.class)
    public synchronized LoginVO login(LoginDTO dto) {
        // 查询用户
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        // 检查账号锁定
        if ("LOCKED".equals(user.getStatus()) && user.getLockTime() != null
                && user.getLockTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(403, "账号已锁定，请" + loginLockMinutes + "分钟后重试");
        }
        if ("LOCKED".equals(user.getStatus())
                && (user.getLockTime() == null || !user.getLockTime().isAfter(LocalDateTime.now()))) {
            user.setStatus("ENABLED");
            user.setLoginFail(0);
            user.setLockTime(null);
            userMapper.updateById(user);
        }

        // 密码校验
        if (!BCrypt.matches(dto.getPassword(), user.getPassword())) {
            // 登录失败计数
            int failCount = (user.getLoginFail() == null ? 0 : user.getLoginFail()) + 1;
            user.setLoginFail(failCount);
            // Five failures is the security ceiling required by the SRS.
            // Runtime configuration may tighten it, but must never weaken it.
            int effectiveLockCount = Math.min(Math.max(loginLockCount, 1), 5);
            if (failCount >= effectiveLockCount) {
                user.setStatus("LOCKED");
                user.setLockTime(LocalDateTime.now().plusMinutes(loginLockMinutes));
            }
            userMapper.updateById(user);
            throw new BusinessException(401, "用户名或密码错误，连续失败" + failCount + "次");
        }

        // 检查账号状态
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 登录成功，重置失败计数
        user.setLoginFail(0);
        user.setLockTime(null);
        if ("LOCKED".equals(user.getStatus())) {
            user.setStatus("ENABLED");
        }
        userMapper.updateById(user);

        // 查询角色
        String roleCode = getRoleCode(user.getId());
        List<String> permissions = getPermissions(user.getId());

        // 生成 sessionId 并存入 Redis
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        // 会话踢出机制：根据 Nacos 配置的 kick-mode 决定策略
        String userSessionKey = "user:session:" + user.getId();
        Object oldSessionId = redisUtil.get(userSessionKey);
        if (oldSessionId != null) {
            if ("reject-new".equals(kickMode)) {
                // 拒绝新登录模式：已有会话时拒绝本次登录
                throw new BusinessException("当前账号已在其他设备登录，请先登出");
            }
            // 踢旧模式（默认）：删除旧会话，允许新登录
            redisUtil.delete(CommonConstants.SESSION_PREFIX + oldSessionId);
            log.info("踢出旧会话: userId={}, oldSessionId={}", user.getId(), oldSessionId);
        }
        // 记录用户当前活跃的 sessionId（反向索引，用于踢出机制）
        redisUtil.set(userSessionKey, sessionId, sessionExpireMinutes, TimeUnit.MINUTES);

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userId", user.getId());
        sessionData.put("username", user.getUsername());
        sessionData.put("role", roleCode);
        sessionData.put("realName", user.getRealName());
        sessionData.put("deptCode", user.getDeptCode());
        sessionData.put("permissions", permissions);
        redisUtil.set(CommonConstants.SESSION_PREFIX + sessionId, sessionData,
                sessionExpireMinutes, TimeUnit.MINUTES);

        log.info("用户登录成功: {}", user.getUsername());
        return LoginVO.builder()
                .sessionId(sessionId)
                .userId(user.getId())
                .role(roleCode)
                .username(user.getUsername())
                .realName(user.getRealName())
                .deptCode(user.getDeptCode())
                .phone(user.getPhone())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .permissions(permissions)
                .build();
    }

    @Override
    public void logout(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            // 先获取 session 中的 userId，删除反向索引
            Object obj = redisUtil.get(CommonConstants.SESSION_PREFIX + sessionId);
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> session = (Map<String, Object>) obj;
                Long userId = Long.valueOf(String.valueOf(session.get("userId")));
                redisUtil.delete("user:session:" + userId);
            }
            redisUtil.delete(CommonConstants.SESSION_PREFIX + sessionId);
            log.info("用户登出: sessionId={}", sessionId);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public UserVO getCurrentUser(String sessionId) {
        Object obj = redisUtil.get(CommonConstants.SESSION_PREFIX + sessionId);
        if (obj == null) {
            throw new BusinessException(401, "会话已过期");
        }
        Map<String, Object> session = (Map<String, Object>) obj;
        Long userId = Long.valueOf(String.valueOf(session.get("userId")));
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return toUserVO(user);
    }

    @Override
    public PageResult<UserVO> getUserPage(Integer current, Integer size, String keyword, String roleCode, String deptCode) {
        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        // 部门筛选
        if (StringUtils.hasText(deptCode)) {
            wrapper.eq(SysUser::getDeptCode, deptCode);
        }
        // 角色筛选：通过 sys_user_role 关联表查找拥有指定角色的用户 ID
        if (StringUtils.hasText(roleCode)) {
            SysRole role = roleMapper.selectOne(
                    new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
            if (role == null) {
                return PageResult.of((long) current, (long) size, 0L, Collections.emptyList());
            }
            List<SysUserRole> userRoles = userRoleMapper.selectList(
                    new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, role.getId()));
            if (userRoles.isEmpty()) {
                return PageResult.of((long) current, (long) size, 0L, Collections.emptyList());
            }
            List<Long> userIds = userRoles.stream().map(SysUserRole::getUserId).collect(Collectors.toList());
            wrapper.in(SysUser::getId, userIds);
        }
        wrapper.orderByDesc(SysUser::getCreatedAt);
        Page<SysUser> result = userMapper.selectPage(page, wrapper);

        List<UserVO> voList = result.getRecords().stream().map(this::toUserVO).collect(Collectors.toList());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), voList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUser(UserCreateDTO dto) {
        // 密码复杂度校验（SRS 安全性需求：至少8位，含大小写字母和数字）
        PasswordValidator.validate(dto.getPassword());

        // 检查用户名唯一
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setDeptCode(dto.getDeptCode());
        user.setStatus("ENABLED");
        user.setLoginFail(0);
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.insert(user);

        // 分配角色
        if (dto.getRoleCodes() != null && !dto.getRoleCodes().isEmpty()) {
            for (String roleCode : dto.getRoleCodes()) {
                SysRole role = roleMapper.selectOne(
                        new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
                if (role != null) {
                    SysUserRole ur = new SysUserRole();
                    ur.setUserId(user.getId());
                    ur.setRoleId(role.getId());
                    userRoleMapper.insert(ur);
                }
            }
        } else {
            // 默认分配 EMPLOYEE 角色
            SysRole role = roleMapper.selectOne(
                    new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "EMPLOYEE"));
            if (role != null) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(user.getId());
                ur.setRoleId(role.getId());
                userRoleMapper.insert(ur);
            }
        }
    }

    @Override
    public void resetPassword(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 重置密码为默认值（符合复杂度要求）
        PasswordValidator.validate(defaultPassword);
        user.setPassword(BCrypt.encode(defaultPassword));
        userMapper.updateById(user);
    }

    @Override
    public void toggleStatus(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if ("ENABLED".equals(user.getStatus())) {
            user.setStatus("DISABLED");
        } else {
            user.setStatus("ENABLED");
        }
        userMapper.updateById(user);
    }

    @Override
    public void setStatus(Long userId, String status) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);
    }

    @Override
    public void updateProfile(Long userId, ProfileUpdateDTO dto) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (StringUtils.hasText(dto.getRealName())) {
            user.setRealName(dto.getRealName());
        }
        if (dto.getPhone() != null) {
            user.setPhone(dto.getPhone());
        }
        if (dto.getEmail() != null) {
            user.setEmail(dto.getEmail());
        }
        if (StringUtils.hasText(dto.getDeptCode())) {
            user.setDeptCode(dto.getDeptCode());
        }
        // 修改密码
        if (StringUtils.hasText(dto.getOldPassword()) && StringUtils.hasText(dto.getNewPassword())) {
            if (!BCrypt.matches(dto.getOldPassword(), user.getPassword())) {
                throw new BusinessException("旧密码不正确");
            }
            // 新密码复杂度校验
            PasswordValidator.validate(dto.getNewPassword());
            user.setPassword(BCrypt.encode(dto.getNewPassword()));
        }
        userMapper.updateById(user);
    }

    @Override
    public String getRoleCode(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return "EMPLOYEE";
        }
        Long roleId = userRoles.get(0).getRoleId();
        SysRole role = roleMapper.selectById(roleId);
        return role != null ? role.getRoleCode() : "EMPLOYEE";
    }

    /**
     * 管理员编辑用户（修改 realName/phone/email/deptCode/角色）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(Long id, Map<String, Object> body) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (body.get("realName") != null) {
            user.setRealName(String.valueOf(body.get("realName")));
        }
        if (body.get("phone") != null) {
            user.setPhone(String.valueOf(body.get("phone")));
        }
        if (body.get("email") != null) {
            user.setEmail(String.valueOf(body.get("email")));
        }
        if (body.get("deptCode") != null) {
            user.setDeptCode(String.valueOf(body.get("deptCode")));
        }
        userMapper.updateById(user);

        // 更新角色（如果传入了 roleCodes）
        if (body.containsKey("roleCodes")) {
            Object codes = body.get("roleCodes");
            if (codes instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> roleCodes = (List<String>) codes;
                // 删除旧角色
                userRoleMapper.delete(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
                // 分配新角色
                for (String roleCode : roleCodes) {
                    SysRole role = roleMapper.selectOne(
                            new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
                    if (role != null) {
                        SysUserRole ur = new SysUserRole();
                        ur.setUserId(id);
                        ur.setRoleId(role.getId());
                        userRoleMapper.insert(ur);
                    }
                }
            }
        }
    }

    private UserVO toUserVO(SysUser user) {
        UserVO vo = UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .deptCode(user.getDeptCode())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
        // 填充角色信息
        String roleCode = getRoleCode(user.getId());
        vo.setRoleCode(roleCode);
        vo.setPermissions(getPermissions(user.getId()));
        SysRole role = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
        if (role != null) {
            vo.setRoleName(role.getRoleName());
        }
        // 填充部门名称
        if (StringUtils.hasText(user.getDeptCode())) {
            SysDepartment dept = departmentMapper.selectOne(
                    new LambdaQueryWrapper<SysDepartment>().eq(SysDepartment::getDeptCode, user.getDeptCode()));
            if (dept != null) {
                vo.setDeptName(dept.getDeptName());
            }
        }
        return vo;
    }

    @Override
    public List<UserVO> getUsersByRole(String roleCode) {
        // 1. 查找角色 ID
        SysRole role = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
        if (role == null) {
            return Collections.emptyList();
        }
        // 2. 查找拥有该角色的所有用户 ID
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, role.getId()));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> userIds = userRoles.stream().map(SysUserRole::getUserId).collect(Collectors.toList());
        // 3. 查询启用状态的用户
        List<SysUser> users = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, userIds)
                        .eq(SysUser::getStatus, "ENABLED"));
        return users.stream().map(this::toUserVO).collect(Collectors.toList());
    }

    @Override
    public Map<String, Long> getUserStats() {
        Long total = userMapper.selectCount(null);
        Long enabled = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, "ENABLED"));
        Long disabled = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, "DISABLED"));
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("enabled", enabled);
        stats.put("disabled", disabled);
        return stats;
    }

    @Override
    public List<UserVO> exportUsers(String keyword, String roleCode, String deptCode) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        if (StringUtils.hasText(deptCode)) {
            wrapper.eq(SysUser::getDeptCode, deptCode);
        }
        if (StringUtils.hasText(roleCode)) {
            SysRole role = roleMapper.selectOne(
                    new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
            if (role == null) {
                return Collections.emptyList();
            }
            List<SysUserRole> userRoles = userRoleMapper.selectList(
                    new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, role.getId()));
            if (userRoles.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> userIds = userRoles.stream().map(SysUserRole::getUserId).collect(Collectors.toList());
            wrapper.in(SysUser::getId, userIds);
        }
        wrapper.orderByDesc(SysUser::getCreatedAt);
        List<SysUser> users = userMapper.selectList(wrapper);
        return users.stream().map(this::toUserVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchOperate(List<Long> ids, String action) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要操作的用户");
        }
        switch (action) {
            case "enable" -> ids.forEach(id -> setStatus(id, "ENABLED"));
            case "disable" -> ids.forEach(id -> setStatus(id, "DISABLED"));
            case "resetPassword" -> ids.forEach(this::resetPassword);
            default -> throw new BusinessException("不支持的操作: " + action);
        }
    }

    private List<String> getPermissions(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>()
                                .in(SysRolePermission::getRoleId, roleIds))
                .stream().map(SysRolePermission::getPermissionCode)
                .distinct().sorted().toList();
    }
}
