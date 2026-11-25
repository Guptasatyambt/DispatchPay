package org.myorg.dispatchpay.iam.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * M01.S09 gate: workers cannot self-escalate; a stale version conflicts; revoking a permission
 * blocks new authorizations.
 * M01.S11 gate: a suspended worker cannot confirm work prepared earlier; other businesses remain
 * accessible.
 */
class WorkerAccessChangeIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    private IamClient iam;
    private JsonNode owner;
    private JsonNode worker;
    private UUID businessId;
    private UUID workerId;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
        worker = iam.signIn(WORKER_MOBILE);
        workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
    }

    // --- M01.S09 replace permissions ----------------------------------------------------------

    @Test
    void ownerReplacesGrantsAndTheVersionAdvances() throws Exception {
        replace(owner, workerId, "[\"ORDER_CREATE\",\"ORDER_READ\",\"ORDER_READ\"]", 0, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(workerId.toString()))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.permissions[0]").value("ORDER_READ"))
                .andExpect(jsonPath("$.permissions[1]").value("ORDER_CREATE"))
                .andExpect(jsonPath("$.version").value(1));

        replace(owner, workerId, "[\"STOCK_READ\"]", 1, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("STOCK_READ"))
                .andExpect(jsonPath("$.version").value(2));
        iam.getScoped(worker, businessId, "/v1/iam/permissions")
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("STOCK_READ"));
    }

    @Test
    void revokingAPermissionBlocksNewAuthorizations() throws Exception {
        replace(owner, workerId, "[\"ORDER_CREATE\"]", 0, newKey()).andExpect(status().isOk());
        guardedOrderCreate(worker, businessId).andExpect(status().isOk());

        replace(owner, workerId, "[]", 1, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isEmpty());

        // Same access token as before: authorization is re-evaluated, not read from the token.
        guardedOrderCreate(worker, businessId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void aStaleVersionConflictsAndChangesNothing() throws Exception {
        replace(owner, workerId, "[\"ORDER_READ\"]", 0, newKey()).andExpect(status().isOk());

        replace(owner, workerId, "[\"REPORT_READ\"]", 0, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        replace(owner, workerId, "[\"REPORT_READ\"]", 7, newKey())
                .andExpect(status().isConflict());
        assertThat(grants()).containsExactly("ORDER_READ");
    }

    @Test
    void concurrentChangesFromOneVersionLetExactlyOneWin() throws Exception {
        List<ResultActions> results = concurrently(6, () -> replace(owner, workerId, "[\"ORDER_READ\"]", 0, newKey()));

        long ok = results.stream().filter(r -> r.andReturn().getResponse().getStatus() == 200).count();
        assertThat(ok).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.andReturn().getResponse().getStatus()).isIn(200, 409));
        assertThat(version(workerId)).isEqualTo(1);
    }

    @Test
    void workerCannotSelfEscalate() throws Exception {
        replace(worker, workerId, "[\"ORDER_CREATE\"]", 0, newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Even a worker holding every grantable permission cannot change grants.
        replace(owner, workerId, allPermissionsJson(), 0, newKey()).andExpect(status().isOk());
        replace(worker, workerId, "[]", 1, newKey()).andExpect(status().isForbidden());
        assertThat(version(workerId)).isEqualTo(1);
    }

    @Test
    void onlyCatalogPermissionsCanBeGranted() throws Exception {
        replace(owner, workerId, "[\"ORDER_READ\",\"MANAGE_WORKERS\"]", 0, newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        replace(owner, workerId, "[\"ORDER_READ\"]", -1, newKey())
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(put("/v1/iam/workers/" + workerId + "/permissions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"ORDER_READ\"]}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(grants()).isEmpty();
        assertThat(version(workerId)).isZero();
    }

    @Test
    void retryWithTheSameKeyReplays() throws Exception {
        String key = newKey();
        replace(owner, workerId, "[\"ORDER_READ\"]", 0, key).andExpect(status().isOk());

        replace(owner, workerId, "[\"ORDER_READ\"]", 0, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(version(workerId)).isEqualTo(1);
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(put("/v1/iam/workers/" + workerId + "/permissions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[],\"version\":0}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void permissionChangesCannotReachOtherTenantsOrTheOwner() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID ownerMembership = ownerMembership(businessId);

        replace(owner, ownerMembership(otherBusiness), "[\"ORDER_READ\"]", 0, newKey())
                .andExpect(status().isNotFound());
        replace(owner, ownerMembership, "[\"ORDER_READ\"]", 0, newKey())
                .andExpect(status().isNotFound());
        // The other owner cannot touch this business's worker through their own business.
        replace(otherOwner, workerId, "[\"ORDER_READ\"]", 0, newKey(), otherBusiness)
                .andExpect(status().isNotFound());
        assertThat(count("membership_permission")).isZero();
    }

    // --- M01.S11 suspend ----------------------------------------------------------------------

    @Test
    void suspendedWorkerCannotConfirmWorkPreparedEarlier() throws Exception {
        replace(owner, workerId, "[\"ORDER_CREATE\"]", 0, newKey()).andExpect(status().isOk());
        guardedOrderCreate(worker, businessId).andExpect(status().isOk());

        suspend(owner, workerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.permissions[0]").value("ORDER_CREATE"));

        guardedOrderCreate(worker, businessId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
        iam.getScoped(worker, businessId, "/v1/iam/permissions")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
    }

    @Test
    void suspensionLeavesIdentitySessionsAndOtherBusinessesIntact() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        iam.addWorker(otherOwner, otherBusiness, worker, WORKER_MOBILE);

        suspend(owner, workerId).andExpect(status().isOk());

        iam.getScoped(worker, otherBusiness, "/v1/iam/permissions").andExpect(status().isOk());
        mockMvc.perform(get("/v1/iam/me").header("Authorization", bearer(worker)))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status FROM user_account WHERE mobile = :m").param("m", WORKER_MOBILE)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM auth_session WHERE revoked_at IS NOT NULL")
                .query(Long.class).single()).isZero();
    }

    @Test
    void suspendingAgainChangesNothing() throws Exception {
        suspend(owner, workerId).andExpect(jsonPath("$.version").value(1));

        suspend(owner, workerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void suspensionInvalidatesAPermissionChangeBasedOnTheOldVersion() throws Exception {
        suspend(owner, workerId).andExpect(status().isOk());

        replace(owner, workerId, "[\"ORDER_READ\"]", 0, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void workersCannotSuspend() throws Exception {
        JsonNode colleague = iam.signIn("+919100000002");
        UUID colleagueId = iam.addWorker(owner, businessId, colleague, "+919100000002");

        suspend(worker, colleagueId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(membershipStatus(colleagueId)).isEqualTo("ACTIVE");
    }

    @Test
    void suspensionCannotReachOtherTenantsOrTheOwner() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID otherWorker = iam.addWorker(otherOwner, otherBusiness, iam.signIn("+919100000003"), "+919100000003");

        suspend(owner, otherWorker).andExpect(status().isNotFound());
        suspend(owner, ownerMembership(businessId)).andExpect(status().isNotFound());
        assertThat(membershipStatus(otherWorker)).isEqualTo("ACTIVE");
    }

    // --- M01.S12 reactivate -------------------------------------------------------------------

    @Test
    void reactivationRestoresOnlyTheReviewedGrants() throws Exception {
        replace(owner, workerId, "[\"ORDER_CREATE\",\"ORDER_READ\"]", 0, newKey()).andExpect(status().isOk());
        suspend(owner, workerId).andExpect(jsonPath("$.version").value(2));

        reactivate(owner, workerId, "[\"ORDER_READ\"]", 2, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("ORDER_READ"))
                .andExpect(jsonPath("$.version").value(3));

        iam.getScoped(worker, businessId, "/v1/iam/permissions").andExpect(status().isOk());
        // ORDER_CREATE was held before suspension but not re-granted.
        guardedOrderCreate(worker, businessId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void reactivationWithNoGrantsLeavesTheWorkerDefaultDenied() throws Exception {
        replace(owner, workerId, "[\"ORDER_CREATE\"]", 0, newKey()).andExpect(status().isOk());
        suspend(owner, workerId).andExpect(status().isOk());

        reactivate(owner, workerId, "[]", 2, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.permissions").isEmpty());
        assertThat(grants()).isEmpty();
    }

    @Test
    void onlyASuspendedWorkerCanBeReactivated() throws Exception {
        reactivate(owner, workerId, "[]", 0, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(version(workerId)).isZero();
    }

    @Test
    void reactivationWithAStaleVersionConflicts() throws Exception {
        suspend(owner, workerId).andExpect(status().isOk());

        reactivate(owner, workerId, "[]", 0, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(membershipStatus(workerId)).isEqualTo("SUSPENDED");
    }

    @Test
    void workersCannotReactivate() throws Exception {
        JsonNode colleague = iam.signIn("+919100000002");
        UUID colleagueId = iam.addWorker(owner, businessId, colleague, "+919100000002");
        suspend(owner, workerId).andExpect(status().isOk());

        // A suspended worker cannot reactivate themselves...
        reactivate(worker, workerId, "[\"ORDER_CREATE\"]", 1, newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
        // ...nor can an active colleague.
        reactivate(colleague, workerId, "[\"ORDER_CREATE\"]", 1, newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(membershipStatus(workerId)).isEqualTo("SUSPENDED");
        assertThat(membershipStatus(colleagueId)).isEqualTo("ACTIVE");
    }

    @Test
    void reactivationRetryReplays() throws Exception {
        suspend(owner, workerId).andExpect(status().isOk());
        String key = newKey();
        reactivate(owner, workerId, "[]", 1, key).andExpect(status().isOk());

        reactivate(owner, workerId, "[]", 1, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(version(workerId)).isEqualTo(2);
    }

    @Test
    void accessChangesAreAudited() throws Exception {
        replace(owner, workerId, "[\"ORDER_CREATE\"]", 0, newKey()).andExpect(status().isOk());
        suspend(owner, workerId).andExpect(status().isOk());
        suspend(owner, workerId).andExpect(status().isOk());
        reactivate(owner, workerId, "[\"ORDER_READ\"]", 2, newKey()).andExpect(status().isOk());

        JsonNode events = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=worker&resourceId=" + workerId)).get("items");

        // Newest first; the repeated suspension changed nothing and is not recorded.
        assertThat(events).hasSize(3);
        JsonNode reactivated = events.get(0);
        assertThat(reactivated.get("action").asString()).isEqualTo("iam.worker.reactivate");
        assertThat(reactivated.at("/changes/status/before").asString()).isEqualTo("SUSPENDED");
        assertThat(reactivated.at("/changes/status/after").asString()).isEqualTo("ACTIVE");
        assertThat(reactivated.at("/changes/permissions/before/0").asString()).isEqualTo("ORDER_CREATE");
        assertThat(reactivated.at("/changes/permissions/after/0").asString()).isEqualTo("ORDER_READ");
        assertThat(events.get(1).get("action").asString()).isEqualTo("iam.worker.suspend");
        assertThat(events.get(2).get("action").asString()).isEqualTo("iam.worker.permissions.replace");
        assertThat(events.get(2).at("/changes/permissions/after/0").asString()).isEqualTo("ORDER_CREATE");
        for (JsonNode event : events) {
            assertThat(event.get("actorType").asString()).isEqualTo("USER");
            assertThat(event.get("actorMembershipId").asString()).isEqualTo(ownerMembership(businessId).toString());
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private ResultActions reactivate(JsonNode caller, UUID membershipId, String permissionsJson, long version,
            String key) throws Exception {
        return mockMvc.perform(post("/v1/iam/workers/" + membershipId + "/reactivate")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}"));
    }

    private ResultActions replace(JsonNode caller, UUID membershipId, String permissionsJson, long version,
            String key) throws Exception {
        return replace(caller, membershipId, permissionsJson, version, key, businessId);
    }

    private ResultActions replace(JsonNode caller, UUID membershipId, String permissionsJson, long version,
            String key, UUID selectedBusiness) throws Exception {
        return mockMvc.perform(put("/v1/iam/workers/" + membershipId + "/permissions")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", selectedBusiness.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}"));
    }

    private ResultActions suspend(JsonNode caller, UUID membershipId) throws Exception {
        return mockMvc.perform(post("/v1/iam/workers/" + membershipId + "/suspend")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString()));
    }

    private ResultActions guardedOrderCreate(JsonNode caller, UUID business) throws Exception {
        return mockMvc.perform(post("/v1/test/guarded/order-create")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString()));
    }

    private List<String> grants() {
        return jdbc.sql("SELECT permission FROM membership_permission ORDER BY permission")
                .query(String.class).list();
    }

    private long version(UUID membershipId) {
        return jdbc.sql("SELECT version FROM business_membership WHERE id = :id")
                .param("id", membershipId).query(Long.class).single();
    }

    private String membershipStatus(UUID membershipId) {
        return jdbc.sql("SELECT status FROM business_membership WHERE id = :id")
                .param("id", membershipId).query(String.class).single();
    }

    private UUID ownerMembership(UUID business) {
        return jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", business).query(UUID.class).single();
    }

    private static String allPermissionsJson() {
        List<String> quoted = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            quoted.add("\"" + permission.name() + "\"");
        }
        return "[" + String.join(",", quoted) + "]";
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
