package org.myorg.dispatchpay.iam.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M01.S04 gate: no activated business without owner; concurrent registration cannot duplicate ownership. */
class BusinessIT extends IntegrationTest {

    private static final String MOBILE = "+919876543210";
    private static final String OTHER_MOBILE = "+919123456789";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    PlatformTransactionManager transactionManager;

    // --- API ----------------------------------------------------------------------------------

    @Test
    void createsBusinessWithTheCallerAsActiveOwner() throws Exception {
        JsonNode owner = signIn(MOBILE);

        JsonNode created = read(create(owner, "key-create-0001", "{\"name\":\"  Sharma Traders \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sharma Traders"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("OWNER")));

        UUID businessId = UUID.fromString(created.get("id").asString());
        UUID membershipId = UUID.fromString(created.get("membershipId").asString());
        assertThat(jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", businessId).query(UUID.class).single()).isEqualTo(membershipId);
        assertThat(jdbc.sql("SELECT user_id::text || '/' || status FROM business_membership WHERE id = :id")
                .param("id", membershipId).query(String.class).single())
                .isEqualTo(owner.get("userId").asString() + "/ACTIVE");
    }

    @Test
    void acceptsAnExplicitTimezone() throws Exception {
        JsonNode owner = signIn(MOBILE);

        create(owner, "key-create-0002", "{\"name\":\"Dubai Branch\",\"timezone\":\"Asia/Dubai\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timezone").value("Asia/Dubai"));
    }

