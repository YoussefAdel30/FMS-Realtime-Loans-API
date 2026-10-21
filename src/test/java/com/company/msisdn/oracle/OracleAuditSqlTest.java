package com.company.msisdn.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.msisdn.support.ConfigFixtures;
import com.company.msisdn.util.Identifiers;
import com.company.msisdn.util.RetryBackoff;
import org.junit.jupiter.api.Test;

class OracleAuditSqlTest {

    @Test
    void insertIsIdempotentAndTargetsFmsprd6() {
        OracleAuditSql sql = new OracleAuditSql(ConfigFixtures.oracle());
        assertThat(sql.qualifiedTable()).isEqualTo("FMSPRD6.MSISDN_ELIGIBILITY_AUDIT");
        assertThat(sql.insertSql()).contains("INSERT INTO FMSPRD6.MSISDN_ELIGIBILITY_AUDIT");
        assertThat(sql.insertSql()).contains("FROM DUAL");
        assertThat(sql.insertSql()).contains("WHERE NOT EXISTS");
        assertThat(sql.insertSql()).contains("NID");
        assertThat(sql.insertSql()).contains("EXISTING_ROW.REQUEST_ID = ?");
        assertThat(sql.insertSql()).doesNotContain("${");
    }

    @Test
    void rejectsUnsafeIdentifiers() {
        assertThatThrownBy(() -> Identifiers.qualify("FMSPRD6; DROP TABLE X", "MSISDN_ELIGIBILITY_AUDIT"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Identifiers.qualify("FMSPRD6", "AUDIT LOG"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void backoffGrowsAndCaps() {
        assertThat(RetryBackoff.delayMs(1, 500, 60_000, 2.0)).isEqualTo(500);
        assertThat(RetryBackoff.delayMs(2, 500, 60_000, 2.0)).isEqualTo(1000);
        assertThat(RetryBackoff.delayMs(3, 500, 60_000, 2.0)).isEqualTo(2000);
        assertThat(RetryBackoff.delayMs(20, 500, 60_000, 2.0)).isEqualTo(60_000);
    }
}
