package com.company.msisdn.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BitmaskCodecTest {

    @Test
    void setsAndReadsEveryBitIncludingTheSignBit() {
        long mask = 0L;
        for (int bit = 0; bit < BitmaskCodec.MAX_SOURCES; bit++) {
            mask = BitmaskCodec.setBit(mask, bit);
            assertThat(BitmaskCodec.isSet(mask, bit)).isTrue();
        }
        assertThat(mask).isEqualTo(-1L);
        assertThat(BitmaskCodec.toHex(mask)).isEqualTo("FFFFFFFFFFFFFFFF");
        assertThat(BitmaskCodec.toUnsignedDecimal(mask)).isEqualTo("18446744073709551615");
        assertThat(BitmaskCodec.fromHex(BitmaskCodec.toHex(mask))).isEqualTo(mask);

        long signOnly = BitmaskCodec.setBit(0L, 63);
        assertThat(signOnly).isEqualTo(Long.MIN_VALUE);
        assertThat(BitmaskCodec.isSet(signOnly, 63)).isTrue();
        assertThat(BitmaskCodec.isSet(signOnly, 0)).isFalse();
        assertThat(BitmaskCodec.toHex(signOnly)).isEqualTo("8000000000000000");
        assertThat(BitmaskCodec.fromHex("8000000000000000")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void decodesDisplayNamesInColumnOrder() {
        List<SourceDefinition> sources = List.of(
                new SourceDefinition(0, "Vishing", "Anti-Fraud (Vishing)"),
                new SourceDefinition(1, "Suspicious_IMEI", "Anti-Fraud (Suspicious IMEI)"),
                new SourceDefinition(2, "Financial_Fraud", "Anti-Fraud (Financial Fraud)")
        );
        long mask = BitmaskCodec.setBit(BitmaskCodec.setBit(0L, 0), 2);
        assertThat(BitmaskCodec.activeDisplayNames(mask, sources))
                .containsExactly("Anti-Fraud (Vishing)", "Anti-Fraud (Financial Fraud)");
        assertThat(BitmaskCodec.formatKoReason(mask, sources))
                .isEqualTo("Anti-fraud : Anti-Fraud (Vishing), Anti-Fraud (Financial Fraud)");
        assertThat(BitmaskCodec.activeDisplayNames(0L, sources)).isEmpty();
    }

    @Test
    void rejectsABitOutsideTheLong() {
        assertThatThrownBy(() -> BitmaskCodec.setBit(0L, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signBitSurvivesARoundTripThroughEveryIndex() {
        List<SourceDefinition> sources = new ArrayList<>();
        long mask = 0L;
        for (int bit = 0; bit < 64; bit++) {
            sources.add(new SourceDefinition(bit, "S" + bit, "Source " + bit));
            if ((bit % 2) == 0) {
                mask = BitmaskCodec.setBit(mask, bit);
            }
        }
        List<String> names = BitmaskCodec.activeDisplayNames(mask, sources);
        assertThat(names).hasSize(32);
        assertThat(names.get(names.size() - 1)).isEqualTo("Source 62");
        long withSign = BitmaskCodec.setBit(mask, 63);
        assertThat(BitmaskCodec.activeDisplayNames(withSign, sources)).contains("Source 63");
    }
}
