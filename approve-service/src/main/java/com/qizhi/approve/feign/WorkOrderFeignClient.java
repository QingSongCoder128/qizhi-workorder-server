package com.qizhi.approve.feign;

import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "work-order-service", path = "/api/v1/workorder", configuration = InternalCallFeignConfiguration.class)
public interface WorkOrderFeignClient {

    /** 更新工单状态（审批完成/驳回后回调） */
    @PutMapping("/{id}/status")
    R<Void> updateStatus(@PathVariable("id") Long id, @RequestBody Map<String, String> body);

    /** 查询工单详情（含 type/createdAt/AI 分析字段，供审批详情展示） */
    @GetMapping("/{id}")
    R<Map<String, Object>> getDetailById(@PathVariable("id") Long id);
}
