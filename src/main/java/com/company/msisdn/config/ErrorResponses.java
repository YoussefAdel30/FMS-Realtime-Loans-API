package com.company.msisdn.config;

import com.company.msisdn.dto.ErrorResponse;
import com.company.msisdn.security.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ErrorResponses {

    private final ApiProperties api;
    private final Clock clock;

    public ErrorResponses(ApiProperties api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    public ErrorResponse unauthorized(HttpServletRequest request) {
        return body(HttpStatus.UNAUTHORIZED, api.error().messages().unauthorized(), request, false);
    }

    public ErrorResponse body(HttpStatus status, String detail, HttpServletRequest request, boolean alwaysGeneric) {
        ErrorMessageMode mode = api.error().includeMessage();
        boolean includeDetail = switch (mode) {
            case NEVER -> false;
            case ALWAYS -> true;
            case VALIDATION_ONLY -> status == HttpStatus.BAD_REQUEST
                    || status == HttpStatus.SERVICE_UNAVAILABLE
                    || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE
                    || status == HttpStatus.UNAUTHORIZED;
        };
        if (status == HttpStatus.UNAUTHORIZED) {
            includeDetail = true;
        }
        if (alwaysGeneric && mode != ErrorMessageMode.ALWAYS) {
            includeDetail = false;
        }
        String visible = includeDetail ? detail : hiddenMessage(status);
        return new ErrorResponse(
                RequestIds.get(request),
                clock.instant(),
                status.value(),
                statusText(status),
                visible,
                api.error().includePath() ? request.getRequestURI() : null);
    }

    public String statusText(HttpStatus status) {
        ApiProperties.StatusText text = api.error().statusText();
        return switch (status) {
            case BAD_REQUEST -> text.badRequest();
            case UNAUTHORIZED -> text.unauthorized();
            case UNSUPPORTED_MEDIA_TYPE -> text.unsupportedMediaType();
            case SERVICE_UNAVAILABLE -> text.serviceUnavailable();
            case INTERNAL_SERVER_ERROR -> text.internalServerError();
            default -> status.getReasonPhrase();
        };
    }

    private String hiddenMessage(HttpStatus status) {
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return api.error().messages().internalError();
        }
        return statusText(status);
    }
}
