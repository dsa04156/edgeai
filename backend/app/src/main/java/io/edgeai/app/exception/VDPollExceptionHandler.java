package io.edgeai.app.exception;
import io.edgeai.app.controller.VDPollController;
import io.edgeai.app.dto.ApiErrorResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=VDPollController.class)
public class VDPollExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class) ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return error(e.status(),e.code());}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class}) ResponseEntity<ApiErrorResponse> invalid(){return error(400,"INVALID_VD_POLL");}
    @ExceptionHandler(ProfilePayloadTooLargeException.class) ResponseEntity<ApiErrorResponse> large(){return error(413,"PAYLOAD_TOO_LARGE");}
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class}) ResponseEntity<ApiErrorResponse> unavailable(){return error(503,"VD_UNAVAILABLE");}
    private ResponseEntity<ApiErrorResponse> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(code,"VD 실행 신원·session·순번을 확인하세요."));}
}
