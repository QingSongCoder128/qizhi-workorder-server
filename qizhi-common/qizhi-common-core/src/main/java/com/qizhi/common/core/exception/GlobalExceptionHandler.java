package com.qizhi.common.core.exception;

import com.qizhi.common.core.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** Seata/AOP 包装的异常（递归解包 BusinessException） */
    @ExceptionHandler(RuntimeException.class)
    public R<Void> handleRuntimeException(RuntimeException e) {
        // Seata AdapterInvocationWrapper / Spring AOP 可能多层包装 BusinessException
        Throwable cause = e;
        for (int i = 0; i < 5 && cause != null; i++) {
            if (cause instanceof BusinessException be) {
                log.warn("业务异常(包装解包): {}", be.getMessage());
                return R.fail(be.getCode(), be.getMessage());
            }
            cause = cause.getCause();
        }
        log.error("系统异常: type={}, msg={}", e.getClass().getName(), e.getMessage(), e);
        return R.fail("系统内部错误，请稍后再试");
    }

    /** 参数校验异常 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return R.fail(400, msg);
    }

    /** 参数绑定异常 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", msg);
        return R.fail(400, msg);
    }

    /** 未知异常 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail("系统内部错误，请稍后再试");
    }
}
