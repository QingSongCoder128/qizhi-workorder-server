# 企智协同工单调度系统 - 后端服务

## 项目简介

企智协同工单调度系统后端，采用 Spring Cloud 微服务架构，实现工单从提交→AI智能分类分级→多级审批→超时督办→统计分析的全生命周期管控。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| JDK | 17 | 运行环境 |
| Spring Boot | 3.2.5 | 微服务基础框架 |
| Spring Cloud | 2023.0.1 | 微服务治理 |
| Spring Cloud Alibaba | 2023.0.1.2 | Nacos/Seata集成 |
| MyBatis-Plus | 3.5.7 | ORM持久层 |
| Knife4j | 4.5.0 | API文档（OpenAPI3） |
| EasyExcel | 4.0.2 | Excel报表导出 |
| Hutool | 5.8.26 | 工具类库 |
| Maven | 3.8+ | 构建工具 |

## 模块结构

```
qizhi-workorder-server/
├── qizhi-common/              # 公共模块
│   ├── qizhi-common-core/     # 核心工具（统一响应、异常、拦截器）
│   ├── qizhi-common-mybatis/  # MyBatis-Plus配置（自动填充）
│   └── qizhi-common-redis/    # Redis配置与工具类
├── gateway-service/           # 网关服务
├── user-service/              # 用户权限服务
├── work-order-service/        # 工单核心服务
├── approve-service/           # 审批流程服务
├── message-service/           # 消息通知服务
├── ai-process-service/        # AI智能处理服务
└── statistics-service/        # 数据统计服务
```

## 微服务职责

| 服务 | 端口 | Nacos服务名 | 数据库 | 职责 |
|------|------|-------------|--------|------|
| gateway-service | 10001 | gateway-service | - | 统一入口、路由转发、Session鉴权、Redis限流 |
| user-service | 10002 | user-service | qizhi_user | 登录认证、用户/角色/部门/权限管理 |
| work-order-service | 10003 | work-order-service | qizhi_work_order | 工单CRUD、状态流转、分布式锁、Seata事务发起 |
| approve-service | 10004 | approve-service | qizhi_approve | 审批模板、多级审批、转交/加签/减签 |
| message-service | 10005 | message-service | qizhi_message | 站内信、RabbitMQ通知、延迟督办、死信管理 |
| ai-process-service | 10006 | ai-process-service | qizhi_ai | AI分类/评级/预审、Supervisor调度、Graph编排 |
| statistics-service | 10007 | statistics-service | qizhi_statistics | 看板统计、Redis缓存、Excel导出 |

## 中间件清单

| 中间件 | 端口 | 用途 |
|--------|------|------|
| MySQL 8.0 | 3306 | 业务数据持久化（7个独立数据库） |
| Redis 7 | 6379 | 分布式会话、分布式锁、限流计数、统计缓存 |
| RabbitMQ 3.12 | 5672/15672 | 异步通知、延迟督办（三TTL队列）、死信处理 |
| Nacos 2.3 | 8848 | 服务注册发现、统一配置中心 |
| Seata 2.0 | 8091/7091 | 分布式事务协调（AT模式） |

## 数据库清单

| 数据库名 | 对应服务 | 核心表 |
|----------|----------|--------|
| qizhi_work_order | work-order-service | work_order, work_order_history, work_order_attachment |
| qizhi_approve | approve-service | approval_instance, approval_record, approval_template, approval_node |
| qizhi_user | user-service | sys_user, sys_role, sys_department, sys_permission, sys_user_role, sys_role_permission |
| qizhi_message | message-service | sys_message, delayed_task, dead_letter |
| qizhi_ai | ai-process-service | ai_task_log |
| qizhi_statistics | statistics-service | stat_daily_summary |
| qizhi_log | work-order-service(写) | operate_log |

每个业务库均包含 `undo_log` 表（Seata AT模式必需）。

## 环境配置

### Nacos配置中心

各服务的数据库连接、Redis、RabbitMQ、Seata等配置均托管在Nacos配置中心：

| Data ID | 说明 |
|---------|------|
| gateway-service.yaml | 网关限流阈值、白名单 |
| user-service.yaml | 数据源、Redis会话 |
| work-order-service.yaml | 数据源、Redis锁、Seata |
| approve-service.yaml | 数据源、审批超时配置 |
| message-service.yaml | 数据源、RabbitMQ连接 |
| ai-process-service.yaml | 数据源、AI模型参数 |
| statistics-service.yaml | 数据源、Redis缓存 |

### 数据库初始化

```bash
mysql -h<HOST> -uroot -p<PWD> --default-character-set=utf8mb4 < sql/qizhi-workorder.sql
```

或在 Navicat 中连接 MySQL(root) 后直接运行 `sql/qizhi-workorder.sql`。

### 演示账号

| 账号 | 密码 | 角色 | 说明 |
|------|------|------|------|
| admin | admin | ADMIN | 系统管理员（李立伟） |
| 张伟 | 123 | EMPLOYEE | 普通员工（运维部） |
| 王强 | 123 | APPROVER | 审批主管（运维部，一级审批） |
| 李明 | 123 | APPROVER | 审批主管（技术部，二级审批） |
| 李娜 | 123 | EMPLOYEE | 普通员工（人事部） |
| 陈静 | 123 | EMPLOYEE | 普通员工（财务部） |
| 赵敏 | 123 | EMPLOYEE | 普通员工（行政部） |
| 刘洋 | 123 | EMPLOYEE | 普通员工（技术部） |

密码均使用 BCrypt 加密存储，admin使用 `$2a$` 前缀，其余使用 `$2b$` 前缀。

## 启动方式

### 中间件启动顺序

中间件通过 Docker Compose 一键管理（位于 `qizhi-workorder-middleware/` 目录）：

