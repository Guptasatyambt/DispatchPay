package org.myorg.dispatchpay.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void parsesQuotedValuesWithCommasQuotesAndLineBreaks() {
        List<Csv.Record> records = Csv.parse("a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\nlast,,\n");

        assertThat(records).hasSize(3);
        assertThat(records.get(0).cells()).containsExactly("a", "b", "c");
        assertThat(records.get(1).cells()).containsExactly("x, y", "say \"hi\"", "two\nlines");
        assertThat(records.get(2).cells()).containsExactly("last", "", "");
        // A quoted value spanning lines is still one record: numbers match spreadsheet rows.
        assertThat(records.get(2).number()).isEqualTo(3);
    }

    @Test
    void lastRecordNeedsNoLineBreak() {
        assertThat(Csv.parse("a,b\n1,2")).extracting(Csv.Record::cells)
                .containsExactly(List.of("a", "b"), List.of("1", "2"));
        assertThat(Csv.parse("")).isEmpty();
        assertThat(Csv.parse("a\n\n")).extracting(Csv.Record::cells).containsExactly(List.of("a"), List.of(""));
    }

    @Test
    void rejectsMalformedQuoting() {
        assertThatThrownBy(() -> Csv.parse("a\nab\"c\n")).isInstanceOf(Csv.MalformedCsvException.class)
                .hasMessageStartingWith("Row 2 ");
        assertThatThrownBy(() -> Csv.parse("a\n\"open")).isInstanceOf(Csv.MalformedCsvException.class)
                .hasMessageContaining("never closed");
        assertThatThrownBy(() -> Csv.parse("\"a\"b\n")).isInstanceOf(Csv.MalformedCsvException.class)
                .hasMessageContaining("after the closing quote");
    }

    @Test
    void writtenCellsCannotStartAFormula() {
        assertThat(Csv.sanitize("=HYPERLINK(\"http://evil\")")).isEqualTo("'=HYPERLINK(\"http://evil\")");
        assertThat(Csv.sanitize("+91 98765")).isEqualTo("'+91 98765");
        assertThat(Csv.sanitize("-5")).isEqualTo("'-5");
        assertThat(Csv.sanitize("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(Csv.sanitize("\tx")).isEqualTo("'\tx");
        assertThat(Csv.sanitize("Lux Soap")).isEqualTo("Lux Soap");
        assertThat(Csv.sanitize(null)).isEmpty();
    }

    @Test
    void writeQuotesWhereNeededAndRoundTrips() {
        String text = Csv.write(List.of(List.of("row", "name"), List.of("2", "Tata, \"Salt\"\nfine")));

        assertThat(text).startsWith("﻿row,name\r\n2,\"Tata, \"\"Salt\"\"\nfine\"\r\n");
        assertThat(Csv.parse(text.substring(1)).get(1).cells()).containsExactly("2", "Tata, \"Salt\"\nfine");
    }
}
