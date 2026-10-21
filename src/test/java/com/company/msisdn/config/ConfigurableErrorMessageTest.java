package com.company.msisdn.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.msisdn.exception.InvalidMsisdnException;
import com.company.msisdn.service.MsisdnValidator;
import com.company.msisdn.support.ConfigFixtures;
import com.company.msisdn.util.MessageTemplates;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ConfigurableErrorMessageTest {

    @Test
    void statusAndValidationTextComeFromConfiguration() {
        ApiProperties.Messages defaults = ConfigFixtures.standardError(ErrorMessageMode.VALIDATION_ONLY).messages();
        ApiProperties api = new ApiProperties(
                "/api/v1/eligibility/check",
                "/api/v1/system/status",
                "test-instance",
                new ApiProperties.Msisdn("^20[0-9]{10}$", 12, 12, "egyptian"),
                new ApiProperties.Nid("^[0-9]{14}$", 14, 14, "14-digit national id"),
                new ApiProperties.Response("OK", "KO", "Blocked: ", " | ", true, true, true, true, true),
                new ApiProperties.Error(
                        ErrorMessageMode.VALIDATION_ONLY,
                        false,
                        new ApiProperties.StatusText("Rejected", "No key", "Wrong type", "Down", "Failed"),
                        new ApiProperties.Messages(
                                "number rejected",
                                defaults.msisdnLength(),
                                defaults.msisdnPattern(),
                                defaults.nidRequired(),
                                defaults.nidLength(),
                                defaults.nidPattern(),
                                defaults.invalidJson(),
                                defaults.validationFailed(),
                                defaults.fieldInvalid(),
                                "key rejected",
                                defaults.unsupportedMediaType(),
                                defaults.internalError(),
                                defaults.internalErrorDetail(),
                                defaults.auditWriterDown(),
                                defaults.diskSpace(),
                                defaults.auditQueueReadiness(),
                                defaults.auditRejected(),
                                defaults.blacklistNotLoaded(),
                                defaults.blacklistStale())));

        assertThatThrownBy(() -> new MsisdnValidator(api).validate(" "))
                .isInstanceOf(InvalidMsisdnException.class)
                .hasMessage("number rejected");
        assertThat(MessageTemplates.apply(defaults.msisdnLength(), "min", "12", "max", "12"))
                .isEqualTo("msisdn length must be between 12 and 12");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/eligibility/check");
        ErrorResponses responses = new ErrorResponses(api, Clock.systemUTC());
        assertThat(responses.body(HttpStatus.BAD_REQUEST, "number rejected", request, false).error()).isEqualTo("Rejected");
        assertThat(responses.body(HttpStatus.BAD_REQUEST, "number rejected", request, false).message()).isEqualTo("number rejected");
        assertThat(responses.body(HttpStatus.BAD_REQUEST, "number rejected", request, false).path()).isNull();
        assertThat(responses.unauthorized(request).message()).isEqualTo("key rejected");
        assertThat(responses.unauthorized(request).error()).isEqualTo("No key");
    }
}
