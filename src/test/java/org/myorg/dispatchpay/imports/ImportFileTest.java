package org.myorg.dispatchpay.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ImportFileTest {

    @Test
    void matchesColumnsIgnoringCaseSpacesAndOrderAndSkipsBlankRows() {
        ImportFile.Parsed parsed = parse("﻿ NAME ,Sku,baseunit\nLux Soap,SOAP-1,PCS\n,,\n\nTata Salt,SALT-1,KG\n");

        assertThat(parsed.columns()).containsExactly("name", "sku", "baseUnit");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(parsed.rows().get(0).values()).containsEntry("sku", "SOAP-1").containsEntry("name", "Lux Soap");
        // Blank rows are skipped but keep their numbers, so errors point at the right row.
        assertThat(parsed.rows().get(1).rowNumber()).isEqualTo(5);
    }

    @Test
    void aRowWithTheWrongNumberOfValuesIsARowError() {
        ImportFile.Parsed parsed = parse("sku,name,baseUnit\nA,Apple\nB,Banana,PCS,extra\nC,Cherry,PCS\n");

        assertThat(parsed.rows().get(0).shapeError().code()).isEqualTo(RowError.COLUMN_COUNT);
        assertThat(parsed.rows().get(0).values()).containsEntry("baseUnit", "");
        assertThat(parsed.rows().get(1).shapeError().code()).isEqualTo(RowError.COLUMN_COUNT);
        assertThat(parsed.rows().get(2).shapeError()).isNull();
    }

    @Test
    void rejectsBadHeaders() {
        assertRejected("sku,name\nA,Apple\n", ImportFile.HEADER_INVALID, "Missing required column(s): [baseUnit]");
        assertRejected("sku,name,baseUnit,colour\nA,Apple,PCS,red\n", ImportFile.HEADER_INVALID,
                "Unknown column \"colour\"");
        assertRejected("sku,name,baseUnit,SKU\nA,Apple,PCS,A\n", ImportFile.HEADER_INVALID, "more than once");
        assertRejected("\n\n", ImportFile.HEADER_INVALID, "first row");
        assertRejected("", ImportFile.HEADER_INVALID, "first row");
    }

    @Test
    void rejectsFilesWithoutRowsOrWithTooManyRows() {
        assertRejected("sku,name,baseUnit\n\n", ImportFile.NO_ROWS, "no rows");
        String threeRows = "sku,name,baseUnit\nA,A,PCS\nB,B,PCS\nC,C,PCS\n";
        assertThat(ImportFile.parse(bytes(threeRows), ImportType.PRODUCTS, 3).rows()).hasSize(3);
        assertThatThrownBy(() -> ImportFile.parse(bytes(threeRows), ImportType.PRODUCTS, 2))
                .isInstanceOfSatisfying(ImportFile.Rejected.class,
                        e -> assertThat(e.code()).isEqualTo(ImportFile.TOO_MANY_ROWS));
    }

    @Test
    void rejectsFilesThatAreNotUtf8CsvText() {
        byte[] latin1 = "sku,name,baseUnit\nA,Café,PCS\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> ImportFile.parse(latin1, ImportType.PRODUCTS, 10))
                .isInstanceOfSatisfying(ImportFile.Rejected.class,
                        e -> assertThat(e.code()).isEqualTo(ImportFile.FILE_ENCODING));
        assertRejected("sku,name,baseUnit\nA,\0,PCS\n", ImportFile.FILE_NOT_CSV, "not a CSV");
        assertRejected("sku,name,baseUnit\n\"A,Apple,PCS\n", ImportFile.FILE_NOT_CSV, "never closed");
    }

    private static ImportFile.Parsed parse(String text) {
        return ImportFile.parse(bytes(text), ImportType.PRODUCTS, 100);
    }

    private static void assertRejected(String text, String code, String message) {
        assertThatThrownBy(() -> parse(text))
                .isInstanceOfSatisfying(ImportFile.Rejected.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
