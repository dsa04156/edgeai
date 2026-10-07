package io.edgeai.app.exception;

import io.edgeai.app.controller.SensorController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={SensorController.class, io.edgeai.app.controller.SensorRegistrationController.class})
public class SensorExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e) {return ResponseEntity.status(e.status()).body(new ApiErrorResponse(e.code(),e.getMessage()));}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.ServletRequestBindingException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> invalid() {return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_SENSOR_REQUEST","센서 이름·명령·입력값을 확인하세요."));}
}
