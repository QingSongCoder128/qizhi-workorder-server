package com.qizhi.approve.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.approve.dto.ApprovalActionDTO;
import com.qizhi.approve.dto.ApprovalCreateDTO;
import com.qizhi.approve.entity.ApprovalInstance;
import com.qizhi.approve.entity.ApprovalNode;
import com.qizhi.approve.entity.ApprovalRecord;
import com.qizhi.approve.entity.ApprovalTemplate;
import com.qizhi.approve.feign.MessageFeignClient;
import com.qizhi.approve.feign.WorkOrderFeignClient;
import com.qizhi.approve.mapper.ApprovalInstanceMapper;
import com.qizhi.approve.mapper.ApprovalRecordMapper;
import com.qizhi.approve.service.ApproveService;
import com.qizhi.approve.service.TemplateService;
import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批管理服务实现
 * <p>
 * SRS 需求: AP-01 ~ AP-04
 * 核心流程：
 *   1. 创建审批单时自动匹配模板 → 生成多级审批节点
 *   2. 支持通过/驳回/转交/加签/减签五种操作
 *   3. 多级审批按节点顺序推进，全部通过后完结
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class ApproveServiceImpl implements ApproveService {

    private final ApprovalInstanceMapper instanceMapper;
    private final ApprovalRecordMapper recordMapper;
    private final MessageFeignClient messageFeignClient;
    private final WorkOrderFeignClient workOrderFeignClient;
    private final TemplateService templateService;

    /** 紧急工单超时阈值（小时），从 Nacos 读取，支持热更新 */
    @Value("${approve.timeout.urgent-hours:1}")
    private int urgentTimeoutHours;

    /** 普通工单超时阈值（小时），从 Nacos 读取，支持热更新 */
    @Value("${approve.timeout.normal-hours:48}")
    private int normalTimeoutHours;

    /**
     * 创建审批单（供 Feign 调用）
     * <p>
     * 流程：
     *   1. 根据 departmentCode + workType 匹配审批模板
     *   2. 有模板：按模板节点创建多级 ApprovalRecord（PENDING）
     *   3. 无模板：创建默认单节点审批
     *   4. 创建 ApprovalInstance，指向第1级节点
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalInstance createApproval(ApprovalCreateDTO dto) {
        // 1. 匹配审批模板
        ApprovalTemplate template = templateService.matchTemplate(
                dto.getDepartmentCode(), dto.getWorkType());

        ApprovalInstance instance = new ApprovalInstance();
        instance.setWorkOrderId(dto.getWorkOrderId());
        instance.setOrderNo(dto.getOrderNo());
        instance.setTitle(dto.getTitle());
        instance.setSubmitterId(dto.getSubmitterId());
        instance.setSubmitterName(dto.getSubmitterName());
        instance.setDetail(dto.getDetail());
        instance.setDepartmentCode(dto.getDepartmentCode());
        instance.setPriority(dto.getPriority());
        instance.setStatus("PENDING");

        if (template != null) {
            // 2. 有模板：读取节点定义，创建多级审批记录
            List<ApprovalNode> nodes = templateService.getTemplateNodes(template.getId());
            if (nodes != null && !nodes.isEmpty()) {
                instance.setTemplateId(template.getId());
                instance.setTotalNodes(nodes.size());
                instance.setCurrentNode(nodes.get(0).getNodeName());
                instance.setCurrentOrder(1);
                // 第一级审批人
                instance.setApproverId(nodes.get(0).getApproverId());
                instance.setApproverName(null); // 姓名由调用方补充或留空
                instanceMapper.insert(instance);

                // 为每个节点创建 PENDING 状态的 ApprovalRecord
                for (ApprovalNode node : nodes) {
                    ApprovalRecord record = new ApprovalRecord();
                    record.setApprovalId(instance.getId());
                    record.setNodeName(node.getNodeName());
                    record.setNodeOrder(node.getNodeOrder());
                    record.setStatus("PENDING");
                    record.setApproverId(node.getApproverId());
                    record.setApproverName(null);
                    recordMapper.insert(record);
                }
                log.info("基于模板[{}]创建审批单: id={}, 节点数={}",
                        template.getTemplateName(), instance.getId(), nodes.size());
                return instance;
            }
        }

        // 3. 无模板或模板无节点：使用默认单节点审批
        instance.setTotalNodes(1);
        instance.setCurrentNode("主管审批");
        instance.setCurrentOrder(1);
        instance.setApproverId(1L); // 默认管理员
        instance.setApproverName("管理员");
        instanceMapper.insert(instance);

        // 创建默认 PENDING 记录
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(instance.getId());
        record.setNodeName("主管审批");
        record.setNodeOrder(1);
        record.setStatus("PENDING");
        record.setApproverId(1L);
        record.setApproverName("管理员");
        recordMapper.insert(record);

        log.warn("未匹配模板，使用默认单节点审批: workOrderId={}, approvalId={}",
                dto.getWorkOrderId(), instance.getId());
        return instance;
    }

    /**
     * 待审批列表
     * 按审批人 ID 过滤，返回 PENDING/APPROVING 状态的审批单
     */
    @Override
    public PageResult<ApprovalInstance> getPending(Long approverId, Integer current, Integer size) {
        Page<ApprovalInstance> page = new Page<>(current, size);
        LambdaQueryWrapper<ApprovalInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalInstance::getApproverId, approverId)
                .in(ApprovalInstance::getStatus, "PENDING", "APPROVING")
                .orderByAsc(ApprovalInstance::getCreatedAt);
        Page<ApprovalInstance> result = instanceMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    /**
     * 审批操作（五种类型统一入口）
     * <p>
     * APPROVED: 通过当前节点 → 下一级或完结<br>
     * REJECTED: 驳回 → 审批单结束<br>
     * TRANSFER: 转交 → 更新当前节点审批人<br>
     * ADD_NODE: 加签 → 在当前节点后插入新节点<br>
     * REMOVE_NODE: 减签 → 标记指定节点为 SKIPPED
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void action(ApprovalActionDTO dto, Long operatorId, String operatorName) {
        ApprovalInstance instance = instanceMapper.selectById(dto.getApprovalId());
        if (instance == null) {
            throw new BusinessException("审批单不存在");
        }
        if (!"PENDING".equals(instance.getStatus()) && !"APPROVING".equals(instance.getStatus())) {
            throw new BusinessException("该审批单已结束，无法操作");
        }

        switch (dto.getAction()) {
            case "APPROVED":
                handleApproved(instance, dto, operatorId, operatorName);
                break;
            case "REJECTED":
                handleRejected(instance, dto, operatorId, operatorName);
                break;
            case "TRANSFER":
                handleTransfer(instance, dto, operatorId, operatorName);
                break;
            case "ADD_NODE":
                handleAddNode(instance, dto, operatorId, operatorName);
                break;
            case "REMOVE_NODE":
                handleRemoveNode(instance, dto, operatorId, operatorName);
                break;
            default:
                throw new BusinessException("不支持的审批操作: " + dto.getAction());
        }
    }

    /**
     * 获取审批单的全部审批记录（按节点顺序）
     */
    @Override
    public List<ApprovalRecord> getRecords(Long approvalId) {
        return recordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApprovalId, approvalId)
                        .orderByAsc(ApprovalRecord::getNodeOrder));
    }

    /**
     * 审批详情（前端适配）
     * 返回 {workOrder: {...}, nodes: [...]}
     */
    @Override
    public Map<String, Object> getDetail(Long approvalInstanceId) {
        ApprovalInstance instance = instanceMapper.selectById(approvalInstanceId);
        if (instance == null) {
            throw new BusinessException("审批单不存在");
        }

        // 构造 workOrder 信息（前端期望的字段）
        Map<String, Object> workOrder = new HashMap<>();
        workOrder.put("id", instance.getWorkOrderId());
        workOrder.put("orderNo", instance.getOrderNo());
        workOrder.put("title", instance.getTitle());
        workOrder.put("submitterId", instance.getSubmitterId());
        workOrder.put("submitterName", instance.getSubmitterName());
        workOrder.put("detail", instance.getDetail());
        workOrder.put("priority", instance.getPriority());
        workOrder.put("departmentCode", instance.getDepartmentCode());
        workOrder.put("approvalInstanceId", instance.getId());
        workOrder.put("status", instance.getStatus());
        workOrder.put("currentNode", instance.getCurrentNode());
        workOrder.put("currentOrder", instance.getCurrentOrder());
        workOrder.put("totalNodes", instance.getTotalNodes());

        // 获取审批节点列表
        List<ApprovalRecord> nodes = recordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApprovalId, approvalInstanceId)
                        .orderByAsc(ApprovalRecord::getNodeOrder));

        Map<String, Object> result = new HashMap<>();
        result.put("workOrder", workOrder);
        result.put("nodes", nodes);
        return result;
    }

    /**
     * 处理超时审批单（定时任务调用）
     * 查找超过 48 小时仍未处理的审批单，发送督办通知
     */
    @Override
    public void handleTimeoutApprovals() {
        // 使用 Nacos 可配置的超时阈值（普通工单阈值，紧急工单阈值更短）
        LocalDateTime deadline = LocalDateTime.now().minusHours(normalTimeoutHours);
        log.debug("审批超时检测: 阈值={}小时", normalTimeoutHours);
        List<ApprovalInstance> timeoutList = instanceMapper.selectList(
                new LambdaQueryWrapper<ApprovalInstance>()
                        .in(ApprovalInstance::getStatus, "PENDING", "APPROVING")
                        .lt(ApprovalInstance::getCreatedAt, deadline));

        if (timeoutList.isEmpty()) {
            log.debug("无超时审批单");
            return;
        }

        for (ApprovalInstance instance : timeoutList) {
            log.warn("审批单超时: id={}, orderNo={}, 创建时间={}",
                    instance.getId(), instance.getOrderNo(), instance.getCreatedAt());
            // 发送督办通知给当前审批人
            sendNotification(instance.getApproverId(), "审批超时督办",
                    "工单[" + instance.getOrderNo() + "]审批已超时，请尽快处理",
                    "URGE_NOTIFY", instance.getWorkOrderId());
            // 同时通知提交人
            sendNotification(instance.getSubmitterId(), "审批超时提醒",
                    "您的工单[" + instance.getOrderNo() + "]审批已超时，系统已督办",
                    "TIMEOUT_NOTIFY", instance.getWorkOrderId());
        }
        log.info("超时审批处理完成，共处理 {} 条", timeoutList.size());
    }

    // ==================== 私有方法：五种操作实现 ====================

    /**
     * 通过审批
     * - 更新当前节点 record 为 APPROVED
     * - 若有后续节点：推进到下一级
     * - 若全部节点通过：审批单完结
     */
    private void handleApproved(ApprovalInstance instance, ApprovalActionDTO dto,
                                 Long operatorId, String operatorName) {
        // 更新当前节点记录
        updateCurrentRecord(instance, "APPROVED", operatorId, operatorName, dto.getOpinion());

        if (instance.getCurrentOrder() >= instance.getTotalNodes()) {
            // 所有节点通过，审批完结
            instance.setStatus("APPROVED");
            instanceMapper.updateById(instance);
            log.info("审批全部通过: approvalId={}, orderNo={}", instance.getId(), instance.getOrderNo());
            // 回调 work-order-service 更新工单状态为 COMPLETED
            updateWorkOrderStatus(instance.getWorkOrderId(), "COMPLETED", "审批全部通过，工单完结");
            sendNotification(instance.getSubmitterId(), "审批结果通知",
                    "您的工单[" + instance.getOrderNo() + "]已通过全部审批",
                    "APPROVE_NOTIFY", instance.getWorkOrderId());
        } else {
            // 进入下一节点（跳过 SKIPPED 状态的节点）
            int nextOrder = instance.getCurrentOrder() + 1;
            ApprovalRecord nextRecord = findNextActiveRecord(instance.getId(), nextOrder);
            if (nextRecord != null) {
                instance.setCurrentOrder(nextRecord.getNodeOrder());
                instance.setCurrentNode(nextRecord.getNodeName());
                instance.setApproverId(nextRecord.getApproverId());
                instance.setStatus("APPROVING");
            } else {
                // 无后续有效节点，审批完结
                instance.setStatus("APPROVED");
                log.info("无后续有效节点，审批完结: approvalId={}", instance.getId());
            }
            instanceMapper.updateById(instance);
            log.info("进入下一级审批: approvalId={}, nextOrder={}", instance.getId(),
                    nextRecord != null ? nextRecord.getNodeOrder() : "完结");
        }
    }

    /**
     * 驳回审批
     * - 更新当前节点 record 为 REJECTED
     * - 审批单状态改为 REJECTED，流程结束
     */
    private void handleRejected(ApprovalInstance instance, ApprovalActionDTO dto,
                                 Long operatorId, String operatorName) {
        updateCurrentRecord(instance, "REJECTED", operatorId, operatorName, dto.getOpinion());
        instance.setStatus("REJECTED");
        instanceMapper.updateById(instance);

        log.info("审批已驳回: approvalId={}, opinion={}", instance.getId(), dto.getOpinion());
        // 回调 work-order-service 更新工单状态为 REJECTED
        updateWorkOrderStatus(instance.getWorkOrderId(), "REJECTED", "审批驳回: " + dto.getOpinion());
        sendNotification(instance.getSubmitterId(), "审批结果通知",
                "您的工单[" + instance.getOrderNo() + "]已被驳回，原因：" + dto.getOpinion(),
                "REJECT_NOTIFY", instance.getWorkOrderId());
    }

    /**
     * 转交审批
     * - 记录转交操作到当前节点 record
     * - 更新当前节点审批人为目标审批人
     * - 审批单状态不变，继续等待新审批人处理
     */
    private void handleTransfer(ApprovalInstance instance, ApprovalActionDTO dto,
                                 Long operatorId, String operatorName) {
        if (dto.getTransferToUserId() == null) {
            throw new BusinessException("转交目标审批人不能为空");
        }
        // 记录转交操作
        ApprovalRecord record = findCurrentRecord(instance);
        if (record != null) {
            record.setOperatorId(operatorId);
            record.setOperatorName(operatorName);
            record.setAction("TRANSFER");
            record.setOpinion("转交给: " + dto.getTransferToUserName());
            record.setOperatedAt(LocalDateTime.now());
            // 更新审批人为目标人
            record.setApproverId(dto.getTransferToUserId());
            record.setApproverName(dto.getTransferToUserName());
            recordMapper.updateById(record);
        }
        // 更新实例的当前审批人
        instance.setApproverId(dto.getTransferToUserId());
        instance.setApproverName(dto.getTransferToUserName());
        instanceMapper.updateById(instance);

        log.info("审批转交: approvalId={}, from={}, to={}",
                instance.getId(), operatorName, dto.getTransferToUserName());
        sendNotification(dto.getTransferToUserId(), "审批转交通知",
                operatorName + "将工单[" + instance.getOrderNo() + "]转交给您审批",
                "TRANSFER_NOTIFY", instance.getWorkOrderId());
    }

    /**
     * 加签（在当前节点后插入新审批节点）
     * - 后续节点 order 全部 +1（后移）
     * - 插入新的 ApprovalRecord（PENDING）
     * - totalNodes + 1
     */
    private void handleAddNode(ApprovalInstance instance, ApprovalActionDTO dto,
                                Long operatorId, String operatorName) {
        if (dto.getAddNodeApproverId() == null) {
            throw new BusinessException("加签审批人不能为空");
        }

        int insertOrder = instance.getCurrentOrder() + 1;
        // 后续节点 order 全部 +1（后移）
        List<ApprovalRecord> laterRecords = recordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApprovalId, instance.getId())
                        .ge(ApprovalRecord::getNodeOrder, insertOrder)
                        .orderByAsc(ApprovalRecord::getNodeOrder));
        for (ApprovalRecord r : laterRecords) {
            r.setNodeOrder(r.getNodeOrder() + 1);
            recordMapper.updateById(r);
        }

        // 插入新节点
        ApprovalRecord newRecord = new ApprovalRecord();
        newRecord.setApprovalId(instance.getId());
        newRecord.setNodeName(dto.getAddNodeName() != null ? dto.getAddNodeName() : "加签审批");
        newRecord.setNodeOrder(insertOrder);
        newRecord.setStatus("PENDING");
        newRecord.setApproverId(dto.getAddNodeApproverId());
        newRecord.setApproverName(dto.getAddNodeApproverName());
        recordMapper.insert(newRecord);

        // 更新实例总节点数
        instance.setTotalNodes(instance.getTotalNodes() + 1);
        instanceMapper.updateById(instance);

        log.info("加签成功: approvalId={}, newNodeOrder={}, approver={}",
                instance.getId(), insertOrder, dto.getAddNodeApproverName());
    }

    /**
     * 减签（跳过指定后续节点）
     * - 将目标节点 record 标记为 SKIPPED
     * - 审批推进时自动跳过 SKIPPED 节点
     */
    private void handleRemoveNode(ApprovalInstance instance, ApprovalActionDTO dto,
                                   Long operatorId, String operatorName) {
        if (dto.getRemoveNodeOrder() == null) {
            throw new BusinessException("要跳过的节点序号不能为空");
        }
        int targetOrder = dto.getRemoveNodeOrder();
        if (targetOrder <= instance.getCurrentOrder()) {
            throw new BusinessException("只能跳过后续未处理的节点");
        }

        ApprovalRecord targetRecord = findRecordByOrder(instance.getId(), targetOrder);
        if (targetRecord == null) {
            throw new BusinessException("节点不存在: order=" + targetOrder);
        }
        // 标记为 SKIPPED
        targetRecord.setStatus("SKIPPED");
        targetRecord.setOperatorId(operatorId);
        targetRecord.setOperatorName(operatorName);
        targetRecord.setAction("REMOVE_NODE");
        targetRecord.setOpinion("减签跳过");
        targetRecord.setOperatedAt(LocalDateTime.now());
        recordMapper.updateById(targetRecord);

        log.info("减签成功: approvalId={}, skippedOrder={}", instance.getId(), targetOrder);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 更新当前节点的审批记录
     */
    private void updateCurrentRecord(ApprovalInstance instance, String action,
                                      Long operatorId, String operatorName, String opinion) {
        ApprovalRecord record = findCurrentRecord(instance);
        if (record != null) {
            record.setStatus(action); // APPROVED 或 REJECTED
            record.setOperatorId(operatorId);
            record.setOperatorName(operatorName);
            record.setAction(action);
            record.setOpinion(opinion);
            record.setOperatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
        }
    }

    /**
     * 查找当前节点的审批记录
     */
    private ApprovalRecord findCurrentRecord(ApprovalInstance instance) {
        return recordMapper.selectOne(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApprovalId, instance.getId())
                        .eq(ApprovalRecord::getNodeOrder, instance.getCurrentOrder())
                        .last("LIMIT 1"));
    }

    /**
     * 按节点序号查找审批记录
     */
    private ApprovalRecord findRecordByOrder(Long approvalId, int order) {
        return recordMapper.selectOne(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApprovalId, approvalId)
                        .eq(ApprovalRecord::getNodeOrder, order)
                        .last("LIMIT 1"));
    }

    /**
     * 查找下一个有效（非 SKIPPED）的审批记录
     * 从指定 order 开始，跳过 SKIPPED 节点
     */
    private ApprovalRecord findNextActiveRecord(Long approvalId, int fromOrder) {
        List<ApprovalRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApprovalId, approvalId)
                        .ge(ApprovalRecord::getNodeOrder, fromOrder)
                        .ne(ApprovalRecord::getStatus, "SKIPPED")
                        .orderByAsc(ApprovalRecord::getNodeOrder)
                        .last("LIMIT 1"));
        return records.isEmpty() ? null : records.get(0);
    }

    /**
     * 发送通知（封装 Feign 调用，失败不抛异常）
     */
    /**
     * 回调 work-order-service 更新工单状态（审批通过/驳回后）
     */
    private void updateWorkOrderStatus(Long workOrderId, String status, String remark) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("status", status);
            body.put("remark", remark);
            workOrderFeignClient.updateStatus(workOrderId, body);
        } catch (Exception e) {
            log.error("回调更新工单状态失败: workOrderId={}, status={}, error={}", workOrderId, status, e.getMessage());
        }
    }

    private void sendNotification(Long receiverId, String title, String content,
                                   String msgType, Long bizId) {
        try {
            Map<String, Object> notify = new HashMap<>();
            notify.put("receiverId", receiverId);
            notify.put("title", title);
            notify.put("content", content);
            notify.put("msgType", msgType);
            notify.put("bizType", "WORK_ORDER");
            notify.put("bizId", bizId);
            messageFeignClient.sendNotify(notify);
        } catch (Exception e) {
            log.error("通知发送失败: receiverId={}, title={}, error={}", receiverId, title, e.getMessage());
        }
    }
}
