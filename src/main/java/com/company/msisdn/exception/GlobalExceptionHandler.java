package com.company.msisdn.exception;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.ErrorMessageMode;
import com.company.msisdn.dto.ErrorResponse;
import com.company.msisdn.security.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiProperties api;
    private final Clock clock;

    public GlobalExceptionHandler(ApiProperties api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @ExceptionHandler(InvalidMsisdnException.class)
    public ResponseEntity<ErrorResponse> invalidMsisdn(InvalidMsisdnException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request, false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request validation failed");
        return error(HttpStatus.BAD_REQUEST, message, request, false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "request body is not valid JSON", request, false);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", request, false);
    }

    @ExceptionHandler({NotReadyException.class, AuditRejectedException.class})
    public ResponseEntity<ErrorResponse> unavailable(RuntimeException ex, HttpServletRequest request) {
        log.warn("requestId={} eligibility unavailable: {}", RequestIds.get(request), ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request, false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        log.error("requestId={} unexpected failure", RequestIds.get(request), ex);
        String message = api.error().includeMessage() == ErrorMessageMode.ALWAYS
                ? ex.getClass().getSimpleName()
                : "An unexpected error occurred";
        return error(HttpStatus.INTERNAL_SERVER_ERROR, message, request, true);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request, boolean alwaysGeneric) {
        ErrorMessageMode mode = api.error().includeMessage();
        boolean includeDetail = switch (mode) {
            case NEVER -> false;
            case ALWAYS -> true;
            case VALIDATION_ONLY -> status == HttpStatus.BAD_REQUEST
                    || status == HttpStatus.SERVICE_UNAVAILABLE
                    || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        };
        if (alwaysGeneric && mode != ErrorMessageMode.ALWAYS) {
            includeDetail = false;
        }
        String visible = includeDetail ? message : (status == HttpStatus.INTERNAL_SERVER_ERROR
                ? "An unexpected error occurred"
                : status.getReasonPhrase());
        ErrorResponse body = new ErrorResponse(
                RequestIds.get(request),
                clock.instant(),
                status.value(),
                status.getReasonPhrase(),
                visible,
                api.error().includePath() ? request.getRequestURI() : null);
        return ResponseEntity.status(status).body(body);
    }
}
