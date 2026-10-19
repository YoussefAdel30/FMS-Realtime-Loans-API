package com.company.msisdn.service;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.exception.InvalidNationalIdException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class NationalIdValidator {

    private final ApiProperties api;
    private final Pattern pattern;

    public NationalIdValidator(ApiProperties api) {
        this.api = api;
        this.pattern = Pattern.compile(api.nid().pattern());
    }

    public void validate(String nid) {
        if (nid == null || nid.isBlank()) {
            throw new InvalidNationalIdException("nid is required");
        }
        int length = nid.length();
        if (length < api.nid().minLength() || length > api.nid().maxLength()) {
            throw new InvalidNationalIdException(
                    "nid length must be between " + api.nid().minLength() + " and " + api.nid().maxLength());
        }
        if (!pattern.matcher(nid).matches()) {
            throw new InvalidNationalIdException("nid does not match the configured pattern");
        }
    }
}
