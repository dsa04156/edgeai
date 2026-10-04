package io.edgeai.app.exception;
import io.edgeai.app.controller.ManagementAuditController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=ManagementAuditController.class)
public class ManagementAuditExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiErrorResponse> invalid() { return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_AUDIT_QUERY","감사 ID와 페이지 범위를 확인하세요.")); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiErrorResponse> unavailable() { return ResponseEntity.status(503).body(new ApiErrorResponse("AUDIT_STORE_UNAVAILABLE","감사 저장소에 연결할 수 없습니다.")); }
}
