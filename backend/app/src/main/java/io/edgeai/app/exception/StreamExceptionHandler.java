package io.edgeai.app.exception;
import io.edgeai.app.controller.DeviceStreamController;
import io.edgeai.app.dto.ApiErrorResponse;
import io.edgeai.domain.stream.StreamBrokerException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=DeviceStreamController.class)
public class StreamExceptionHandler {
    @ExceptionHandler(ControlPlaneException.class) ResponseEntity<ApiErrorResponse> controlled(ControlPlaneException e){return error(e.status(),e.code());}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<ApiErrorResponse> invalid(){return error(400,"INVALID_STREAM_REQUEST");}
    @ExceptionHandler(ProfilePayloadTooLargeException.class) ResponseEntity<ApiErrorResponse> large(){return error(413,"PAYLOAD_TOO_LARGE");}
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class,StreamBrokerException.class}) ResponseEntity<ApiErrorResponse> unavailable(){return error(503,"STREAM_UNAVAILABLE");}
    private ResponseEntity<ApiErrorResponse> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(code,"현재 장치·실행 주체와 스트림 배정 권한을 확인하세요."));}
}
