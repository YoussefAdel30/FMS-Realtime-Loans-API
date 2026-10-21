package com.company.msisdn.util;

public final class MessageTemplates {

    private MessageTemplates() {
    }

    public static String apply(String template, String... tokens) {
        if (template == null) {
            return "";
        }
        if (tokens.length % 2 != 0) {
            throw new IllegalArgumentException("message tokens must be name/value pairs");
        }
        String result = template;
        for (int index = 0; index < tokens.length; index += 2) {
            String value = tokens[index + 1] == null ? "" : tokens[index + 1];
            result = result.replace("{" + tokens[index] + "}", value);
        }
        return result;
    }
}
