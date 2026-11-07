package com.moshanghuakai.exhibition.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/** 集中处理业务异常，内部错误不向前端暴露 SQL、文件路径和设备凭据。 @author MoShangHuaKai */
@Slf4j
@RestControllerAdvice
public class ExceptionHandlerAdvice {
  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    com.fasterxml.jackson.core.JsonProcessingException.class
  })
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResult<Void> malformed(Exception e) {
    return new ApiResult<>(400, "请求参数或 JSON 格式不正确", null);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResult<Void>> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatus())
        .body(new ApiResult<>(e.getStatus().value(), e.getReason(), null));
  }

  @ExceptionHandler({
    BusinessException.class,
    IllegalArgumentException.class,
    MaxUploadSizeExceededException.class
  })
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResult<Void> business(Exception e) {
    return new ApiResult<>(
        400, e instanceof MaxUploadSizeExceededException ? "上传文件超过大小限制" : e.getMessage(), null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResult<Void> validation(MethodArgumentNotValidException e) {
    return new ApiResult<>(
        400, e.getBindingResult().getFieldErrors().get(0).getDefaultMessage(), null);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ApiResult<Void> duplicate() {
    return new ApiResult<>(409, "唯一标识已存在，请检查工号或刷新后重试", null);
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResult<Void> internal(Exception e) {
    log.error("业务请求处理失败", e);
    return new ApiResult<>(500, "处理失败，请联系管理员查看服务日志", null);
  }
}
