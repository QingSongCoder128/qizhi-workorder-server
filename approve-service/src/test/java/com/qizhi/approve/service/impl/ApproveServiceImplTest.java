package com.qizhi.approve.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qizhi.approve.dto.ApprovalActionDTO;
import com.qizhi.approve.entity.ApprovalInstance;
import com.qizhi.approve.entity.ApprovalRecord;
import com.qizhi.approve.feign.MessageFeignClient;
import com.qizhi.approve.feign.UserFeignClient;
import com.qizhi.approve.feign.WorkOrderFeignClient;
import com.qizhi.approve.mapper.ApprovalInstanceMapper;
import com.qizhi.approve.mapper.ApprovalRecordMapper;
import com.qizhi.approve.service.TemplateService;
import com.qizhi.common.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApproveServiceImplTest {

    private ApprovalInstanceMapper instanceMapper;
    private ApprovalRecordMapper recordMapper;
    private ApproveServiceImpl service;

    @BeforeEach
    void setUp() {
        instanceMapper = mock(ApprovalInstanceMapper.class);
        recordMapper = mock(ApprovalRecordMapper.class);
        service = new ApproveServiceImpl(
                instanceMapper,
                recordMapper,
                mock(MessageFeignClient.class),
                mock(WorkOrderFeignClient.class),
                mock(UserFeignClient.class),
                mock(TemplateService.class));
    }

    @Test
    void rejectsNonCurrentApproverAndStaleVersion() {
        ApprovalInstance instance = pendingInstance();
        when(instanceMapper.selectById(8L)).thenReturn(instance);

        ApprovalActionDTO forbidden = action("TRANSFER", 1);
        forbidden.setTransferToUserId(4L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.action(forbidden, 99L, "other")).getCode());

        ApprovalActionDTO stale = action("TRANSFER", 0);
        stale.setTransferToUserId(4L);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.action(stale, 3L, "current")).getCode());
        verifyNoInteractions(recordMapper);
    }

    @Test
    void transferChangesOnlyCurrentInstanceSnapshot() {
        ApprovalInstance instance = pendingInstance();
        ApprovalRecord current = record(8L, 1, 3L);
        when(instanceMapper.selectById(8L)).thenReturn(instance);
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(current);

        ApprovalActionDTO dto = action("TRANSFER", 1);
        dto.setTransferToUserId(4L);
        dto.setTransferToUserName("new approver");
        service.action(dto, 3L, "current");

        assertEquals(4L, instance.getApproverId());
        assertEquals("TRANSFER", current.getAction());
        assertEquals(4L, current.getApproverId());
        verify(instanceMapper).updateById(instance);
        verify(recordMapper).updateById(current);
    }

    @Test
    void addNodeShiftsOnlyRecordsFromSameInstance() {
        ApprovalInstance instance = pendingInstance();
        ApprovalRecord later = record(8L, 2, 4L);
        when(instanceMapper.selectById(8L)).thenReturn(instance);
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(later));

        ApprovalActionDTO dto = action("ADD_NODE", 1);
        dto.setAddNodeApproverId(5L);
        dto.setAddNodeApproverName("added");
        dto.setAddNodeName("security review");
        service.action(dto, 3L, "current");

        ArgumentCaptor<ApprovalRecord> inserted = ArgumentCaptor.forClass(ApprovalRecord.class);
        verify(recordMapper).insert(inserted.capture());
        assertEquals(8L, inserted.getValue().getApprovalId());
        assertEquals(2, inserted.getValue().getNodeOrder());
        assertEquals("ADD_NODE", inserted.getValue().getAction());
        assertEquals(3, later.getNodeOrder());
        assertEquals(3, instance.getTotalNodes());
    }

    @Test
    void removeNodeProtectsTheOnlyRemainingNode() {
        ApprovalInstance instance = pendingInstance();
        ApprovalRecord target = record(8L, 2, 4L);
        when(instanceMapper.selectById(8L)).thenReturn(instance);
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(target);
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        ApprovalActionDTO dto = action("REMOVE_NODE", 1);
        dto.setRemoveNodeOrder(2);
        assertThrows(BusinessException.class, () -> service.action(dto, 3L, "current"));
        verify(recordMapper, never()).updateById(target);
    }

    private ApprovalInstance pendingInstance() {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(8L);
        instance.setWorkOrderId(18L);
        instance.setSubmitterId(2L);
        instance.setApproverId(3L);
        instance.setApproverName("current");
        instance.setStatus("PENDING");
        instance.setVersionNo(1);
        instance.setCurrentOrder(1);
        instance.setTotalNodes(2);
        return instance;
    }

    private ApprovalActionDTO action(String action, int version) {
        ApprovalActionDTO dto = new ApprovalActionDTO();
        dto.setApprovalId(8L);
        dto.setAction(action);
        dto.setVersionNo(version);
        return dto;
    }

    private ApprovalRecord record(long approvalId, int order, long approverId) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(approvalId);
        record.setNodeOrder(order);
        record.setApproverId(approverId);
        record.setStatus("PENDING");
        return record;
    }
}
