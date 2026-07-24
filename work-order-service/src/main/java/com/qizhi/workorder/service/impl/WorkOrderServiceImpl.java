package com.qizhi.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.workorder.dto.WorkOrderSubmitDTO;
import com.qizhi.workorder.entity.WorkOrder;
import com.qizhi.workorder.entity.WorkOrderAttachment;
import com.qizhi.workorder.entity.WorkOrderHistory;
import com.qizhi.workorder.feign.AiProcessFeignClient;
import com.qizhi.workorder.feign.ApproveFeignClient;
import com.qizhi.workorder.feign.MessageFeignClient;
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

        try {
            // ---- 第一阶段: 本地事务保存工单基础数据 ----
            WorkOrder order = saveOrder(dto, userId, username);

            // ---- 第二阶段: AI 智能预处理（同步阻塞，失败不阻断流程） ----
            processAI(order);

            // ---- 第三阶段: Seata 分布式事务（更新状态 + 创建审批单，跨服务原子操作） ----
            // 通过 self 代理调用，确保 @GlobalTransactional 注解生效
            self.updateStatusAndCreateApproval(order, userId, username);

            // ---- 第四阶段: 异步通知（事务外执行，失败不影响主流程） ----
            sendSubmitNotification(order, userId);

            return order;
        } finally {
            redisUtil.unlock(lockKey);
        }
    }

    /**
     * 本地事务：保存工单 + 附件 + 状态历史
     * 使用 Spring 本地 @Transactional，保证工单和附件的原子性
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder saveOrder(WorkOrderSubmitDTO dto, Long userId, String username) {
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
                @SuppressWarnings("unchecked")
                Map<String, Object> aiData = (Map<String, Object>) aiResult.getData();
                order.setAiCategory(String.valueOf(aiData.getOrDefault("category", "")));
                order.setAiConfidence(aiData.get("confidence") != null ?
                        Double.parseDouble(String.valueOf(aiData.get("confidence"))) : null);
                order.setAiPriorityReason(String.valueOf(aiData.getOrDefault("priorityReason", "")));
                order.setAiSuggestion(String.valueOf(aiData.getOrDefault("suggestion", "")));
                order.setAiSensitiveWords(String.valueOf(aiData.getOrDefault("sensitiveWords", "")));
                if (aiData.get("priority") != null) {
                    order.setPriority(String.valueOf(aiData.get("priority")));
                }
            }
        } catch (Exception e) {
            // AI 异常不阻断工单流转，标记异常供审批人参考
            log.error("AI 预处理调用失败，工单继续流转: {}", e.getMessage());
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

    @Override
    public PageResult<WorkOrder> getMyList(Long userId, Integer current, Integer size, String status) {
        Page<WorkOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrder::getSubmitterId, userId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(WorkOrder::getStatus, status);
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
                .versionNo(order.getVersionNo())
                .createdAt(order.getCreatedAt())
                .completedAt(order.getCompletedAt())
                .statusHistory(historyList)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

        // 更新工单内容
        order.setType(dto.getType());
        order.setTitle(dto.getTitle());
        order.setDetail(dto.getDetail());
        order.setDepartmentCode(dto.getDepartmentCode());
        order.setUrgent(dto.getUrgent());
        order.setStatus(CommonConstants.STATUS_PENDING_APPROVE);
        order.setVersionNo(order.getVersionNo() + 1);
        workOrderMapper.updateById(order);

        saveHistory(id, CommonConstants.STATUS_REJECTED, CommonConstants.STATUS_PENDING_APPROVE,
                userId, username, "重新提交，版本号:" + order.getVersionNo());
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
    public Map<String, Object> getStats() {
        List<WorkOrder> allOrders = workOrderMapper.selectList(new LambdaQueryWrapper<>());

        int totalCount = allOrders.size();
        int pendingCount = (int) allOrders.stream()
                .filter(o -> "PENDING_APPROVE".equals(o.getStatus()) || "APPROVING".equals(o.getStatus()))
                .count();
        int completedCount = (int) allOrders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()))
                .count();
        // 超时工单：状态为待审批/审批中 且创建超过4小时
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusHours(4);
        int timeoutCount = (int) allOrders.stream()
                .filter(o -> ("PENDING_APPROVE".equals(o.getStatus()) || "APPROVING".equals(o.getStatus()))
                        && o.getCreatedAt() != null && o.getCreatedAt().isBefore(timeoutThreshold))
                .count();

        // 各部门工单分布
        Map<String, String> deptNames = Map.of(
                "DEPT_IT", "运维部", "DEPT_ADMIN", "行政部",
                "DEPT_HR", "人事部", "DEPT_TECH", "技术部");
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
        result.put("completedCount", completedCount);
        result.put("timeoutCount", timeoutCount);
        result.put("deptDistribution", deptDistribution);
        result.put("trend", trend);
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
    }

    /**
     * 附件上传（存本地 uploads 目录，返回可访问 URL）
     */
    @Override
    public Map<String, String> uploadAttachment(org.springframework.web.multipart.MultipartFile file) {
        try {
            String uploadDir = System.getProperty("user.dir") + "/uploads";
            java.io.File dir = new java.io.File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String originalName = file.getOriginalFilename();
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf(".")) : "";
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

    @Override
    public String generateOrderNo() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = "workorder:seq:" + dateStr;
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
