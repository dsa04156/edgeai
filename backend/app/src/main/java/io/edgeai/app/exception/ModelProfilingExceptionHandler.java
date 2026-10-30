package io.edgeai.app.exception;

import io.edgeai.app.controller.ModelProfilingController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=ModelProfilingController.class)
public class ModelProfilingExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,ServletRequestBindingException.class})
    ResponseEntity<ApiErrorResponse> invalid() { return error(400,"INVALID_PROFILING_RUN","측정 요청의 필드·UUID·Idempotency-Key·페이지 입력을 확인하세요."); }
    @ExceptionHandler(ProfilePayloadTooLargeException.class)
    ResponseEntity<ApiErrorResponse> tooLarge() { return error(413,"PAYLOAD_TOO_LARGE","측정 요청은 UTF-8 JSON 128 KiB 이하로 입력하세요."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> conflict() { return error(409,"PROFILING_RUN_ACTIVE","동시에 시작된 측정과 충돌했습니다. 측정 기록을 다시 조회하세요."); }
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> unavailable() { return error(503,"AI_MODEL_STORE_UNAVAILABLE","AI 모델 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요."); }
    private ResponseEntity<ApiErrorResponse> error(int status,String code,String message) { return ResponseEntity.status(status).body(new ApiErrorResponse(code,message)); }
}