    @Test
    void retryWithTheSameKeyReplaysTheOriginalBusiness() throws Exception {
        JsonNode owner = signIn(MOBILE);
        String body = "{\"name\":\"Sharma Traders\"}";

        JsonNode first = read(create(owner, "key-retry-0001", body).andExpect(status().isCreated()));
        JsonNode second = read(create(owner, "key-retry-0001", body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")));

        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(count("business")).isEqualTo(1);
        assertThat(count("business_membership")).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentBusinessConflicts() throws Exception {
        JsonNode owner = signIn(MOBILE);
        create(owner, "key-reuse-0001", "{\"name\":\"First\"}").andExpect(status().isCreated());

        create(owner, "key-reuse-0001", "{\"name\":\"Second\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("business")).isEqualTo(1);
    }

    @Test
    void idempotencyKeysAreScopedPerUser() throws Exception {
        JsonNode first = signIn(MOBILE);
        JsonNode second = signIn(OTHER_MOBILE);

        JsonNode a = read(create(first, "key-shared-0001", "{\"name\":\"Same\"}").andExpect(status().isCreated()));
        JsonNode b = read(create(second, "key-shared-0001", "{\"name\":\"Same\"}").andExpect(status().isCreated()));

        assertThat(b.get("id")).isNotEqualTo(a.get("id"));
        assertThat(count("business")).isEqualTo(2);
    }

    @Test
    void aUserMayOwnSeveralBusinesses() throws Exception {
        JsonNode owner = signIn(MOBILE);

        create(owner, "key-multi-0001", "{\"name\":\"Shop One\"}").andExpect(status().isCreated());
        create(owner, "key-multi-0002", "{\"name\":\"Shop Two\"}").andExpect(status().isCreated());

        assertThat(count("business")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT owner_membership_id) FROM business")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        JsonNode owner = signIn(MOBILE);

        mockMvc.perform(post("/v1/businesses")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sharma Traders\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(count("business")).isZero();
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        JsonNode owner = signIn(MOBILE);

        create(owner, "key-invalid-0001", "{\"name\":\"   \"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        create(owner, "key-invalid-0002", "{\"name\":\"" + "x".repeat(121) + "\"}")
                .andExpect(status().isUnprocessableContent());
        create(owner, "key-invalid-0003", "{\"name\":\"Shop\",\"timezone\":\"Mars/Olympus\"}")
                .andExpect(status().isUnprocessableContent());
        create(owner, "key-invalid-0004", "{\"name\":\"Shop\",\"timezone\":\"+05:30\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(count("business")).isZero();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/v1/businesses")
                        .header("Idempotency-Key", "key-anon-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sharma Traders\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(count("business")).isZero();
    }

    @Test
    void concurrentSubmissionsWithOneKeyCreateOneBusinessAndOneOwner() throws Exception {
        JsonNode owner = signIn(MOBILE);
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResultActions>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return create(owner, "key-concurrent-0001", "{\"name\":\"Sharma Traders\"}");
                }));
            }
            start.countDown();

            Set<String> ids = new HashSet<>();
            for (Future<ResultActions> future : futures) {
                var response = future.get().andReturn().getResponse();
                // A request that arrives while the first is still running is told to retry (409).
                assertThat(response.getStatus()).isIn(201, 409);
                if (response.getStatus() == 201) {
                    ids.add(json.readTree(response.getContentAsString()).get("id").asString());
                }
            }
            assertThat(ids).hasSize(1);
        }
        assertThat(count("business")).isEqualTo(1);
        assertThat(count("business_membership")).isEqualTo(1);
    }

    // --- Database invariants: the schema itself refuses ownerless or misowned businesses -------

    @Test
    void businessCannotBeCommittedWithoutItsOwnerMembership() throws Exception {
        UUID userId = userId(signIn(MOBILE));

        assertThatThrownBy(() -> inTransaction(() -> insertBusiness(UUID.randomUUID(), UUID.randomUUID(), userId)))
                .hasStackTraceContaining("fk_business_owner_membership");
        assertThat(count("business")).isZero();
    }

    @Test
    void ownerMustBeAMembershipOfTheSameBusiness() throws Exception {
        JsonNode owner = signIn(MOBILE);
        UUID worker = userId(signIn(OTHER_MOBILE));
        UUID shopOne = UUID.fromString(
                read(create(owner, "key-foreign-0001", "{\"name\":\"Shop One\"}")).get("id").asString());
        UUID workerInShopOne = UUID.randomUUID();
        inTransaction(() -> insertMembership(workerInShopOne, shopOne, worker));

        assertThatThrownBy(() -> inTransaction(() -> insertBusiness(UUID.randomUUID(), workerInShopOne, worker)))
                .hasStackTraceContaining("fk_business_owner_membership");
        assertThat(count("business")).isEqualTo(1);
    }

    @Test
    void aBusinessHasOneOwnerSoASecondCannotBeAdded() throws Exception {
        JsonNode owner = signIn(MOBILE);
        JsonNode created = read(create(owner, "key-second-0001", "{\"name\":\"Shop One\"}"));
        UUID ownerMembership = UUID.fromString(created.get("membershipId").asString());

        // Reusing an existing owner membership for another business is refused outright.
        assertThatThrownBy(() -> inTransaction(() -> insertBusiness(UUID.randomUUID(), ownerMembership,
                userId(owner))))
                .hasStackTraceContaining("uq_business_owner");
        assertThat(count("business")).isEqualTo(1);
    }

    @Test
    void ownerMembershipCannotBeSuspendedWhileTheBusinessIsActive() throws Exception {
        JsonNode owner = signIn(MOBILE);
        JsonNode created = read(create(owner, "key-suspend-0001", "{\"name\":\"Shop One\"}"));

        assertThatThrownBy(() -> inTransaction(() -> jdbc.sql(
                        "UPDATE business_membership SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", UUID.fromString(created.get("membershipId").asString()))
                .update()))
                .hasStackTraceContaining("ck_business_owner_active");
    }

    @Test
    void ownerMembershipCannotBeDeleted() throws Exception {
        JsonNode owner = signIn(MOBILE);
        read(create(owner, "key-delete-0001", "{\"name\":\"Shop One\"}"));

        assertThatThrownBy(() -> inTransaction(() -> jdbc.sql("DELETE FROM business_membership").update()))
                .hasStackTraceContaining("fk_business_owner_membership");
        assertThat(count("business_membership")).isEqualTo(1);
    }

    @Test
    void aWorkerMembershipCanBeSuspended() throws Exception {
        JsonNode owner = signIn(MOBILE);
        UUID worker = userId(signIn(OTHER_MOBILE));
        UUID businessId = UUID.fromString(
                read(create(owner, "key-worker-0001", "{\"name\":\"Shop One\"}")).get("id").asString());

        inTransaction(() -> {
            UUID membership = UUID.randomUUID();
            insertMembership(membership, businessId, worker);
            jdbc.sql("UPDATE business_membership SET status = 'SUSPENDED' WHERE id = :id")
                    .param("id", membership).update();
        });
        assertThat(count("business_membership")).isEqualTo(2);
    }

    // --- helpers ------------------------------------------------------------------------------

    private JsonNode signIn(String mobile) throws Exception {
        mockMvc.perform(post("/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\"}"));
        return read(mockMvc.perform(post("/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\",\"code\":\"" + otpSender.lastCode(mobile) + "\"}")));
    }

    private ResultActions create(JsonNode caller, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/v1/businesses")
                .header("Authorization", bearer(caller))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void insertBusiness(UUID businessId, UUID ownerMembershipId, UUID userId) {
        jdbc.sql("""
                        INSERT INTO business
                            (id, name, timezone, status, owner_membership_id, created_by_user_id, created_at, updated_at)
                        VALUES (:id, 'Orphan', 'Asia/Kolkata', 'ACTIVE', :owner, :userId, now(), now())
                        """)
                .param("id", businessId).param("owner", ownerMembershipId).param("userId", userId).update();
    }

    private void insertMembership(UUID id, UUID businessId, UUID userId) {
        jdbc.sql("""
                        INSERT INTO business_membership (id, business_id, user_id, status, created_at, updated_at)
                        VALUES (:id, :businessId, :userId, 'ACTIVE', now(), now())
                        """)
                .param("id", id).param("businessId", businessId).param("userId", userId).update();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static UUID userId(JsonNode tokens) {
        return UUID.fromString(tokens.get("userId").asString());
    }

    private static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asString();
    }
}
