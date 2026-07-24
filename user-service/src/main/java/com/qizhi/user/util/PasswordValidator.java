package com.qizhi.user.util;

import com.qizhi.common.core.exception.BusinessException;

import java.util.regex.Pattern;

/**
 * 密码复杂度校验工具
 * <p>
 * SRS 需求: 安全性需求第3项
 * 密码必须符合复杂度要求：至少8位，包含大写字母、小写字母和数字。
 * </p>
 */
public final class PasswordValidator {

    private PasswordValidator() {}

    /**
     * 密码复杂度正则：
     * - 至少 8 位字符
     * - 包含至少一个大写字母 (?=.*[A-Z])
     * - 包含至少一个小写字母 (?=.*[a-z])
     * - 包含至少一个数字 (?=.*\d)
     */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$");

    /**
     * 校验密码复杂度，不符合则抛出 BusinessException
     *
     * @param password 待校验的明文密码
     * @throws BusinessException 密码不符合复杂度要求时抛出
     */
    public static void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException("密码复杂度不足：至少8位，需包含大写字母、小写字母和数字");
        }
    }
}