```bash
# 启动全部中间件
bash start.sh

# 停止全部中间件
bash stop.sh
```

启动顺序：MySQL → Redis → Nacos → RabbitMQ → Seata

### 后端服务启动顺序

在 IDEA 中打开 `qizhi-workorder-server` 目录，按以下顺序启动：

1. **gateway-service** → `GatewayApplication.java`
2. **user-service** → `UserApplication.java`
3. **work-order-service** → `WorkOrderApplication.java`
4. **approve-service** → `ApproveApplication.java`
5. **message-service** → `MessageApplication.java`
6. **ai-process-service** → `AiProcessApplication.java`
7. **statistics-service** → `StatisticsApplication.java`

### Maven命令行启动

```bash
# 编译打包
mvn clean package -DskipTests

# 启动单个服务（以user-service为例）
java -jar user-service/target/user-service-1.0.0.jar
```

### 启动成功标志

- 控制台无 ERROR 日志
- Nacos控制台（http://<VM_IP>:8848/nacos）服务列表显示7个服务
- 访问 http://localhost:10001/actuator/health 返回 `{"status":"UP"}`

## Swagger API文档

各服务均集成 Knife4j（OpenAPI3），启动后访问：

| 服务 | Swagger地址 |
|------|-------------|
| user-service | http://localhost:10002/doc.html |
| work-order-service | http://localhost:10003/doc.html |
| approve-service | http://localhost:10004/doc.html |
| message-service | http://localhost:10005/doc.html |
| ai-process-service | http://localhost:10006/doc.html |
| statistics-service | http://localhost:10007/doc.html |

## Session认证说明

- 登录成功后，user-service 生成 UUID 格式的 sessionId
- 用户信息（ID、姓名、角色、权限）序列化存入 Redis，Key: `session:{sessionId}`，过期30分钟
- 前端将 sessionId 存入内存，每次请求通过 `X-Session-Id` 请求头携带
- Gateway 的 `AuthGlobalFilter` 统一拦截校验，无效返回401
- 每次有效请求自动续期（重置30分钟）
- 登出时主动删除 Redis Key

## Redis作用

| 场景 | Key格式 | 说明 |
|------|---------|------|
| 分布式会话 | session:{sessionId} | 用户登录态，30分钟过期 |
| 防重复提交锁 | lock:workorder:{userId}:{type} | SETNX，30秒过期 |
| 接口限流 | limit:user:{userId} / limit:ip:{ip} | INCR计数器，1秒过期 |
| 统计缓存 | stats:dashboard:{deptId} | 看板数据，5分钟过期 |
| 工单序列号 | wo:seq:{yyyyMMdd} | 每日工单编号序列 |

## RabbitMQ作用

| 队列 | 用途 |
|------|------|
| notify.queue | 实时通知（工单状态变更→站内信） |
| remind.delay.urgent.queue | 紧急工单督办延迟（TTL=60分钟） |
| remind.delay.normal.queue | 普通工单督办延迟（TTL=240分钟） |
| remind.delay.low.queue | 低级工单督办延迟（TTL=720分钟） |
| remind.delay.retry.queue | 督办重试延迟 |
| remind.fire.queue | 督办触发消费队列 |
| dlq.queue | 死信队列（消费失败消息） |

交换机：`direct.exchange`（实时通知）、`remind.delay.exchange`（延迟督办）、`remind.fire.exchange`（督办触发）、`dlx.exchange`（死信）

## Seata分布式事务

- 模式：AT（无侵入，基于undo_log自动回滚）
- 事务组：`qizhi_tx_group`
- 场景：工单提交后，work-order-service 发起全局事务，approve-service 生成审批单，work-order-service 在同一事务中跨库写入 `qizhi_log.operate_log`
- 任一环节异常→全局回滚，保证数据一致性

## AI服务说明

- 采用 Supervisor + Graph + A2A 架构
- 三个Agent串行执行：分类Agent → 评级Agent → 预审Agent
- 模型参数（接口地址、API Key、温度）存储在Nacos配置中心，热生效
- AI不可用时自动降级：工单仍可提交，标记 `ai_abnormal=true`，不阻断审批

## 超时督办说明

- 工单进入待审批后，根据优先级发送不同TTL的延迟消息
- 三个独立队列避免队头阻塞（URGENT不被NORMAL/LOW阻塞）
- 消息到期后消费者检查工单状态，未审批则生成督办通知
- 最多督办3次，超过后升级通知上级

## 常见错误排查

| 现象 | 可能原因 | 解决方法 |
|------|----------|----------|
| 服务启动报Nacos连接失败 | 中间件未启动或IP配置错误 | 检查Nacos是否运行，确认bootstrap.yml中server-addr |
| 数据库连接失败 | MySQL未启动或密码错误 | 检查Nacos配置中datasource密码 |
| Feign调用超时 | 下游服务未启动 | 确认被调用服务已注册到Nacos |
| Redis连接失败 | Redis未启动或密码错误 | 检查Nacos配置中redis密码 |
| RabbitMQ队列声明冲突 | 队列参数被修改 | 不要手动修改队列TTL等参数 |
| Seata注册失败 | Seata Server未启动 | 确认Seata容器运行且Nacos中有seata-server |
| 中文乱码 | 编码不一致 | 确保JVM启动参数含 `-Dfile.encoding=UTF-8` |

## 已知限制

- AI服务依赖外部大模型API，离线环境使用规则引擎兜底
- Seata Web Console（7091端口）在当前版本不可用，不影响事务功能
- 附件存储在本地文件系统，未对接OSS

## 测试执行

```bash
# 编译（含单元测试）
mvn clean test

# 打包（跳过测试）
mvn clean package -DskipTests
```
