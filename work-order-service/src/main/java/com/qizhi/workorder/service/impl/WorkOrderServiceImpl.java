package com.qizhi.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.workorder.dto.WorkOrderSubmitDTO;
import com.qizhi.workorder.entity.WorkOrder;
import com.qizhi.workorder.entity.WorkOrderAttachment;
import com.qizhi.workorder.entity.WorkOrderHistory;
import com.qizhi.workorder.feign.AiProcessFeignClient;
import com.qizhi.workorder.feign.ApproveFeignClient;
import com.qizhi.workorder.feign.MessageFeignClient;
import com.qizhi.workorder.feign.OperationLogFeignClient;
import com.qizhi.workorder.mapper.WorkOrderAttachmentMapper;
import com.qizhi.workorder.mapper.WorkOrderHistoryMapper;
import com.qizhi.workorder.mapper.WorkOrderMapper;
import com.qizhi.workorder.service.WorkOrderService;
import com.qizhi.workorder.vo.WorkOrderDetailVO;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工单核心服务实现
 * <p>
 * SRS 需求: WO-01 ~ WO-22
 * 实现工单从提交到完结的全生命周期管理，核心流程：
 *   1. Redis 分布式锁防重复提交
 *   2. 保存工单基础数据（本地事务）
 *   3. Feign 调用 AI 智能预处理
 *   4. Seata @GlobalTransactional 分布式事务：更新工单状态 + 创建审批单
 *   5. 异步发送通知（RabbitMQ）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderHistoryMapper historyMapper;
    private final WorkOrderAttachmentMapper attachmentMapper;
    private final AiProcessFeignClient aiProcessFeignClient;
    private final ApproveFeignClient approveFeignClient;
    private final MessageFeignClient messageFeignClient;
    private final OperationLogFeignClient operationLogFeignClient;
    private final RedisUtil redisUtil;

    /** 分布式锁过期时间（秒），从 Nacos 读取，默认 30 */
    @Value("${workorder.lock-expire-seconds:30}")
    private int lockExpireSeconds;

    /** 导出最大行数，从 Nacos 读取，默认 5000 */
    @Value("${workorder.export-max-rows:5000}")
    private int exportMaxRows;

    /** 工单序列号过期时间（秒），从 Nacos 读取，默认 2 天 */
    @Value("${workorder.seq-expire-seconds:172800}")
    private int seqExpireSeconds;

    /** 单张工单最大附件数 */
    @Value("${workorder.attachment.max-files:5}")
    private int maxAttachmentFiles;

    /** 单附件最大字节数，默认 5MB */
    @Value("${workorder.attachment.max-size-bytes:5242880}")
    private long maxAttachmentSize;

    /** 允许的附件扩展名 */
    @Value("#{'${workorder.attachment.allowed-extensions:jpg,jpeg,png,pdf}'.split(',')}")
    private List<String> allowedAttachmentExtensions;

    /** 超时阈值-加急（分钟），从 Nacos 读取，默认 60 */
    @Value("${workorder.timeout.urgent-minutes:60}")
    private long timeoutUrgentMinutes;

    /** 超时阈值-普通（分钟），从 Nacos 读取，默认 240 */
    @Value("${workorder.timeout.normal-minutes:240}")
    private long timeoutNormalMinutes;

    /** 超时阈值-低优先级（分钟），从 Nacos 读取，默认 720 */
    @Value("${workorder.timeout.low-minutes:720}")
    private long timeoutLowMinutes;

    /**
     * 自注入代理（解决同类中 @GlobalTransactional / @Transactional 不生效问题）
     * 通过 @Lazy 延迟注入，避免循环依赖
     */
    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private WorkOrderService self;

    /**
     * 提交工单（核心入口方法，不加事务注解）
     * <p>
     * 流程拆分为三个阶段：
     *   1. 本地事务：保存工单基础数据 + 附件
     *   2. 外部调用：AI 预处理（不在事务内，避免长事务）
     *   3. 分布式事务（Seata）：更新工单状态 + 创建审批单（跨服务原子操作）
     *   4. 异步通知：发送消息（事务外，失败不影响主流程）
     * </p>
     */
    @Override
    public WorkOrder submit(WorkOrderSubmitDTO dto, Long userId, String username) {
        // ---- 防重复提交：Redis 分布式锁（锁定维度: userId + workType） ----
        String lockKey = CommonConstants.LOCK_WORK_ORDER_PREFIX + userId + ":" + dto.getType();
        Boolean locked = redisUtil.tryLock(lockKey, lockExpireSeconds);
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(409, "请勿短时间重复提交同类工单");
        }

        // ---- 第一阶段: 本地事务保存工单基础数据 ----
        WorkOrder order = saveOrder(dto, userId, username);

        // ---- 第二阶段: AI 智能预处理（同步阻塞，失败不阻断流程） ----
        processAI(order);

        // ---- 第三阶段: Seata 分布式事务（更新状态 + 创建审批单，跨服务原子操作） ----
        // 通过 self 代理调用，确保 @GlobalTransactional 注解生效
        self.updateStatusAndCreateApproval(order, userId, username);

        // ---- 第四阶段: 异步通知（事务外执行，失败不影响主流程） ----
        sendSubmitNotification(order, userId);

        // ---- 第五阶段: 触发延迟督办（MS-04/MS-05，根据优先级设置不同延迟时长） ----
        sendDelayRemind(order);

        // 注意: 不主动释放锁，让其自然过期（30秒），防止短时间内重复提交同类工单
        return order;
    }

    /**
     * 本地事务：保存工单 + 附件 + 状态历史
     * 使用 Spring 本地 @Transactional，保证工单和附件的原子性
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder saveOrder(WorkOrderSubmitDTO dto, Long userId, String username) {
        validateAttachmentMetadata(dto.getAttachments());
        String orderNo = generateOrderNo();

        WorkOrder order = new WorkOrder();
        order.setOrderNo(orderNo);
        order.setSubmitterId(userId);
        order.setSubmitterName(username);
        order.setType(dto.getType());
        order.setTitle(dto.getTitle());
        order.setDetail(dto.getDetail());
        order.setDepartmentCode(dto.getDepartmentCode());
        order.setUrgent(dto.getUrgent());
        order.setPriority("NORMAL");
        order.setStatus(CommonConstants.STATUS_PENDING_AI);
        order.setAiAbnormal(false);
        order.setVersionNo(1);
        workOrderMapper.insert(order);

        // 记录状态流转历史
        saveHistory(order.getId(), null, CommonConstants.STATUS_PENDING_AI, userId, username, "提交工单");

        // 保存附件列表
        if (dto.getAttachments() != null) {
            for (WorkOrderSubmitDTO.AttachmentInfo att : dto.getAttachments()) {
                WorkOrderAttachment attachment = new WorkOrderAttachment();
                attachment.setWorkOrderId(order.getId());
                attachment.setFileName(att.getFileName());
                attachment.setFileUrl(att.getFileUrl());
                attachment.setFileType(att.getFileType());
                attachment.setFileSize(att.getFileSize());
                attachmentMapper.insert(attachment);
            }
        }

        return order;
    }

    /**
     * AI 智能预处理：调用 ai-process-service 进行分类/评级/预审
     * <p>
     * SRS 需求: WO-05
     * AI 调用失败时不阻断工单流转，标记 aiAbnormal=true，审批人可见异常标记。
     * </p>
     */
    private void processAI(WorkOrder order) {
        try {
            Map<String, Object> aiRequest = new HashMap<>();
            aiRequest.put("workOrderId", order.getId());
            aiRequest.put("type", order.getType());
            aiRequest.put("title", order.getTitle());
            aiRequest.put("detail", order.getDetail());
            aiRequest.put("urgent", order.getUrgent());

            var aiResult = aiProcessFeignClient.process(aiRequest);
            if (aiResult != null && aiResult.getCode() == 200 && aiResult.getData() != null) {
                Map<String, Object> aiData = aiResult.getData();
                order.setAiCategory(String.valueOf(aiData.getOrDefault("category", "")));
                order.setAiConfidence(aiData.get("confidence") != null ?
                        Double.parseDouble(String.valueOf(aiData.get("confidence"))) : null);
                order.setAiPriorityReason(String.valueOf(aiData.getOrDefault("priorityReason", "")));
                order.setAiSuggestion(String.valueOf(aiData.getOrDefault("suggestion", "")));
                order.setAiSensitiveWords(String.valueOf(aiData.getOrDefault("sensitiveWords", "")));
                if (aiData.get("priority") != null) {
                    order.setPriority(String.valueOf(aiData.get("priority")));
                }
                // BUG-003 FIX: 读取 AI 服务返回的异常标记（降级/繁忙）
                if (Boolean.TRUE.equals(aiData.get("aiAbnormal"))) {
                    order.setAiAbnormal(true);
                }
            }
        } catch (Exception e) {
            // AI 异常不阻断工单流转，标记异常供审批人参考
            log.error("AI 预处理调用失败，工单继续流转: type={}, msg={}", e.getClass().getName(), e.getMessage(), e);
            order.setAiAbnormal(true);
        }
    }

    /**
     * Seata 分布式事务：更新工单状态 + Feign 创建审批单
     * <p>
     * SRS 需求: WO-06, WO-11 ~ WO-15
     * 使用 @GlobalTransactional 保证跨服务数据一致性：
     *   - work-order-service: 更新工单状态为 PENDING_APPROVE
     *   - approve-service: 创建审批实例 + 审批节点记录
     * 任一服务失败，两者同时回滚。
     * </p>
     *
     * @param order    工单对象（含 AI 处理结果）
     * @param userId   提交人 ID
     * @param username 提交人姓名
     */
    @GlobalTransactional(name = "create-approval", rollbackFor = Exception.class)
    public void updateStatusAndCreateApproval(WorkOrder order, Long userId, String username) {
        log.info("[Seata] 开启全局事务: 更新工单状态 + 创建审批单, workOrderId={}", order.getId());

        // ---- work-order-service 本地操作：更新工单状态为待审批 ----
        order.setStatus(CommonConstants.STATUS_PENDING_APPROVE);
        // WO-14: 将全局事务 ID XID 存入工单记录，供问题溯源
        order.setSeataXid(io.seata.core.context.RootContext.getXID());
        workOrderMapper.updateById(order);
        saveHistory(order.getId(), CommonConstants.STATUS_PENDING_AI,
                CommonConstants.STATUS_PENDING_APPROVE, userId, username, "AI预处理完成，进入审批流程");

        // ---- Feign 调用 approve-service 创建审批单（XID 自动通过 Header 传播） ----
        // 传入 departmentCode + workType 用于审批模板自动匹配
        Map<String, Object> approveRequest = new HashMap<>();
        approveRequest.put("workOrderId", order.getId());
        approveRequest.put("orderNo", order.getOrderNo());
        approveRequest.put("title", order.getTitle());
        approveRequest.put("submitterId", userId);
        approveRequest.put("submitterName", username);
        approveRequest.put("detail", order.getDetail());
        approveRequest.put("departmentCode", order.getDepartmentCode());
        approveRequest.put("workType", order.getType()); // 工单类型，用于匹配审批模板
        approveRequest.put("priority", order.getPriority());

        // 如果审批服务异常，Seata 会自动回滚 work-order-service 的状态更新
        var result = approveFeignClient.createApproval(approveRequest);
        if (result == null || result.getCode() != 200) {
            throw new BusinessException("审批单创建失败，全局事务回滚");
        }

        // ---- operation-log-service 第三事务分支：写入操作日志 ----
        Map<String, Object> logRequest = new HashMap<>();
        logRequest.put("userId", userId);
        logRequest.put("userName", username);
        logRequest.put("module", "WORK_ORDER");
        logRequest.put("action", "SUBMIT");
        logRequest.put("targetType", "WORK_ORDER");
        logRequest.put("targetId", order.getId());
        logRequest.put("detail", "提交工单并创建审批流程: " + order.getOrderNo());
        var logResult = operationLogFeignClient.create(logRequest);
        if (logResult == null || logResult.getCode() != 200) {
            throw new BusinessException("操作日志写入失败，全局事务回滚");
        }

        log.info("[Seata] 全局事务提交成功: workOrderId={}", order.getId());
    }

    /**
     * 发送工单提交通知（异步，失败不影响主流程）
     * SRS 需求: WO-07 事务提交后的异步通知
     */
    private void sendSubmitNotification(WorkOrder order, Long userId) {
        try {
            Map<String, Object> notifyRequest = new HashMap<>();
            notifyRequest.put("receiverId", userId);
            notifyRequest.put("title", "工单提交成功");
            notifyRequest.put("content", "您的工单[" + order.getOrderNo() + "]已提交，正在审批中");
            notifyRequest.put("msgType", "SYSTEM");
            notifyRequest.put("bizType", "WORK_ORDER");
            notifyRequest.put("bizId", order.getId());
            messageFeignClient.sendNotify(notifyRequest);
        } catch (Exception e) {
            // 通知失败不影响主流程，仅记录日志
            log.error("消息通知发送失败（不影响工单提交）: {}", e.getMessage());
        }
    }

    /**
     * 触发延迟督办（MS-04/MS-05）
     * 工单进入待审批状态后，根据优先级设置不同延迟时长：
     * URGENT=60分钟、NORMAL=240分钟、LOW=720分钟
     * 超时未审批则自动发送督办提醒
     */
    private void sendDelayRemind(WorkOrder order) {
        try {
            Map<String, Object> remindRequest = new HashMap<>();
            remindRequest.put("workOrderId", order.getId());
            remindRequest.put("orderNo", order.getOrderNo());
            remindRequest.put("title", order.getTitle());
            remindRequest.put("priority", order.getPriority());
            remindRequest.put("submitterId", order.getSubmitterId());
            remindRequest.put("approverId", order.getCurrentApproverId());
            messageFeignClient.sendDelayRemind(remindRequest);
            log.info("延迟督办已触发: orderNo={}, priority={}", order.getOrderNo(), order.getPriority());
        } catch (Exception e) {
            // 督办触发失败不影响主流程
            log.error("延迟督办触发失败（不影响工单提交）: {}", e.getMessage());
        }
    }

    @Override
    public PageResult<WorkOrder> getMyList(Long userId, Integer current, Integer size, String status, String type, String priority, String keyword) {
        Page<WorkOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrder::getSubmitterId, userId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        if (StringUtils.hasText(type)) {
            wrapper.eq(WorkOrder::getType, type);
        }
        if (StringUtils.hasText(priority)) {
            wrapper.eq(WorkOrder::getPriority, priority);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(WorkOrder::getTitle, keyword).or().like(WorkOrder::getOrderNo, keyword));
        }
        wrapper.orderByDesc(WorkOrder::getCreatedAt);
        Page<WorkOrder> result = workOrderMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public PageResult<WorkOrder> getAdminList(Integer current, Integer size, String status, String type, String keyword) {
        Page<WorkOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        if (StringUtils.hasText(type)) {
            wrapper.eq(WorkOrder::getType, type);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(WorkOrder::getTitle, keyword)
                    .or().like(WorkOrder::getOrderNo, keyword)
                    .or().like(WorkOrder::getSubmitterName, keyword));
        }
        wrapper.orderByDesc(WorkOrder::getCreatedAt);
        Page<WorkOrder> result = workOrderMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public WorkOrderDetailVO getDetail(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("工单不存在");
        }

        // 查询状态历史
        List<WorkOrderHistory> histories = historyMapper.selectList(
                new LambdaQueryWrapper<WorkOrderHistory>()
                        .eq(WorkOrderHistory::getWorkOrderId, id)
                        .orderByAsc(WorkOrderHistory::getCreatedAt));

        List<WorkOrderDetailVO.StatusHistory> historyList = histories.stream()
                .map(h -> WorkOrderDetailVO.StatusHistory.builder()
                        .fromStatus(h.getFromStatus())
                        .toStatus(h.getToStatus())
                        .operatorName(h.getOperatorName())
                        .remark(h.getRemark())
                        .createdAt(h.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        // 审批时间线：Feign 调用 approve-service 获取审批记录
        List<WorkOrderDetailVO.ApprovalTimeline> timelineList = new ArrayList<>();
        try {
            R<List<Map<String, Object>>> recordsResp = approveFeignClient.getRecordsByWorkOrderId(id);
            if (recordsResp != null && recordsResp.getCode() == 200 && recordsResp.getData() != null) {
                for (Map<String, Object> rec : recordsResp.getData()) {
                    timelineList.add(WorkOrderDetailVO.ApprovalTimeline.builder()
                            .nodeName(rec.get("nodeName") != null ? String.valueOf(rec.get("nodeName")) : null)
                            .action(rec.get("action") != null ? String.valueOf(rec.get("action")) : null)
                            .operatorName(rec.get("operatorName") != null ? String.valueOf(rec.get("operatorName")) : null)
                            .opinion(rec.get("opinion") != null ? String.valueOf(rec.get("opinion")) : null)
                            .operatedAt(rec.get("operatedAt") != null ? LocalDateTime.parse(String.valueOf(rec.get("operatedAt")).replace("Z", "")) : null)
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("获取审批时间线失败: {}", e.getMessage());
        }

        return WorkOrderDetailVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .submitterId(order.getSubmitterId())
                .submitterName(order.getSubmitterName())
                .type(order.getType())
                .title(order.getTitle())
                .detail(order.getDetail())
                .departmentCode(order.getDepartmentCode())
                .urgent(order.getUrgent())
                .priority(order.getPriority())
                .status(order.getStatus())
                .currentApproverName(order.getCurrentApproverName())
                .currentNode(order.getCurrentNode())
                .aiCategory(order.getAiCategory())
                .aiConfidence(order.getAiConfidence())
                .aiPriorityReason(order.getAiPriorityReason())
                .aiSuggestion(order.getAiSuggestion())
                .aiSensitiveWords(order.getAiSensitiveWords())
                .aiAbnormal(order.getAiAbnormal())
                .seataXid(order.getSeataXid())
                .versionNo(order.getVersionNo())
                .createdAt(order.getCreatedAt())
                .completedAt(order.getCompletedAt())
                .approvalNodes(timelineList)
                .statusHistory(historyList)
                .build();
    }

    @Override
    public void resubmit(Long id, WorkOrderSubmitDTO dto, Long userId, String username) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("工单不存在");
        }
        if (!CommonConstants.STATUS_REJECTED.equals(order.getStatus())) {
            throw new BusinessException("只有被驳回的工单才能重新提交");
        }
        if (!order.getSubmitterId().equals(userId)) {
            throw new BusinessException("只能重新提交自己的工单");
        }

        // 更新工单内容，状态回到 PENDING_AI，重新走 AI 预审流程
        order.setType(dto.getType());
        order.setTitle(dto.getTitle());
        order.setDetail(dto.getDetail());
        order.setDepartmentCode(dto.getDepartmentCode());
        order.setUrgent(dto.getUrgent());
        order.setStatus(CommonConstants.STATUS_PENDING_AI);
        order.setVersionNo(order.getVersionNo() + 1);
        workOrderMapper.updateById(order);

        saveHistory(id, CommonConstants.STATUS_REJECTED, CommonConstants.STATUS_PENDING_AI,
                userId, username, "重新提交，版本号:" + order.getVersionNo() + "，重新进入AI预审");

        // 重新触发 AI 智能预处理（失败不阻断流程）
        processAI(order);

        // Seata 分布式事务：更新状态为待审批 + 创建新审批单
        self.updateStatusAndCreateApproval(order, userId, username);
    }

    @Override
    public WorkOrder getById(Long id) {
        return workOrderMapper.selectById(id);
    }

    /**
     * 工单导出列表（全量查询，支持多条件筛选）
     * 用于 statistics-service 通过 Feign 调用获取导出数据
     */
    @Override
    public List<Map<String, Object>> getExportList(String deptCode, String type, String startDate, String endDate) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        // 部门筛选
        if (StringUtils.hasText(deptCode)) {
            wrapper.eq(WorkOrder::getDepartmentCode, deptCode);
        }
        // 工单类型筛选
        if (StringUtils.hasText(type)) {
            wrapper.eq(WorkOrder::getType, type);
        }
        // 时间范围筛选
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        if (StringUtils.hasText(startDate)) {
            wrapper.ge(WorkOrder::getCreatedAt, LocalDate.parse(startDate, fmt).atStartOfDay());
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le(WorkOrder::getCreatedAt, LocalDate.parse(endDate, fmt).plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(WorkOrder::getCreatedAt);
        // 最多导出 5000 条，防止内存溢出
        wrapper.last("LIMIT " + exportMaxRows);

        List<WorkOrder> orders = workOrderMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkOrder o : orders) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("orderNo", o.getOrderNo());
            map.put("title", o.getTitle());
            map.put("submitterName", o.getSubmitterName());
            map.put("type", o.getType());
            map.put("priority", o.getPriority());
            map.put("status", o.getStatus());
            map.put("currentApproverName", o.getCurrentApproverName());
            map.put("departmentCode", o.getDepartmentCode());
            map.put("createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : "");
            map.put("completedAt", o.getCompletedAt() != null ? o.getCompletedAt().toString() : "");
            result.add(map);
        }
        log.info("工单导出查询: deptCode={}, type={}, count={}", deptCode, type, result.size());
        return result;
    }

    /**
     * 实时统计看板数据（供 statistics-service Feign 调用）
     * 直接从 work_order 表聚合，返回前端期望的格式
     */
    @Override
    public Map<String, Object> getStats(String deptCode, String startDate, String endDate, String workType) {
        LambdaQueryWrapper<WorkOrder> statsQuery = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(deptCode)) {
            statsQuery.eq(WorkOrder::getDepartmentCode, deptCode);
        }
        if (StringUtils.hasText(workType)) {
            statsQuery.eq(WorkOrder::getType, workType);
        }
        DateTimeFormatter statsDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        if (StringUtils.hasText(startDate)) {
            statsQuery.ge(WorkOrder::getCreatedAt,
                    LocalDate.parse(startDate, statsDateFormat).atStartOfDay());
        }
        if (StringUtils.hasText(endDate)) {
            statsQuery.lt(WorkOrder::getCreatedAt,
                    LocalDate.parse(endDate, statsDateFormat).plusDays(1).atStartOfDay());
        }
        List<WorkOrder> allOrders = workOrderMapper.selectList(statsQuery);

        int totalCount = allOrders.size();
        // 待处理总量（工单状态 PENDING_AI / PENDING_APPROVE / APPROVING）
        int pendingTotal = (int) allOrders.stream()
                .filter(o -> "PENDING_AI".equals(o.getStatus()) || "PENDING_APPROVE".equals(o.getStatus()) || "APPROVING".equals(o.getStatus()))
                .count();
        // “审批中” = 审批实例处于 APPROVING（首节点已通过、流转中）的数量；Feign 失败时降级为 0
        int approvingInstances = 0;
        try {
            R<Long> approvingResult = approveFeignClient.countApproving();
            if (approvingResult != null && approvingResult.getCode() == 200 && approvingResult.getData() != null) {
                approvingInstances = approvingResult.getData().intValue();
            }
        } catch (Exception e) {
            log.warn("获取审批中实例数失败，看板“审批中”降级为0: {}", e.getMessage());
        }
        int approvedCount = approvingInstances;                          // 审批中
        int pendingCount = Math.max(pendingTotal - approvingInstances, 0); // 待审批（扣除已流转）
        int completedCount = (int) allOrders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()))
                .count();
        int rejectedCount = (int) allOrders.stream()
                .filter(o -> "REJECTED".equals(o.getStatus()))
                .count();

        // 各部门工单分布（与 sys_department 实际部门对齐）
        Map<String, String> deptNames = Map.of(
                "DEPT_IT", "运维部", "DEPT_ADMIN", "行政部",
                "DEPT_HR", "人事部", "DEPT_TECH", "技术部",
                "DEPT_FIN", "财务部");
        Map<String, Long> deptCounts = allOrders.stream()
                .filter(o -> o.getDepartmentCode() != null)
                .collect(Collectors.groupingBy(WorkOrder::getDepartmentCode, Collectors.counting()));
        List<Map<String, Object>> deptDistribution = new ArrayList<>();
        deptCounts.forEach((code, cnt) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", deptNames.getOrDefault(code, code));
            item.put("value", cnt);
            deptDistribution.add(item);
        });

        // 近7日工单趋势
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            long cnt = allOrders.stream()
                    .filter(o -> o.getCreatedAt() != null
                            && !o.getCreatedAt().isBefore(dayStart)
                            && o.getCreatedAt().isBefore(dayEnd))
                    .count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.format(dateFmt));
            item.put("count", cnt);
            trend.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", totalCount);
        result.put("pendingCount", pendingCount);
        result.put("approvedCount", approvedCount);
        result.put("completedCount", completedCount);
        result.put("rejectedCount", rejectedCount);
        result.put("deptDistribution", deptDistribution);
        result.put("trend", trend);

        // 平均审批时长（分钟）：已完结工单从创建到完成的平均耗时
        long totalMinutes = allOrders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()) && o.getCreatedAt() != null && o.getCompletedAt() != null)
                .mapToLong(o -> java.time.Duration.between(o.getCreatedAt(), o.getCompletedAt()).toMinutes())
                .sum();
        long completedWithTime = allOrders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()) && o.getCreatedAt() != null && o.getCompletedAt() != null)
                .count();
        result.put("avgApproveMinutes", completedWithTime > 0 ? totalMinutes / completedWithTime : 0);

        // ST-01: 超时工单数 — 处于待审批/审批中且超过对应优先级阈值的工单
        LocalDateTime now = LocalDateTime.now();
        long timeoutCount = allOrders.stream()
                .filter(o -> ("PENDING_APPROVE".equals(o.getStatus()) || "APPROVING".equals(o.getStatus()))
                        && o.getCreatedAt() != null)
                .filter(o -> {
                    long minutes = java.time.Duration.between(o.getCreatedAt(), now).toMinutes();
                    String priority = o.getPriority();
                    long threshold = "URGENT".equals(priority) ? timeoutUrgentMinutes
                            : "LOW".equals(priority) ? timeoutLowMinutes : timeoutNormalMinutes;
                    return minutes > threshold;
                })
                .count();
        result.put("timeoutCount", timeoutCount);

        return result;
    }

    /**
     * 撤销工单（仅待审批状态可撤销）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long id, Long userId) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("工单不存在");
        }
        if (!order.getSubmitterId().equals(userId)) {
            throw new BusinessException("只能撤销自己的工单");
        }
        if (!"PENDING_APPROVE".equals(order.getStatus())) {
            throw new BusinessException("只有待审批状态的工单才能撤销");
        }
        order.setStatus("CANCELLED");
        workOrderMapper.updateById(order);
        saveHistory(id, "PENDING_APPROVE", "CANCELLED", userId, order.getSubmitterName(), "用户撤销工单");
    }

    /**
     * 更新工单状态（供 approve-service 审批完成/驳回后回调）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status, String remark) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("工单不存在");
        }
        String fromStatus = order.getStatus();
        order.setStatus(status);
        if ("COMPLETED".equals(status)) {
            order.setCompletedAt(LocalDateTime.now());
        }
        workOrderMapper.updateById(order);
        saveHistory(id, fromStatus, status, null, "system", remark != null ? remark : "审批状态变更");

        // ST-03: 工单状态变更时主动失效统计缓存，下次查询时重新计算
        invalidateStatsCache();
    }

    /**
     * 清除统计看板 Redis 缓存（ST-03）
     * 工单状态变更后调用，确保看板数据实时性
     */
    private void invalidateStatsCache() {
        try {
            String[] deptCodes = {"ALL", "DEPT_IT", "DEPT_ADMIN", "DEPT_HR", "DEPT_TECH", "DEPT_FIN"};
            for (String dept : deptCodes) {
                redisUtil.delete(CommonConstants.STATS_DASHBOARD_PREFIX + dept);
            }
            log.debug("统计缓存已失效");
        } catch (Exception e) {
            log.warn("统计缓存失效失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 附件上传（存本地 uploads 目录，返回可访问 URL）
     */
    @Override
    public Map<String, String> uploadAttachment(org.springframework.web.multipart.MultipartFile file) {
        validateUpload(file);
        try {
            String uploadDir = System.getProperty("user.dir") + "/uploads";
            java.io.File dir = new java.io.File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String originalName = file.getOriginalFilename();
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase(Locale.ROOT) : "";
            String fileName = java.util.UUID.randomUUID().toString().replace("-", "") + ext;
            java.io.File dest = new java.io.File(dir, fileName);
            file.transferTo(dest);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("url", "/uploads/" + fileName);
            result.put("fileName", originalName);
            return result;
        } catch (Exception e) {
            log.error("附件上传失败: {}", e.getMessage(), e);
            throw new BusinessException("附件上传失败");
        }
    }

    private void validateAttachmentMetadata(List<WorkOrderSubmitDTO.AttachmentInfo> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        if (attachments.size() > maxAttachmentFiles) {
            throw new BusinessException("附件数量不能超过" + maxAttachmentFiles + "个");
        }
        for (WorkOrderSubmitDTO.AttachmentInfo attachment : attachments) {
            String extension = extensionOf(attachment.getFileName());
            if (!isAllowedExtension(extension)) {
                throw new BusinessException("不支持的附件类型，仅允许 JPG、PNG 和 PDF");
            }
            if (attachment.getFileSize() == null || attachment.getFileSize() <= 0
                    || attachment.getFileSize() > maxAttachmentSize) {
                throw new BusinessException("单个附件大小不能超过5MB");
            }
        }
    }

    private void validateUpload(org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("附件不能为空");
        }
        if (file.getSize() > maxAttachmentSize) {
            throw new BusinessException("单个附件大小不能超过5MB");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!isAllowedExtension(extension)) {
            throw new BusinessException("不支持的附件类型，仅允许 JPG、PNG 和 PDF");
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean validMime = contentType.equals("image/jpeg")
                || contentType.equals("image/png")
                || contentType.equals("application/pdf");
        if (!validMime) {
            throw new BusinessException("附件内容类型不合法");
        }
    }

    private boolean isAllowedExtension(String extension) {
        return allowedAttachmentExtensions.stream()
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(extension::equals);
    }

    private String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public String generateOrderNo() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = "workorder:seq:" + dateStr;
        // 序号计数器缺失时（如 Redis 重建/清空后首次创建），从数据库当天最大序号初始化，
        // 避免新生成的工单编号与已有数据（如导入的演示数据）发生 UNIQUE 冲突
        if (!Boolean.TRUE.equals(redisUtil.hasKey(key))) {
            Long maxSeq = workOrderMapper.selectMaxSeqByDate(dateStr);
            if (maxSeq != null && maxSeq > 0) {
                // INCRBY 为原生数值命令，不走 Jackson 序列化，key 不存在时直接初始化为 maxSeq
                redisUtil.incrementBy(key, maxSeq);
                redisUtil.expire(key, seqExpireSeconds, java.util.concurrent.TimeUnit.SECONDS);
                log.info("工单序号计数器从数据库初始化: date={}, maxSeq={}", dateStr, maxSeq);
            }
        }
        Long seq = redisUtil.increment(key);
        // 设置过期时间
        if (seq == 1L) {
            redisUtil.expire(key, seqExpireSeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
        return "WO" + dateStr + String.format("%06d", seq);
    }

    private void saveHistory(Long workOrderId, String fromStatus, String toStatus,
                             Long operatorId, String operatorName, String remark) {
        WorkOrderHistory history = new WorkOrderHistory();
        history.setWorkOrderId(workOrderId);
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setOperatorId(operatorId);
        history.setOperatorName(operatorName);
        history.setRemark(remark);
        history.setCreatedAt(LocalDateTime.now());
        historyMapper.insert(history);
    }
}
