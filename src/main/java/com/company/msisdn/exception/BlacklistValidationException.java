package com.company.msisdn.exception;

public class BlacklistValidationException extends RuntimeException {

    public BlacklistValidationException(String message) {
        super(message);
    }
}
