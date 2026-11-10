package com.company.msisdn.service;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.exception.InvalidMsisdnException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MsisdnValidator {

    private final ApiProperties api;
    private final Pattern pattern;

    public MsisdnValidator(ApiProperties api) {
        this.api = api;
        this.pattern = Pattern.compile(api.msisdn().pattern());
    }

    public void validate(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            throw new InvalidMsisdnException("msisdn is required");
        }
        int length = msisdn.length();
        if (length < api.msisdn().minLength() || length > api.msisdn().maxLength()) {
            throw new InvalidMsisdnException(
                    "msisdn length must be between " + api.msisdn().minLength() + " and " + api.msisdn().maxLength());
        }
        if (!pattern.matcher(msisdn).matches()) {
            throw new InvalidMsisdnException("msisdn does not match the configured pattern");
        }
    }

    public Pattern pattern() {
        return pattern;
    }
}
