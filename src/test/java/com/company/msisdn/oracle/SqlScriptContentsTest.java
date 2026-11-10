package com.company.msisdn.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SqlScriptContentsTest {

    @Test
    void installationScriptsTargetFmsprd6AndTheAuditColumns() throws Exception {
        String tables = Files.readString(Path.of("sql/01_create_tables.sql"));
        assertThat(tables).contains("DEFINE schema_owner = FMSPRD6");
        assertThat(tables).contains("MSISDN_ELIGIBILITY_AUDIT");
        for (String column : new String[] {
                "REQUEST_ID", "REQUEST_TIMESTAMP", "RESPONSE_TIMESTAMP", "MSISDN", "RESULT",
                "REASON_MASK", "REASONS", "BLACKLIST_VERSION", "PROCESSING_TIME_MS",
                "HTTP_STATUS", "API_INSTANCE"
        }) {
            assertThat(tables).contains(column);
        }
        assertThat(tables).contains("VARCHAR2(32 CHAR)");
        assertThat(tables).contains("VARCHAR2(16 CHAR)");
        assertThat(Files.readString(Path.of("sql/02_create_indexes.sql"))).contains("IDX_MSISDN_ELIG_MSISDN_TS");
        assertThat(Files.readString(Path.of("sql/03_create_sequences.sql"))).contains("No sequence is required");
        String grants = Files.readString(Path.of("sql/04_create_grants.sql"));
        assertThat(grants).contains("MSISDN_API");
        assertThat(grants).contains("GRANT INSERT, SELECT");
        assertThat(grants).doesNotContain("GRANT DELETE");
        assertThat(Files.readString(Path.of("sql/05_verify_installation.sql"))).contains("VERIFICATION OK");
        assertThat(Files.readString(Path.of("sql/07_maintenance.sql"))).contains("WARNING");
    }
}
