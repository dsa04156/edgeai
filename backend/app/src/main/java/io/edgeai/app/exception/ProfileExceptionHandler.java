package io.edgeai.app.exception;

import io.edgeai.app.controller.ProfileController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ProfileController.class)
public class ProfileExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> invalid(Exception e) {
        // Do not echo raw JSON or rejected values into logs/responses.
        return error(400, "INVALID_PROFILE", "종류, 키, 버전과 JSON 규격을 확인하세요. 규격은 비어 있지 않은 객체여야 합니다.");
    }
    @ExceptionHandler(ProfileConflictException.class)
    ResponseEntity<ApiErrorResponse> conflict() { return error(409, "PROFILE_CONFLICT", "이미 발행한 버전입니다. 내용을 바꾸려면 새 버전을 등록하세요."); }
    @ExceptionHandler(ProfileNotFoundException.class)
    ResponseEntity<ApiErrorResponse> missing() { return error(404, "PROFILE_NOT_FOUND", "해당 Profile 버전을 찾을 수 없습니다."); }
    @ExceptionHandler(ProfilePayloadTooLargeException.class)
    ResponseEntity<ApiErrorResponse> tooLarge() { return error(413, "PAYLOAD_TOO_LARGE", "JSON 규격은 64 KiB 이하로 입력하세요."); }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> unavailable() { return error(503, "PROFILE_STORE_UNAVAILABLE", "Profile 저장소에 연결할 수 없습니다. 잠시 후 다시 시도하세요."); }
    private ResponseEntity<ApiErrorResponse> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
