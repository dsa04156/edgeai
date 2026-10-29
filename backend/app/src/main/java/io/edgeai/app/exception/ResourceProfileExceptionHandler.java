package io.edgeai.app.exception;

import io.edgeai.app.controller.ResourceProfileController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=ResourceProfileController.class)
public class ResourceProfileExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,ServletRequestBindingException.class})
    ResponseEntity<ApiErrorResponse> invalid() { return error(400,"INVALID_RESOURCE_PROFILE","자원 프로파일 요청의 필드·UUID·Idempotency-Key·페이지 입력을 확인하세요."); }
    @ExceptionHandler(ProfilePayloadTooLargeException.class)
    ResponseEntity<ApiErrorResponse> tooLarge() { return error(413,"PAYLOAD_TOO_LARGE","자원 프로파일 요청은 UTF-8 JSON 64 KiB 이하로 입력하세요."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> conflict() { return error(409,"RESOURCE_PROFILE_CONFLICT","동시에 저장된 자원 프로파일과 충돌했습니다. 다시 시도하세요."); }
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> unavailable() { return error(503,"RESOURCE_PROFILE_STORE_UNAVAILABLE","자원 프로파일 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요."); }
    private ResponseEntity<ApiErrorResponse> error(int status,String code,String message) { return ResponseEntity.status(status).body(new ApiErrorResponse(code,message)); }
}
