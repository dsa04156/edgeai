package io.edgeai.app.exception;

import io.edgeai.app.controller.ScenarioController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ScenarioController.class)
public class ScenarioExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e) { return error(e.status(), e.code(), e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class, ProfilePayloadTooLargeException.class, MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ResponseEntity<ApiErrorResponse> invalid() {
        return error(400, "INVALID_SCENARIO_REQUEST", "UUID Idempotency-Key와 1~100 사이의 imageCount, 페이지 입력을 확인하세요.");
    }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> unavailable() {
        return error(503, "SCENARIO_STORE_UNAVAILABLE", "비교 실행 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요.");
    }
    private ResponseEntity<ApiErrorResponse> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
