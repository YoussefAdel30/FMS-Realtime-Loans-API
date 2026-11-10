package com.company.msisdn.blacklist;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.BlacklistProperties;
import com.company.msisdn.exception.BlacklistValidationException;
import com.company.msisdn.exception.FatalBlacklistException;
import com.company.msisdn.service.MsisdnValidator;
import com.company.msisdn.util.MsisdnMasker;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CsvBlacklistLoader {

    private final BlacklistProperties properties;
    private final MsisdnValidator msisdnValidator;
    private final Clock clock;

    public CsvBlacklistLoader(BlacklistProperties properties, MsisdnValidator msisdnValidator, Clock clock) {
        this.properties = properties;
        this.msisdnValidator = msisdnValidator;
        this.clock = clock;
    }

    public BlacklistSnapshot load(Path csvFile, String version, long versionNumber, Instant publishedAt, String sha256)
            throws IOException {
        Charset charset = Charset.forName(properties.encoding());
        CsvParser parser = new CsvParser(parserSettings());
        Long2LongOpenHashMap map = new Long2LongOpenHashMap(
                properties.memory().initialCapacity(),
                properties.memory().loadFactor());
        map.defaultReturnValue(0L);

        long rowsRead = 0;
        long invalidRows = 0;
        List<SourceDefinition> sources;

        try (Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(csvFile), charset), 1 << 20)) {
            parser.beginParsing(reader);
            String[] headers = parser.parseNext();
            sources = validateHeaders(headers);
            String[] row;
            while ((row = parser.parseNext()) != null) {
                long line = parser.getContext().currentLine();
                try {
                    acceptRow(map, sources, row);
                    rowsRead++;
                } catch (FatalBlacklistException ex) {
                    throw new BlacklistValidationException("line " + line + ": " + ex.getMessage());
                } catch (BlacklistValidationException ex) {
                    invalidRows++;
                    if (invalidRows > properties.validation().maxInvalidRows()) {
                        throw new BlacklistValidationException(
                                "line " + line + ": " + ex.getMessage()
                                        + " (invalid rows exceed blacklist.validation.max-invalid-rows="
                                        + properties.validation().maxInvalidRows() + ")");
                    }
                }
                if (map.size() > properties.validation().maxRecordCount()) {
                    throw new BlacklistValidationException(
                            "unique MSISDN count exceeds blacklist.validation.max-record-count="
                                    + properties.validation().maxRecordCount());
                }
            }
        } finally {
            parser.stopParsing();
        }

        if (map.size() < properties.validation().minRecordCount()) {
            throw new BlacklistValidationException(
                    "unique MSISDN count " + map.size() + " is below blacklist.validation.min-record-count="
                            + properties.validation().minRecordCount());
        }

        map.trim();
        long bytes = Files.size(csvFile);
        return new BlacklistSnapshot(
                map,
                sources,
                version,
                versionNumber,
                clock.instant(),
                publishedAt,
                rowsRead,
                csvFile.getFileName().toString(),
                sha256,
                bytes);
    }

    private void acceptRow(Long2LongOpenHashMap map, List<SourceDefinition> sources, String[] row) {
        if (row.length != sources.size() + 1) {
            throw new BlacklistValidationException(
                    "expected " + (sources.size() + 1) + " columns but found " + row.length);
        }
        String msisdnText = row[0] == null ? "" : row[0].trim();
        if (properties.validation().applyApiMsisdnRules()) {
            try {
                msisdnValidator.validate(msisdnText);
            } catch (RuntimeException ex) {
                throw new BlacklistValidationException(ex.getMessage());
            }
        } else if (!msisdnText.chars().allMatch(Character::isDigit) || msisdnText.length() > 18 || msisdnText.isEmpty()) {
            throw new BlacklistValidationException("MSISDN must be 1 to 18 digits");
        }
        long msisdn = Long.parseLong(msisdnText);
        long mask = 0L;
        for (int index = 0; index < sources.size(); index++) {
            String flag = row[index + 1] == null ? "" : row[index + 1].trim();
            if ("1".equals(flag)) {
                mask = BitmaskCodec.setBit(mask, index);
            } else if (!"0".equals(flag)) {
                throw new BlacklistValidationException(
                        "flag for " + sources.get(index).columnName() + " must be 0 or 1");
            }
        }
        applyDuplicatePolicy(map, msisdn, mask);
    }

    private void applyDuplicatePolicy(Long2LongOpenHashMap map, long msisdn, long mask) {
        boolean present = map.containsKey(msisdn);
        switch (properties.duplicatePolicy()) {
            case MERGE_OR -> map.put(msisdn, (present ? map.get(msisdn) : 0L) | mask);
            case FIRST_WINS -> {
                if (!present) {
                    map.put(msisdn, mask);
                }
            }
            case LAST_WINS -> map.put(msisdn, mask);
            case REJECT -> {
                if (present) {
                    throw new FatalBlacklistException(
                            "duplicate MSISDN " + MsisdnMasker.mask(Long.toString(msisdn))
                                    + " and duplicate-policy is reject");
                }
                map.put(msisdn, mask);
            }
            default -> throw new IllegalStateException("Unsupported duplicate policy " + properties.duplicatePolicy());
        }
    }

    private List<SourceDefinition> validateHeaders(String[] headers) {
        if (headers == null || headers.length == 0) {
            throw new BlacklistValidationException("CSV header row is missing");
        }
        String msisdnHeader = headers[0] == null ? "" : headers[0].trim();
        if (msisdnHeader.startsWith("\uFEFF")) {
            msisdnHeader = msisdnHeader.substring(1);
        }
        if (!columnEquals(msisdnHeader, properties.msisdnColumn())) {
            throw new BlacklistValidationException(
                    "first column must be " + properties.msisdnColumn() + " but was " + msisdnHeader);
        }
        if (headers.length - 1 > BitmaskCodec.MAX_SOURCES) {
            throw new BlacklistValidationException(
                    "CSV declares " + (headers.length - 1) + " sources; the maximum is " + BitmaskCodec.MAX_SOURCES);
        }
        if (headers.length == 1) {
            throw new BlacklistValidationException("CSV must declare at least one blacklist source column");
        }
        Set<String> seen = new HashSet<>();
        List<SourceDefinition> sources = new ArrayList<>();
        for (int index = 1; index < headers.length; index++) {
            String column = headers[index] == null ? "" : headers[index].trim();
            if (column.isBlank()) {
                throw new BlacklistValidationException("source column " + index + " is blank");
            }
            String key = properties.sources().headerCaseSensitive() ? column : column.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                throw new BlacklistValidationException("duplicate source column " + column);
            }
            String display = displayName(column);
            if (display == null) {
                throw new BlacklistValidationException(
                        "source column " + column + " has no entry in blacklist.sources.display-names");
            }
            sources.add(new SourceDefinition(index - 1, column, display));
        }
        return List.copyOf(sources);
    }

    private String displayName(String column) {
        for (Map.Entry<String, String> entry : properties.sources().displayNames().entrySet()) {
            if (columnEquals(entry.getKey(), column) && entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        if (properties.validation().requireKnownHeaders()) {
            return null;
        }
        String readable = column.replace('_', ' ');
        return properties.sources().unknownDisplayPattern().replace("{name}", readable);
    }

    private boolean columnEquals(String left, String right) {
        if (properties.sources().headerCaseSensitive()) {
            return left.equals(right);
        }
        return left.equalsIgnoreCase(right);
    }

    private CsvParserSettings parserSettings() {
        CsvParserSettings settings = new CsvParserSettings();
        settings.setHeaderExtractionEnabled(false);
        settings.setLineSeparatorDetectionEnabled(true);
        settings.setSkipEmptyLines(true);
        settings.setMaxCharsPerColumn(256);
        settings.setMaxColumns(BitmaskCodec.MAX_SOURCES + 2);
        settings.setReadInputOnSeparateThread(false);
        settings.getFormat().setDelimiter(properties.delimiter().charAt(0));
        settings.getFormat().setQuote('"');
        settings.getFormat().setQuoteEscape('"');
        settings.setIgnoreLeadingWhitespaces(false);
        settings.setIgnoreTrailingWhitespaces(false);
        return settings;
    }

    public static Pattern compileFilename(String filenamePattern) {
        return Pattern.compile(filenamePattern);
    }
}
