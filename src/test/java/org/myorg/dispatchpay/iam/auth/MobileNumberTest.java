package org.myorg.dispatchpay.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myorg.dispatchpay.platform.error.ApiException;

class MobileNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "09876543210", "919876543210", "+919876543210", "+91 98765-43210",
            " (987) 654 3210 "})
    void normalizesAcceptedFormatsToE164(String raw) {
        assertThat(MobileNumber.parse(raw).e164()).isEqualTo("+919876543210");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"5876543210", "987654321", "98765432101", "+449876543210", "98765abcde", "+91"})
    void rejectsInvalidNumbers(String raw) {
        assertThatThrownBy(() -> MobileNumber.parse(raw)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9876543210"})
    void masksForLogging(String raw) {
        MobileNumber mobile = MobileNumber.parse(raw);
        assertThat(mobile.masked()).isEqualTo("+91******3210");
        assertThat(mobile.toString()).doesNotContain("98765");
    }
}
