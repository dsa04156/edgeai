package io.edgeai.app.exception;
import io.edgeai.app.controller.*;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes={DeviceController.class,NodeController.class})
public class DeviceExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> invalid() { return error(400,"INVALID_DEVICE","입력 필드·UUID·페이지·시각·JSON 형식을 확인하세요."); }
    @ExceptionHandler(ProfilePayloadTooLargeException.class)
    ResponseEntity<ApiErrorResponse> tooLarge() { return error(413,"PAYLOAD_TOO_LARGE","장치 요청은 UTF-8 16 KiB 이하로 입력하세요."); }
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> unavailable() { return error(503,"DEVICE_STORE_UNAVAILABLE","장치 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요."); }
    private ResponseEntity<ApiErrorResponse> error(int status,String code,String message) { return ResponseEntity.status(status).body(new ApiErrorResponse(code,message)); }
}
