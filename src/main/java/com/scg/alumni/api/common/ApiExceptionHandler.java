package com.scg.alumni.api.common;

import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final MultipartProperties multipartProperties;

    public ApiExceptionHandler(MultipartProperties multipartProperties) {
        this.multipartProperties = multipartProperties;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("AA0001", "입력값을 확인해주세요."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException exception
    ) {
        DataSize maxFileSize = multipartProperties.getMaxFileSize();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(
                        "AA0001",
                        "이미지는 " + maxFileSize.toMegabytes()
                                + "MB 이하만 업로드할 수 있습니다."
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("AA0002", exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        int statusCode = exception.getStatusCode().value();
        String reason = exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason();
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(new ApiErrorResponse("AA" + statusCode, reason));
    }

    /**
     * 스프링이 컨트롤러에 들여보내지 못한 요청(틀린 Content-Type, 빠진 파라미터,
     * 없는 경로 등)도 이 어드바이스로 온다. 클라이언트 잘못이니 아래의 catch-all이
     * 500으로 뭉개기 전에 원래 상태 코드로 돌려준다.
     */
    @ExceptionHandler({
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            HttpRequestMethodNotSupportedException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MultipartException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleClientError(Exception exception) {
        HttpStatusCode status = exception instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode()
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse("AA" + status.value(), "요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("AA9999", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }
}
