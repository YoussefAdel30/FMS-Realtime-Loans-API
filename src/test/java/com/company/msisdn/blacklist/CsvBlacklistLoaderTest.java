package com.company.msisdn.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.msisdn.exception.BlacklistValidationException;
import com.company.msisdn.service.MsisdnValidator;
import com.company.msisdn.service.NationalIdValidator;
import com.company.msisdn.support.ConfigFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvBlacklistLoaderTest {

    @TempDir
    Path temp;

    @Test
    void loadsDynamicSourcesAndMergesDuplicatePairs() throws Exception {
        Path csv = write("""
                MSISDN,NID,Vishing,Suspicious_IMEI,Financial_Fraud
                201066257228,29901011234567,1,0,1
                201066257228,29901011234567,0,1,0
                201066257228,28802021234568,0,1,0
                201012345678,29901011234567,0,1,0
                201098765432,27703031234569,0,0,0
                """);
        BlacklistSnapshot snapshot = loader(DuplicatePolicy.MERGE_OR).load(csv, "20261015", 20261015, Instant.parse("2026-10-15T01:00:00Z"), "abc");
        assertThat(snapshot.recordCount()).isEqualTo(4);
        assertThat(snapshot.sourceCount()).isEqualTo(3);
        long merged = snapshot.lookup(201066257228L, 29901011234567L);
        assertThat(BitmaskCodec.formatKoReason(merged, snapshot.sources()))
                .isEqualTo("Anti-fraud : Anti-Fraud (Vishing), Anti-Fraud (Suspicious IMEI), Anti-Fraud (Financial Fraud)");
        assertThat(snapshot.lookup(201066257228L, 28802021234568L)).isEqualTo(BitmaskCodec.setBit(0L, 1));
        assertThat(snapshot.lookup(201012345678L, 29901011234567L)).isEqualTo(BitmaskCodec.setBit(0L, 1));
        assertThat(snapshot.contains(201098765432L, 27703031234569L)).isTrue();
        assertThat(snapshot.lookup(201098765432L, 27703031234569L)).isZero();
        assertThat(snapshot.lookup(201055555555L, 29901011234567L)).isZero();
        assertThat(snapshot.contains(201055555555L, 29901011234567L)).isFalse();
    }

    @Test
    void rejectsMoreThanSixtyFourSources() throws Exception {
        StringBuilder header = new StringBuilder("MSISDN,NID");
        StringBuilder row = new StringBuilder("201066257228,29901011234567");
        for (int i = 0; i < 65; i++) {
            header.append(",S").append(i);
            row.append(",0");
        }
        Path csv = write(header + "\n" + row + "\n");
        assertThatThrownBy(() -> loader(DuplicatePolicy.MERGE_OR).load(csv, "20261015", 20261015, Instant.now(), "x"))
                .isInstanceOf(BlacklistValidationException.class)
                .hasMessageContaining("maximum is 64");
    }

    @Test
    void keepsTheSignBitForTheSixtyFourthSource() throws Exception {
        StringBuilder header = new StringBuilder("MSISDN,NID");
        StringBuilder row = new StringBuilder("201066257228,29901011234567");
        for (int i = 0; i < 64; i++) {
            header.append(",S").append(i);
            row.append(i == 63 ? ",1" : ",0");
        }
        Path csv = write(header + "\n" + row + "\n");
        BlacklistSnapshot snapshot = loader(DuplicatePolicy.MERGE_OR).load(csv, "20261015", 20261015, Instant.now(), "x");
        assertThat(snapshot.lookup(201066257228L, 29901011234567L)).isEqualTo(Long.MIN_VALUE);
        assertThat(BitmaskCodec.activeDisplayNames(snapshot.lookup(201066257228L, 29901011234567L), snapshot.sources()))
                .containsExactly("Anti-Fraud (S63)");
    }

    @Test
    void rejectsDuplicateColumnsAndInvalidFlags() throws Exception {
        Path duplicateHeader = write("MSISDN,NID,Vishing,Vishing\n201066257228,29901011234567,1,0\n");
        assertThatThrownBy(() -> loader(DuplicatePolicy.MERGE_OR).load(duplicateHeader, "20261015", 20261015, Instant.now(), "x"))
                .isInstanceOf(BlacklistValidationException.class)
                .hasMessageContaining("duplicate source column");

        Path badFlag = write("MSISDN,NID,Vishing\n201066257228,29901011234567,2\n");
        assertThatThrownBy(() -> loader(DuplicatePolicy.MERGE_OR).load(badFlag, "20261015", 20261015, Instant.now(), "x"))
                .isInstanceOf(BlacklistValidationException.class)
                .hasMessageContaining("must be 0 or 1");
    }

    @Test
    void rejectPolicyFailsTheFileOnADuplicateMsisdn() throws Exception {
        Path csv = write("""
                MSISDN,NID,Vishing
                201066257228,29901011234567,1
                201066257228,29901011234567,0
                """);
        assertThatThrownBy(() -> loader(DuplicatePolicy.REJECT).load(csv, "20261015", 20261015, Instant.now(), "x"))
                .isInstanceOf(BlacklistValidationException.class)
                .hasMessageContaining("duplicate MSISDN");
    }

    private CsvBlacklistLoader loader(DuplicatePolicy policy) {
        return new CsvBlacklistLoader(
                ConfigFixtures.blacklist(temp, policy),
                new MsisdnValidator(ConfigFixtures.api()),
                new NationalIdValidator(ConfigFixtures.api()),
                Clock.systemUTC());
    }

    private Path write(String content) throws Exception {
        Path csv = temp.resolve("blacklist-" + System.nanoTime() + ".csv");
        Files.writeString(csv, content);
        return csv;
    }
}
