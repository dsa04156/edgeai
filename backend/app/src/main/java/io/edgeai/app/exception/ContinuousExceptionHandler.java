package io.edgeai.app.exception;

import io.edgeai.app.controller.ContinuousDeviceController;
import io.edgeai.app.controller.ProcessingEngineController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={ContinuousDeviceController.class,ProcessingEngineController.class})
public class ContinuousExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return ResponseEntity.status(e.status()).body(new ApiErrorResponse(e.code(),e.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_CONTINUOUS_INPUT",e.getMessage()));}
}
