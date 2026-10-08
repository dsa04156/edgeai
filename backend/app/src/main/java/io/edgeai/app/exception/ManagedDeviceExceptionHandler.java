package io.edgeai.app.exception;

import io.edgeai.app.controller.ManagedDeviceController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=ManagedDeviceController.class)
public class ManagedDeviceExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return ResponseEntity.status(e.status()).body(new ApiErrorResponse(e.code(),e.getMessage()));}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> invalid(){return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_DEVICE_PROFILE","장치 이름·연결·property·노드 설정을 확인하세요."));}
    @ExceptionHandler(ProfilePayloadTooLargeException.class)
    ResponseEntity<ApiErrorResponse> large(){return ResponseEntity.status(413).body(new ApiErrorResponse("PAYLOAD_TOO_LARGE","장치 요청은 UTF-8 64 KiB 이하로 입력하세요."));}
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiErrorResponse> unavailable(){return ResponseEntity.status(503).body(new ApiErrorResponse("DEVICE_CONTROL_UNAVAILABLE","KubeEdge 연결과 리소스 권한을 확인하세요."));}
}
