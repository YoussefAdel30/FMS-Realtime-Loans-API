package com.company.msisdn.exception;

public class FatalBlacklistException extends BlacklistValidationException {

    public FatalBlacklistException(String message) {
        super(message);
    }
}
