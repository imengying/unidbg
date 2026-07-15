package com.mengying.fqnovel.web;

import com.mengying.fqnovel.dto.FQNovelResponse;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        // 请求超时通常代表上游阻塞或客户端等待窗口结束，不应直接触发自愈/重启计数。
        if (log.isDebugEnabled()) {
            log.debug("异步请求超时", ex);
        } else {
            log.warn("异步请求超时");
        }
        return buildError(HttpStatus.GATEWAY_TIMEOUT, "request timeout");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        if (log.isDebugEnabled()) {
            log.debug("请求体解析失败: {}", ex.getMessage());
        }
        return buildError(HttpStatus.BAD_REQUEST, "Invalid request body format");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        if (log.isDebugEnabled()) {
            log.debug("不支持的请求方法: {}", ex.getMessage());
        }
        return buildError(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed: " + ex.getMethod());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleMissingRequestParameter(MissingServletRequestParameterException ex) {
        String parameterName = ex.getParameterName();
        if (log.isDebugEnabled()) {
            log.debug("缺少请求参数: {}", parameterName);
        }
        return buildError(HttpStatus.BAD_REQUEST, "Missing required parameter: " + parameterName);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String parameterName = ex.getName();
        if (log.isDebugEnabled()) {
            log.debug("请求参数类型错误: {} = {}", parameterName, ex.getValue());
        }
        return buildError(HttpStatus.BAD_REQUEST, "Invalid parameter type: " + parameterName);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleServletBinding(ServletRequestBindingException ex) {
        if (log.isDebugEnabled()) {
            log.debug("请求绑定失败: {}", ex.getMessage());
        }
        return buildError(HttpStatus.BAD_REQUEST, "Bad request");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        return buildError(HttpStatus.BAD_REQUEST, messageOrDefault(ex.getMessage(), "bad request"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        if (log.isDebugEnabled()) {
            log.debug("静态资源不存在: {}", ex.getMessage());
        }
        return buildError(HttpStatus.NOT_FOUND, "Not Found");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<FQNovelResponse<Void>> handleNoHandlerFound(NoHandlerFoundException ex) {
        if (log.isDebugEnabled()) {
            log.debug("请求路径不存在: {}", ex.getRequestURL());
        }
        return buildError(HttpStatus.NOT_FOUND, "Not Found");
    }

    /**
     * 客户端主动断开连接（浏览器取消请求/网络中断）属于常见噪音，不按服务异常记录。
     * 使用 void 返回避免再次写响应触发二次 Broken pipe 日志。
     */
    @ExceptionHandler({SocketException.class, EOFException.class, ClosedChannelException.class})
    public void handleClientAbort(Exception ex) {
        if (log.isDebugEnabled()) {
            log.debug("客户端已断开连接: {}", ex.getMessage());
        }
    }

    /**
     * 异步响应写回阶段，若客户端已经断开，Spring 会抛出 AsyncRequestNotUsableException。
     * 该场景属于常见网络噪音，不应继续写响应，否则会触发重复异常日志。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        if (log.isDebugEnabled()) {
            log.debug("异步响应不可用（客户端可能已断开）: {}", ex.getMessage());
        }
    }

    /**
     * JSON 写回阶段常会包裹一层 HttpMessageNotWritableException。
     * 若根因是客户端断开，则按噪音处理；否则返回统一 500 JSON。
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public void handleMessageNotWritable(HttpMessageNotWritableException ex, HttpServletResponse response) {
        if (isClientAbortLike(ex)) {
            if (log.isDebugEnabled()) {
                log.debug("响应写回失败（客户端已断开）: {}", ex.getMessage());
            }
            return;
        }
        log.error("响应写回失败", ex);
        writeJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FQNovelResponse<Void>> handleException(Exception ex) {
        log.error("全局异常捕获: {}", ex.getMessage(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }

    private static String messageOrDefault(String message, String defaultMessage) {
        return Objects.requireNonNullElse(message, defaultMessage);
    }

    private static boolean isClientAbortLike(Throwable throwable) {
        return hasCause(throwable, SocketException.class)
            || hasCause(throwable, EOFException.class)
            || hasCause(throwable, ClosedChannelException.class)
            || hasCause(throwable, AsyncRequestNotUsableException.class)
            || hasCauseClassName(throwable, "org.apache.catalina.connector.ClientAbortException");
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static boolean hasCauseClassName(Throwable throwable, String className) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor.getClass().getName().equals(className)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static ResponseEntity<FQNovelResponse<Void>> buildError(HttpStatus status, String message) {
        return ResponseEntity
            .status(status)
            .body(FQNovelResponse.error(status.value(), message));
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status, String message) {
        if (response == null || response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        try {
            objectMapper.writeValue(response.getWriter(), FQNovelResponse.error(status.value(), message));
        } catch (Exception writeEx) {
            if (log.isDebugEnabled()) {
                log.debug("统一错误响应写回失败: {}", writeEx.getMessage());
            }
        }
    }
}
