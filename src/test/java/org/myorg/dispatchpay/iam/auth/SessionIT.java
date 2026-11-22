package org.myorg.dispatchpay.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.iam.auth.session.SessionService;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;

/** M01.S02 gate: expired or reused credentials fail; refresh rotation is atomic. */
class SessionIT extends IntegrationTest {

    private static final String MOBILE = "+919876543210";

    @Autowired
    SessionService sessionService;

    @Autowired
    JwtEncoder jwtEncoder;

    private AuthTestClient auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestClient(mockMvc, otpSender);
    }

    // --- OTP verification ---------------------------------------------------------------------

    @Test
    void verifyCreatesUserAndIssuesUsableTokens() throws Exception {
        auth.requestOtp(MOBILE);
        JsonNode tokens = auth.read(auth.verify(MOBILE, otpSender.lastCode(MOBILE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer")));

        assertThat(count("user_account")).isEqualTo(1);
        assertThat(count("auth_session")).isEqualTo(1);
        mockMvc.perform(get("/v1/test/whoami").header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(tokens.get("userId").asString()))
                .andExpect(jsonPath("$.sessionId").value(tokens.get("sessionId").asString()));
    }

    @Test
    void repeatSignInReusesTheIdentityWithANewSession() throws Exception {
        JsonNode first = auth.signIn(MOBILE);
        JsonNode second = auth.signIn(MOBILE);

        assertThat(second.get("userId")).isEqualTo(first.get("userId"));
        assertThat(second.get("sessionId")).isNotEqualTo(first.get("sessionId"));
        assertThat(count("user_account")).isEqualTo(1);
    }

    @Test
    void wrongCodeIsRejectedAndChallengeLocksAfterMaxAttempts() throws Exception {
        auth.requestOtp(MOBILE);
        String code = otpSender.lastCode(MOBILE);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            auth.verify(MOBILE, wrong)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP"));
        }

        auth.verify(MOBILE, code).andExpect(status().isUnauthorized());
        assertThat(count("user_account")).isZero();
        assertThat(jdbc.sql("SELECT status FROM otp_challenge").query(String.class).single()).isEqualTo("LOCKED");
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        auth.requestOtp(MOBILE);
        jdbc.sql("UPDATE otp_challenge SET expires_at = now() - interval '1 second'").update();

        auth.verify(MOBILE, otpSender.lastCode(MOBILE)).andExpect(status().isUnauthorized());
    }

    @Test
    void codeIsSingleUse() throws Exception {
        auth.requestOtp(MOBILE);
        String code = otpSender.lastCode(MOBILE);

        auth.verify(MOBILE, code).andExpect(status().isOk());
        auth.verify(MOBILE, code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void numberWithoutChallengeFailsExactlyLikeAWrongCode() throws Exception {
        auth.requestOtp(MOBILE);
        JsonNode wrongCode = auth.read(auth.verify(MOBILE, "000001"));
        JsonNode noChallenge = auth.read(auth.verify("+919123456789", "000001"));

        assertThat(noChallenge.get("code")).isEqualTo(wrongCode.get("code"));
        assertThat(noChallenge.get("detail")).isEqualTo(wrongCode.get("detail"));
    }

    @Test
    void disabledAccountCannotSignIn() throws Exception {
        auth.signIn(MOBILE);
        jdbc.sql("UPDATE user_account SET status = 'DISABLED'").update();

        auth.requestOtp(MOBILE);
        auth.verify(MOBILE, otpSender.lastCode(MOBILE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    // --- Access tokens ------------------------------------------------------------------------

    @Test
    void protectedEndpointRejectsMissingTamperedAndExpiredTokens() throws Exception {
        JsonNode tokens = auth.signIn(MOBILE);
        String accessToken = tokens.get("accessToken").asString();

        mockMvc.perform(get("/v1/test/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/v1/test/whoami").header("Authorization", "Bearer " + accessToken + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/v1/test/whoami").header("Authorization", "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized());
    }

    // --- Refresh rotation ---------------------------------------------------------------------

    @Test
    void refreshRotatesTokens() throws Exception {
        JsonNode first = auth.signIn(MOBILE);

        JsonNode second = auth.read(auth.refresh(first.get("refreshToken").asString())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));

        assertThat(second.get("refreshToken")).isNotEqualTo(first.get("refreshToken"));
        assertThat(second.get("sessionId")).isEqualTo(first.get("sessionId"));
        mockMvc.perform(get("/v1/test/whoami").header("Authorization", bearer(second)))
                .andExpect(status().isOk());
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeSession() throws Exception {
        JsonNode first = auth.signIn(MOBILE);
        JsonNode second = auth.read(auth.refresh(first.get("refreshToken").asString()));

        auth.refresh(first.get("refreshToken").asString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(jdbc.sql("SELECT revoke_reason FROM auth_session").query(String.class).single())
                .isEqualTo("REFRESH_TOKEN_REUSE");
        auth.refresh(second.get("refreshToken").asString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        JsonNode tokens = auth.signIn(MOBILE);
        jdbc.sql("UPDATE refresh_token SET expires_at = now() - interval '1 second'").update();

        auth.refresh(tokens.get("refreshToken").asString()).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionRejectsRefresh() throws Exception {
        JsonNode tokens = auth.signIn(MOBILE);
        jdbc.sql("UPDATE auth_session SET expires_at = now() - interval '1 second'").update();

        auth.refresh(tokens.get("refreshToken").asString()).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        auth.refresh("not-a-real-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void disabledAccountCannotRefresh() throws Exception {
        JsonNode tokens = auth.signIn(MOBILE);
        jdbc.sql("UPDATE user_account SET status = 'DISABLED'").update();

        auth.refresh(tokens.get("refreshToken").asString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void concurrentRefreshWithTheSameTokenSucceedsAtMostOnce() throws Exception {
        String refreshToken = auth.signIn(MOBILE).get("refreshToken").asString();
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return sessionService.refresh(refreshToken, "192.0.2.10");
                }));
            }
            start.countDown();

            int successes = 0;
            for (Future<?> future : futures) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) e.getCause()).code()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
                }
            }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE used_at IS NULL").query(Long.class).single())
                .as("exactly one successor token was minted").isEqualTo(1);
    }

    private static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asString();
    }

    private String expiredAccessToken() {
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("dispatchpay")
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(past)
                .expiresAt(past.plusSeconds(60))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
