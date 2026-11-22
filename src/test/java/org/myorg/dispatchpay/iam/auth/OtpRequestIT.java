package org.myorg.dispatchpay.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;

/** M01.S01 gate: abuse limits and OTP secrecy hold; responses never reveal whether a user exists. */
class OtpRequestIT extends IntegrationTest {

    private static final String MOBILE = "+919876543210";

    private AuthTestClient auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestClient(mockMvc, otpSender);
    }

    @Test
    void acceptsWithGenericResponseAndSendsCode() throws Exception {
        String body = auth.requestOtp("9876543210")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If the number can receive SMS, a code has been sent"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.resendAfterSeconds").value(30))
                .andReturn().getResponse().getContentAsString();

        String code = otpSender.lastCode(MOBILE);
        assertThat(code).matches("\\d{6}");
        assertThat(body).doesNotContain(code);
    }

    @Test
    void storesOnlyAKeyedHashOfTheCode() throws Exception {
        auth.requestOtp(MOBILE).andExpect(status().isAccepted());
        String code = otpSender.lastCode(MOBILE);

        String stored = jdbc.sql("SELECT code_hash FROM otp_challenge WHERE mobile = :m")
                .param("m", MOBILE).query(String.class).single();
        assertThat(stored).hasSize(64).doesNotContain(code);
    }

    @Test
    void responseIsIdenticalForRegisteredAndUnknownNumbers() throws Exception {
        auth.signIn(MOBILE);
        assertThat(count("user_account")).isEqualTo(1);

        String registered = auth.requestOtp(MOBILE).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String unknown = auth.requestOtp("+919123456789").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(registered);
        assertThat(count("user_account")).as("requesting a code never creates an account").isEqualTo(1);
    }

    @Test
    void rejectsMalformedMobileWithoutSending() throws Exception {
        auth.requestOtp("5876543210")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(otpSender.sends()).isZero();
    }

    @Test
    void resendWithinCooldownIsThrottledThenSupersedesPreviousCode() throws Exception {
        auth.requestOtp(MOBILE).andExpect(status().isAccepted());
        String firstCode = otpSender.lastCode(MOBILE);

        auth.requestOtp(MOBILE)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        backdateChallenges(31);
        auth.requestOtp(MOBILE).andExpect(status().isAccepted());

        assertThat(jdbc.sql("SELECT count(*) FROM otp_challenge WHERE mobile = :m AND status = 'ACTIVE'")
                .param("m", MOBILE).query(Long.class).single()).isEqualTo(1);
        auth.verify(MOBILE, firstCode).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsRequestsPerMobile() throws Exception {
        for (int i = 0; i < 3; i++) {
            auth.requestOtp(MOBILE).andExpect(status().isAccepted());
            backdateChallenges(31);
        }

        auth.requestOtp(MOBILE).andExpect(status().isTooManyRequests());
    }

    @Test
    void limitsRequestsPerClientIpAcrossNumbers() throws Exception {
        for (int i = 0; i < 10; i++) {
            auth.requestOtp("+91900000000" + i, "198.51.100.7").andExpect(status().isAccepted());
        }

        auth.requestOtp("+919111111111", "198.51.100.7").andExpect(status().isTooManyRequests());
        auth.requestOtp("+919111111111", "198.51.100.8").andExpect(status().isAccepted());
    }

    @Test
    void providerFailureStillReturnsTheGenericResponse() throws Exception {
        otpSender.failing(true);

        auth.requestOtp(MOBILE)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If the number can receive SMS, a code has been sent"));
    }

    private void backdateChallenges(int seconds) {
        jdbc.sql("UPDATE otp_challenge SET created_at = created_at - make_interval(secs => :s)")
                .param("s", seconds).update();
    }
}
