package com.company.msisdn.util;

import java.util.regex.Pattern;

public final class Identifiers {

    private static final Pattern ORACLE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");

    private Identifiers() {
    }

    public static void requireOracleIdentifier(String value, String setting) {
        if (value == null || !ORACLE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException(setting + " must be a single Oracle identifier (letters, digits, _, $, #)");
        }
    }

    public static String qualify(String schema, String table) {
        requireOracleIdentifier(schema, "oracle-audit.schema");
        requireOracleIdentifier(table, "oracle-audit.table");
        return schema + "." + table;
    }
}
