package com.company.msisdn.service;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.exception.InvalidMsisdnException;
import com.company.msisdn.util.MessageTemplates;
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
            throw new InvalidMsisdnException(api.error().messages().msisdnRequired());
        }
        int length = msisdn.length();
        if (length < api.msisdn().minLength() || length > api.msisdn().maxLength()) {
            throw new InvalidMsisdnException(MessageTemplates.apply(
                    api.error().messages().msisdnLength(),
                    "min", Integer.toString(api.msisdn().minLength()),
                    "max", Integer.toString(api.msisdn().maxLength())));
        }
        if (!pattern.matcher(msisdn).matches()) {
            throw new InvalidMsisdnException(api.error().messages().msisdnPattern());
        }
    }

    public Pattern pattern() {
        return pattern;
    }
}
