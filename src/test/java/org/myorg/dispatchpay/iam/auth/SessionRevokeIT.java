package org.myorg.dispatchpay.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.myorg.dispatchpay.iam.auth.session.SessionTokens;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** M01.S03 gate: revoked sessions fail subsequent protected requests. */
class SessionRevokeIT extends IntegrationTest {

    private static final String MOBILE = "+919876543210";
    private static final String OTHER_MOBILE = "+919123456789";

    @Autowired
    SessionService sessionService;

    private AuthTestClient auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestClient(mockMvc, otpSender);
    }

    @Test
    void revokingTheCurrentSessionSignsOutOnlyThisDevice() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        JsonNode tablet = auth.signIn(MOBILE);

        auth.revokeSessions(phone, "{\"scope\":\"CURRENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessionIds[0]").value(phone.get("sessionId").asString()))
                .andExpect(jsonPath("$.revokedSessionIds.length()").value(1));

        auth.whoAmI(phone)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        auth.refresh(phone.get("refreshToken").asString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        auth.whoAmI(tablet).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT revoke_reason FROM auth_session WHERE revoked_at IS NOT NULL")
                .query(String.class).single()).isEqualTo("USER_REVOKED");
    }

    @Test
    void revokingAnotherOwnSessionSignsOutThatDevice() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        JsonNode lostTablet = auth.signIn(MOBILE);

        auth.revokeSessions(phone, sessionScope(lostTablet.get("sessionId").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessionIds[0]").value(lostTablet.get("sessionId").asString()));

        auth.whoAmI(lostTablet).andExpect(status().isUnauthorized());
        auth.refresh(lostTablet.get("refreshToken").asString()).andExpect(status().isUnauthorized());
        auth.whoAmI(phone).andExpect(status().isOk());
    }

    @Test
    void revokingAllSessionsSignsOutEveryDeviceIncludingTheCaller() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        JsonNode tablet = auth.signIn(MOBILE);
        JsonNode otherUser = auth.signIn(OTHER_MOBILE);

        auth.revokeSessions(phone, "{\"scope\":\"ALL\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessionIds.length()").value(2));

        auth.whoAmI(phone).andExpect(status().isUnauthorized());
        auth.whoAmI(tablet).andExpect(status().isUnauthorized());
        auth.refresh(tablet.get("refreshToken").asString()).andExpect(status().isUnauthorized());
        auth.whoAmI(otherUser).andExpect(status().isOk());
    }

    @Test
    void signingInAgainAfterRevocationWorks() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        auth.revokeSessions(phone, "{\"scope\":\"ALL\"}").andExpect(status().isOk());

        auth.whoAmI(auth.signIn(MOBILE)).andExpect(status().isOk());
    }

    @Test
    void anotherUsersSessionIsNotFoundAndStaysActive() throws Exception {
        JsonNode attacker = auth.signIn(MOBILE);
        JsonNode victim = auth.signIn(OTHER_MOBILE);

        auth.revokeSessions(attacker, sessionScope(victim.get("sessionId").asString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        auth.whoAmI(victim).andExpect(status().isOk());
    }

    @Test
    void unknownSessionIsNotFoundLikeAnotherUsersSession() throws Exception {
        JsonNode caller = auth.signIn(MOBILE);
        JsonNode victim = auth.signIn(OTHER_MOBILE);

        JsonNode unknown = auth.read(auth.revokeSessions(caller, sessionScope(UUID.randomUUID().toString())));
        JsonNode foreign = auth.read(auth.revokeSessions(caller, sessionScope(victim.get("sessionId").asString())));

        assertThat(unknown.get("code")).isEqualTo(foreign.get("code"));
        assertThat(unknown.get("detail")).isEqualTo(foreign.get("detail"));
    }

    @Test
    void revokingAnAlreadyRevokedSessionIsANoOp() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        JsonNode tablet = auth.signIn(MOBILE);
        String tabletSession = sessionScope(tablet.get("sessionId").asString());

        auth.revokeSessions(phone, tabletSession).andExpect(status().isOk());
        auth.revokeSessions(phone, tabletSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessionIds.length()").value(0));
        auth.revokeSessions(phone, "{\"scope\":\"ALL\"}")
                .andExpect(jsonPath("$.revokedSessionIds.length()").value(1));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        JsonNode caller = auth.signIn(MOBILE);

        auth.revokeSessions(caller, "{\"scope\":\"SESSION\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        auth.revokeSessions(caller, "{\"scope\":\"CURRENT\",\"sessionId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent());
        auth.revokeSessions(caller, "{}").andExpect(status().isUnprocessableContent());
        auth.revokeSessions(caller, "{\"scope\":\"EVERYTHING\"}").andExpect(status().isBadRequest());

        auth.whoAmI(caller).andExpect(status().isOk());
    }

    @Test
    void revokeRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/v1/sessions/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void revokedAccessTokenCannotRevokeAgain() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        auth.revokeSessions(phone, "{\"scope\":\"CURRENT\"}").andExpect(status().isOk());

        auth.revokeSessions(phone, "{\"scope\":\"ALL\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountsAccessTokensStopWorking() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        jdbc.sql("UPDATE user_account SET status = 'DISABLED'").update();

        auth.whoAmI(phone).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionsAccessTokensStopWorking() throws Exception {
        JsonNode phone = auth.signIn(MOBILE);
        jdbc.sql("UPDATE auth_session SET expires_at = now() - interval '1 second'").update();

        auth.whoAmI(phone).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRacingWithRevokeAllNeverLeavesAUsableSession() throws Exception {
        for (int round = 0; round < 10; round++) {
            jdbc.sql("TRUNCATE rate_limit_bucket, otp_challenge").update();
            JsonNode phone = auth.signIn(MOBILE);
            UUID userId = UUID.fromString(phone.get("userId").asString());
            CountDownLatch start = new CountDownLatch(1);

            Future<SessionTokens> refreshed;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                refreshed = pool.submit(() -> {
                    start.await();
                    return sessionService.refresh(phone.get("refreshToken").asString(), "192.0.2.10");
                });
                pool.submit(() -> {
                    start.await();
                    return sessionService.revokeAll(userId);
                });
                start.countDown();
            }

            SessionTokens tokens;
            try {
                tokens = refreshed.get();
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
                continue;
            }
            mockMvc.perform(get("/v1/test/whoami").header("Authorization", "Bearer " + tokens.accessToken()))
                    .andExpect(status().isUnauthorized());
            auth.refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
        }
    }

    private static String sessionScope(String sessionId) {
        return "{\"scope\":\"SESSION\",\"sessionId\":\"" + sessionId + "\"}";
    }
}
