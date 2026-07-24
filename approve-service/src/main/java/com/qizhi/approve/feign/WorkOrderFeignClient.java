package com.qizhi.approve.feign;

import com.qizhi.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "work-order-service", path = "/api/v1/workorder")
public interface WorkOrderFeignClient {

    /** 更新工单状态（审批完成/驳回后回调） */
    @PutMapping("/{id}/status")
    R<Void> updateStatus(@PathVariable("id") Long id, @RequestBody Map<String, String> body);
}
