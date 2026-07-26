package com.qizhi.gateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qizhi.common.core.result.R;
import com.qizhi.gateway.filter.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 全局异常处理器
 * <p>
 * SRS 需求: GW-05 非法路径匹配统一返回 404，封装标准 JSON 错误响应体
 * {"code":404,"msg":"接口不存在"}
 * </p>
 * <p>
 * 优先级 @Order(-1) 高于默认的 DefaultErrorWebExceptionHandler，
 * 确保所有未匹配路由的请求都返回统一 JSON 格式。
 * </p>
 */
@Slf4j
@Order(-1)
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        // 响应已提交则无法再写入
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        // 判断异常类型，确定 HTTP 状态码和业务消息
        HttpStatus status;
        String message;

        if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            message = switch (status) {
                case NOT_FOUND -> "接口不存在";
                case SERVICE_UNAVAILABLE -> "服务暂不可用，请稍后重试";
                case GATEWAY_TIMEOUT -> "服务响应超时";
                default -> rse.getReason() != null ? rse.getReason() : "网关异常";
            };
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "网关内部错误";
            log.error("Gateway未捕获异常: path={}", exchange.getRequest().getURI().getPath(), ex);
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // 构建统一 JSON 响应体
        R<Void> result = R.fail(status.value(), message);
        result.setTraceId(exchange.getRequest().getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            DataBufferFactory bufferFactory = response.bufferFactory();
            return response.writeWith(Mono.just(bufferFactory.wrap(bytes)));
        } catch (JsonProcessingException e) {
            log.error("序列化网关错误响应失败", e);
            return response.setComplete();
        }
    }
}
