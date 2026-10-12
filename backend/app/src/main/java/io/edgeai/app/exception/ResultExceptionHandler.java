package io.edgeai.app.exception;
import io.edgeai.app.controller.ResultController;
import io.edgeai.app.dto.ApiErrorResponse;
import io.edgeai.domain.storage.ArtifactStoreUnavailableException;
import io.edgeai.domain.storage.ArtifactVerificationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=ResultController.class)
public class ResultExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class)
    ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return error(e.status(),e.code(),e.getMessage());}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> invalid(){return error(400,"INVALID_TASK_ID","Task UUID 형식을 확인하세요.");}
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class,ArtifactStoreUnavailableException.class})
    ResponseEntity<ApiErrorResponse> unavailable(){return error(503,"RESULT_STORE_UNAVAILABLE","결과 저장소에 연결할 수 없습니다. 복구 후 다시 시도하세요.");}
    @ExceptionHandler(ArtifactVerificationException.class)
    ResponseEntity<ApiErrorResponse> verification(){return error(503,"RESULT_VERIFICATION_FAILED","결과 파일 검증에 실패했습니다. 저장소를 확인한 뒤 다시 조회하세요.");}
    private ResponseEntity<ApiErrorResponse> error(int status,String code,String message){return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(new ApiErrorResponse(code,message));}
}
