package io.edgeai.app.exception;
import io.edgeai.app.controller.ResultController;
import io.edgeai.app.dto.ApiErrorResponse;
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
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> unavailable(){return error(503,"RESULT_STORE_UNAVAILABLE","결과 저장소에 연결할 수 없습니다. 복구 후 다시 시도하세요.");}
    private ResponseEntity<ApiErrorResponse> error(int status,String code,String message){return ResponseEntity.status(status).body(new ApiErrorResponse(code,message));}
}
