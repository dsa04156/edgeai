package io.edgeai.app.exception;

import io.edgeai.app.controller.InferenceController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=InferenceController.class)
public class InferenceExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return ResponseEntity.status(e.status()).body(new ApiErrorResponse(e.code(),e.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalid(){return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_INFERENCE_REQUEST","modelId와 JPEG/PNG의 base64(imageBase64)를 JSON으로 입력하세요."));}
}
