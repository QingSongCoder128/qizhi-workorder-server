package com.qizhi.log.controller;

import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.core.result.R;
import com.qizhi.log.entity.OperationLog;
import com.qizhi.log.mapper.OperationLogMapper;
import io.seata.core.context.RootContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/log")
public class OperationLogController {

    private final OperationLogMapper operationLogMapper;

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public R<Long> create(@RequestBody Map<String, Object> request) {
        String module = stringValue(request.get("module"));
        String action = stringValue(request.get("action"));
        if (!StringUtils.hasText(module) || !StringUtils.hasText(action)) {
            throw new BusinessException("日志模块和操作类型不能为空");
        }

        OperationLog log = new OperationLog();
        log.setUserId(longValue(request.get("userId")));
        log.setUserName(stringValue(request.get("userName")));
        log.setModule(module);
        log.setAction(action);
        log.setTargetType(stringValue(request.get("targetType")));
        log.setTargetId(longValue(request.get("targetId")));
        log.setDetail(stringValue(request.get("detail")));
        log.setSeataXid(RootContext.getXID());
        log.setCreatedAt(LocalDateTime.now());
        operationLogMapper.insert(log);
        return R.ok(log.getId());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }
}
