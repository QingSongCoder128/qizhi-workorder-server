package com.qizhi.common.core.constant;

/**
 * 系统常量
 */
public final class CommonConstants {

    private CommonConstants() {}

    // ========== Redis Key 前缀 ==========

    /** 分布式会话 */
    public static final String SESSION_PREFIX = "session:";

    /** 限流计数器 - 用户维度 */
    public static final String LIMIT_USER_PREFIX = "limit:user:";

    /** 限流计数器 - IP 维度 */
    public static final String LIMIT_IP_PREFIX = "limit:ip:";

    /** 工单防重复锁 */
    public static final String LOCK_WORK_ORDER_PREFIX = "lock:workorder:";

    /** 统计看板缓存 */
    public static final String STATS_DASHBOARD_PREFIX = "stats:dashboard:";

    // ========== 请求头 ==========

    /** 会话 ID */
    public static final String HEADER_SESSION_ID = "X-Session-Id";

    /** 用户 ID（网关注入） */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 用户名（网关注入） */
    public static final String HEADER_USERNAME = "X-Username";

    /** 用户角色（网关注入） */
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 用户权限列表（网关注入，逗号分隔） */
    public static final String HEADER_USER_PERMISSIONS = "X-User-Permissions";

    /** 内部调用方 */
    public static final String HEADER_INTERNAL_CALLER = "X-Internal-Caller";

    /** 内部调用时间戳 */
    public static final String HEADER_INTERNAL_TIMESTAMP = "X-Internal-Timestamp";

    /** 内部调用一次性随机数 */
    public static final String HEADER_INTERNAL_NONCE = "X-Internal-Nonce";

    /** 签名绑定的微服务实际路径 */
    public static final String HEADER_INTERNAL_PATH = "X-Internal-Path";

    /** 内部调用 HMAC 签名 */
    public static final String HEADER_INTERNAL_SIGNATURE = "X-Internal-Signature";

    // ========== 工单状态 ==========

    public static final String STATUS_PENDING_AI = "PENDING_AI";
    public static final String STATUS_PENDING_APPROVE = "PENDING_APPROVE";
    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    // ========== 优先级 ==========

    public static final String PRIORITY_URGENT = "URGENT";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_LOW = "LOW";

    // ========== 会话配置（已过时，各服务已通过 @Value 从 Nacos 读取） ==========

    /**
     * 会话过期时间（分钟）
     * @deprecated 已由 user-service 通过 Nacos user.session.expire-minutes 管理
     */
    @Deprecated
    public static final int SESSION_EXPIRE_MINUTES = 30;

    /**
     * 分布式锁过期时间（秒）
     * @deprecated 已由 work-order-service 通过 Nacos workorder.lock-expire-seconds 管理
     */
    @Deprecated
    public static final int LOCK_EXPIRE_SECONDS = 30;

    /**
     * 连续登录失败锁定次数
     * @deprecated 已由 user-service 通过 Nacos user.login.lock-count 管理
     */
    @Deprecated
    public static final int LOGIN_FAIL_LOCK_COUNT = 5;

    /**
     * 账号锁定时长（分钟）
     * @deprecated 已由 user-service 通过 Nacos user.login.lock-minutes 管理
     */
    @Deprecated
    public static final int LOGIN_LOCK_MINUTES = 15;
}
