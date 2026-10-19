package com.company.msisdn.exception;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.ErrorMessageMode;
import com.company.msisdn.config.ErrorResponses;
import com.company.msisdn.dto.ErrorResponse;
import com.company.msisdn.security.RequestIds;
import com.company.msisdn.util.MessageTemplates;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ErrorResponses errorResponses;

    public GlobalExceptionHandler(ApiProperties api, ErrorResponses errorResponses) {
        this.api = api;
        this.errorResponses = errorResponses;
    }

    @ExceptionHandler({InvalidMsisdnException.class, InvalidNationalIdException.class})
    public ResponseEntity<ErrorResponse> invalidIdentifier(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request, false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> MessageTemplates.apply(
                        api.error().messages().fieldInvalid(),
                        "field", error.getField() == null ? "" : error.getField(),
                        "detail", error.getDefaultMessage()))
                .orElse(api.error().messages().validationFailed());
        return error(HttpStatus.BAD_REQUEST, message, request, false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, api.error().messages().invalidJson(), request, false);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, api.error().messages().unsupportedMediaType(), request, false);
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
                ? MessageTemplates.apply(api.error().messages().internalErrorDetail(), "exception", ex.getClass().getSimpleName())
                : api.error().messages().internalError();
        return error(HttpStatus.INTERNAL_SERVER_ERROR, message, request, true);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request, boolean alwaysGeneric) {
        return ResponseEntity.status(status).body(errorResponses.body(status, message, request, alwaysGeneric));
    }
}
