package com.company.msisdn.oracle;

import com.company.msisdn.config.OracleAuditProperties;
import com.company.msisdn.util.Identifiers;

public final class OracleAuditSql {

    private final String insertSql;
    private final String qualifiedTable;

    public OracleAuditSql(OracleAuditProperties properties) {
        this.qualifiedTable = Identifiers.qualify(properties.schema(), properties.table());
        this.insertSql = """
                INSERT INTO %s (
                    REQUEST_ID, REQUEST_TIMESTAMP, RESPONSE_TIMESTAMP, MSISDN, RESULT,
                    REASON_MASK, REASONS, BLACKLIST_VERSION, PROCESSING_TIME_MS, HTTP_STATUS, API_INSTANCE
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM DUAL
                WHERE NOT EXISTS (
                    SELECT 1 FROM %s EXISTING_ROW WHERE EXISTING_ROW.REQUEST_ID = ?
                )
                """.formatted(qualifiedTable, qualifiedTable);
    }

    public String insertSql() {
        return insertSql;
    }

    public String qualifiedTable() {
        return qualifiedTable;
    }
}
