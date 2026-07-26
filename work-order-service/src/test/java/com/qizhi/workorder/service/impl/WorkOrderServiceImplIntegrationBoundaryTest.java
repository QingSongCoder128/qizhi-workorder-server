package com.qizhi.workorder.service.impl;

import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.R;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.workorder.entity.OperationLog;
import com.qizhi.workorder.entity.WorkOrder;
import com.qizhi.workorder.feign.AiProcessFeignClient;
import com.qizhi.workorder.feign.ApproveFeignClient;
import com.qizhi.workorder.feign.MessageFeignClient;
import com.qizhi.workorder.mapper.OperationLogMapper;
import com.qizhi.workorder.mapper.WorkOrderAttachmentMapper;
import com.qizhi.workorder.mapper.WorkOrderHistoryMapper;
import com.qizhi.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkOrderServiceImplIntegrationBoundaryTest {

    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private WorkOrderHistoryMapper historyMapper;
    @Mock private WorkOrderAttachmentMapper attachmentMapper;
    @Mock private OperationLogMapper operationLogMapper;
    @Mock private AiProcessFeignClient aiProcessFeignClient;
    @Mock private ApproveFeignClient approveFeignClient;
    @Mock private MessageFeignClient messageFeignClient;
    @Mock private RedisUtil redisUtil;

    @InjectMocks
    private WorkOrderServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void configureStorage() {
        ReflectionTestUtils.setField(service, "attachmentStorageRoot", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxAttachmentSize", 5L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "allowedAttachmentExtensions",
                List.of("jpg", "jpeg", "png", "pdf"));
    }

    @Test
    void uploadAndResolveMustStayInsideConfiguredModuleDirectory() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evidence.png", "image/png", new byte[]{1, 2, 3});

        Map<String, String> uploaded = service.uploadAttachment(file);
        String filename = uploaded.get("url").substring(uploaded.get("url").lastIndexOf('/') + 1);
        Path stored = service.resolveAttachment(filename);

        assertTrue(stored.startsWith(tempDir.toAbsolutePath().normalize()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(stored));
        assertEquals("/api/v1/employee/workorders/attachment/" + filename,
                uploaded.get("url"));
        assertThrows(BusinessException.class,
                () -> service.resolveAttachment("../" + filename));
    }

    @Test
    void approvalTransactionWritesOperationLogWithoutExtraMicroservice() {
        WorkOrder order = new WorkOrder();
        order.setId(42L);
        order.setOrderNo("WO-TEST-42");
        order.setStatus("PENDING_AI");
        order.setType("OPS_REPAIR");
        order.setTitle("测试工单");
        order.setDetail("测试详情");
        order.setDepartmentCode("DEPT_IT");
        order.setPriority("NORMAL");

        doReturn(R.ok(1L)).when(approveFeignClient).createApproval(any());
        when(operationLogMapper.insert(any())).thenReturn(1);

        service.updateStatusAndCreateApproval(order, 7L, "测试用户");

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        assertEquals("WORK_ORDER", captor.getValue().getModule());
        assertEquals("SUBMIT", captor.getValue().getAction());
        assertEquals(42L, captor.getValue().getTargetId());
        verify(workOrderMapper).updateById(order);
    }

    @Test
    void failedLogInsertAbortsTransactionMethod() {
        WorkOrder order = new WorkOrder();
        order.setId(43L);
        order.setOrderNo("WO-TEST-43");
        order.setStatus("PENDING_AI");
        order.setType("OPS_REPAIR");
        order.setTitle("回滚测试");
        order.setDetail("回滚测试");
        order.setDepartmentCode("DEPT_IT");
        order.setPriority("NORMAL");

        doReturn(R.ok(1L)).when(approveFeignClient).createApproval(any());
        when(operationLogMapper.insert(any())).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.updateStatusAndCreateApproval(order, 7L, "测试用户"));
    }
}
