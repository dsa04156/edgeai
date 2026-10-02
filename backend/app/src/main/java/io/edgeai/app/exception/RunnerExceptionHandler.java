package io.edgeai.app.exception;
import io.edgeai.app.controller.RunnerController;
import io.edgeai.app.dto.ApiErrorResponse;
import io.edgeai.domain.storage.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=RunnerController.class)
public class RunnerExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class) ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return error(e.status(),e.code());}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class}) ResponseEntity<ApiErrorResponse> invalid(){return error(400,"INVALID_RUNNER_REQUEST");}
    @ExceptionHandler(ProfilePayloadTooLargeException.class) ResponseEntity<ApiErrorResponse> large(){return error(413,"PAYLOAD_TOO_LARGE");}
    @ExceptionHandler(ArtifactVerificationException.class) ResponseEntity<ApiErrorResponse> artifact(){return error(400,"ARTIFACT_INVALID");}
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class,ArtifactStoreUnavailableException.class})
    ResponseEntity<ApiErrorResponse> unavailable(){return error(503,"RUNTIME_UNAVAILABLE");}
    private ResponseEntity<ApiErrorResponse> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(code,"Runner 요청 상태와 실행 계약을 확인하세요."));}
}
