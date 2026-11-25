package org.myorg.dispatchpay.iam.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * M01.S06 gate: workers cannot invite; duplicate and expired invitations behave deterministically.
 * M01.S07 gate: an invitation cannot be replayed or accepted by another mobile identity.
 */
class InvitationIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919123456789";
    private static final String OTHER_MOBILE = "+919000000001";

    private IamClient iam;
    private JsonNode owner;
    private UUID businessId;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
    }

    // --- M01.S06 invite -----------------------------------------------------------------------

    @Test
    void ownerInvitesAMobileAndOnlyTheInviteeReceivesTheToken() throws Exception {
        JsonNode created = iam.read(iam.invite(owner, businessId, "98765 12345", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessId").value(businessId.toString()))
                .andExpect(jsonPath("$.businessName").value("Sharma Traders"))
                .andExpect(jsonPath("$.mobile").value("+919876512345"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.token").doesNotExist()));

        Instant createdAt = Instant.parse(created.get("createdAt").asString());
        assertThat(Instant.parse(created.get("expiresAt").asString())).isEqualTo(createdAt.plus(Duration.ofDays(7)));
        String token = invitationSender.lastToken("+919876512345");
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
        String stored = jdbc.sql("SELECT token_hash FROM worker_invitation").query(String.class).single();
        assertThat(stored).isEqualTo(InvitationService.sha256(token)).doesNotContain(token);
        assertThat(jdbc.sql("""
                        SELECT response_body::text FROM idempotency_record
                        WHERE operation = 'iam.invitations.create'
                        """).query(String.class).single())
                .doesNotContain(token);
    }

    @Test
    void workerCannotInvite() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        iam.addWorker(owner, businessId, worker, WORKER_MOBILE);

        iam.invite(worker, businessId, OTHER_MOBILE, newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(count("worker_invitation")).isEqualTo(1);
        assertThat(invitationSender.lastToken(OTHER_MOBILE)).isNull();
    }

    @Test
    void outsidersCannotInviteIntoABusiness() throws Exception {
        JsonNode outsider = iam.signIn(OTHER_MOBILE);

        iam.invite(outsider, businessId, WORKER_MOBILE, newKey())
                .andExpect(status().isNotFound());
        assertThat(count("worker_invitation")).isZero();
    }

    @Test
    void reinvitingSupersedesThePendingInvitation() throws Exception {
        iam.invite(owner, businessId, WORKER_MOBILE, newKey()).andExpect(status().isCreated());
        String first = invitationSender.lastToken(WORKER_MOBILE);
        iam.invite(owner, businessId, WORKER_MOBILE, newKey()).andExpect(status().isCreated());
        String second = invitationSender.lastToken(WORKER_MOBILE);
        assertThat(second).isNotEqualTo(first);
        assertThat(pending()).isEqualTo(1);

        JsonNode worker = iam.signIn(WORKER_MOBILE);
        iam.accept(worker, first, newKey())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_SUPERSEDED"));
        iam.accept(worker, second, newKey()).andExpect(status().isCreated());
    }

    @Test
    void retryWithTheSameKeyReplaysWithoutSendingAgain() throws Exception {
        String key = newKey();
        JsonNode first = iam.read(iam.invite(owner, businessId, WORKER_MOBILE, key).andExpect(status().isCreated()));
        JsonNode second = iam.read(iam.invite(owner, businessId, WORKER_MOBILE, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")));

        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(invitationSender.sends()).isEqualTo(1);
        assertThat(count("worker_invitation")).isEqualTo(1);
    }

    @Test
    void sameKeyForAnotherMobileConflicts() throws Exception {
        String key = newKey();
        iam.invite(owner, businessId, WORKER_MOBILE, key).andExpect(status().isCreated());

        iam.invite(owner, businessId, OTHER_MOBILE, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void existingMembersCannotBeInvited() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        iam.addWorker(owner, businessId, worker, WORKER_MOBILE);

        iam.invite(owner, businessId, WORKER_MOBILE, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        iam.invite(owner, businessId, OWNER_MOBILE, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        iam.invite(owner, businessId, "12345", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/v1/iam/invitations")
                        .header("Authorization", bearer(owner))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"" + WORKER_MOBILE + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_SELECTED"));
        assertThat(count("worker_invitation")).isZero();
    }

    @Test
    void deliveryFailureStillRecordsTheInvitation() throws Exception {
        invitationSender.failing(true);

        iam.invite(owner, businessId, WORKER_MOBILE, newKey()).andExpect(status().isCreated());
        assertThat(pending()).isEqualTo(1);

        // The owner resends by inviting again.
        invitationSender.failing(false);
        iam.invite(owner, businessId, WORKER_MOBILE, newKey()).andExpect(status().isCreated());
        assertThat(invitationSender.lastToken(WORKER_MOBILE)).isNotNull();
    }

    @Test
    void concurrentInvitesForOneMobileLeaveOnePendingInvitation() throws Exception {
        List<ResultActions> results = concurrently(6, () -> iam.invite(owner, businessId, WORKER_MOBILE, newKey()));

        for (ResultActions result : results) {
            assertThat(result.andReturn().getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(count("worker_invitation")).isEqualTo(6);
        assertThat(pending()).isEqualTo(1);
    }

    // --- M01.S07 accept -----------------------------------------------------------------------

    @Test
    void inviteeAcceptsAndBecomesAWorkerWithNoPermissions() throws Exception {
        iam.invite(owner, businessId, WORKER_MOBILE, newKey());
        JsonNode worker = iam.signIn(WORKER_MOBILE);

        JsonNode accepted = iam.read(iam.accept(worker, invitationSender.lastToken(WORKER_MOBILE), newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessId").value(businessId.toString()))
                .andExpect(jsonPath("$.businessName").value("Sharma Traders"))
                .andExpect(jsonPath("$.role").value("WORKER"))
                .andExpect(jsonPath("$.status").value("ACTIVE")));

        UUID membershipId = UUID.fromString(accepted.get("membershipId").asString());
        assertThat(jdbc.sql("SELECT status || '/' || accepted_membership_id FROM worker_invitation")
                .query(String.class).single()).isEqualTo("ACCEPTED/" + membershipId);
        iam.getScoped(worker, businessId, "/v1/iam/permissions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WORKER"))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void anotherMobileIdentityCannotAccept() throws Exception {
        iam.invite(owner, businessId, WORKER_MOBILE, newKey());
        String token = invitationSender.lastToken(WORKER_MOBILE);
        JsonNode other = iam.signIn(OTHER_MOBILE);

        iam.accept(other, token, newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // Even the owner, who issued it, cannot use it.
        iam.accept(owner, token, newKey()).andExpect(status().isNotFound());
        assertThat(count("business_membership")).isEqualTo(1);

        iam.accept(iam.signIn(WORKER_MOBILE), token, newKey()).andExpect(status().isCreated());
    }

    @Test
    void anAcceptedInvitationCannotBeReplayed() throws Exception {
        iam.invite(owner, businessId, WORKER_MOBILE, newKey());
        String token = invitationSender.lastToken(WORKER_MOBILE);
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        String key = newKey();
        JsonNode first = iam.read(iam.accept(worker, token, key).andExpect(status().isCreated()));

        // A network retry with the same key gets the original answer...
        JsonNode retried = iam.read(iam.accept(worker, token, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")));
        assertThat(retried.get("membershipId")).isEqualTo(first.get("membershipId"));
        // ...but a new attempt is refused.
        iam.accept(worker, token, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
        assertThat(count("business_membership")).isEqualTo(2);
    }

    @Test
    void anExpiredInvitationCannotBeAccepted() throws Exception {
        iam.invite(owner, businessId, WORKER_MOBILE, newKey());
        jdbc.sql("UPDATE worker_invitation SET expires_at = now() - interval '1 second'").update();

        iam.accept(iam.signIn(WORKER_MOBILE), invitationSender.lastToken(WORKER_MOBILE), newKey())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        assertThat(count("business_membership")).isEqualTo(1);

        // Re-inviting replaces the expired invitation.
        iam.invite(owner, businessId, WORKER_MOBILE, newKey()).andExpect(status().isCreated());
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void unknownTokensAreNotFound() throws Exception {
        iam.accept(iam.signIn(WORKER_MOBILE), "not-a-real-invitation-token", newKey())
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentAcceptancesCreateOneMembership() throws Exception {
        iam.invite(owner, businessId, WORKER_MOBILE, newKey());
        String token = invitationSender.lastToken(WORKER_MOBILE);
        JsonNode worker = iam.signIn(WORKER_MOBILE);

        List<ResultActions> results = concurrently(6, () -> iam.accept(worker, token, newKey()));

        long created = results.stream().filter(r -> r.andReturn().getResponse().getStatus() == 201).count();
        assertThat(created).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.andReturn().getResponse().getStatus()).isIn(201, 409));
        assertThat(count("business_membership")).isEqualTo(2);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/v1/iam/invitations/accept")
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"anything-at-all\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ------------------------------------------------------------------------------

    private long pending() {
        return jdbc.sql("SELECT count(*) FROM worker_invitation WHERE status = 'PENDING'").query(Long.class).single();
    }

    private interface Call {
        ResultActions run() throws Exception;
    }

    private static List<ResultActions> concurrently(int threads, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResultActions>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.run();
                }));
            }
            start.countDown();
            List<ResultActions> results = new ArrayList<>();
            for (Future<ResultActions> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
