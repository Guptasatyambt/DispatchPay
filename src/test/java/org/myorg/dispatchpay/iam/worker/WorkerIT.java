package org.myorg.dispatchpay.iam.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import tools.jackson.databind.JsonNode;

/** M01.S08 gate: cross-tenant ids disclose no worker details. */
class WorkerIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_A = "+919100000001";
    private static final String WORKER_B = "+919100000002";
    private static final String WORKER_C = "+919100000003";
    private static final String OTHER_OWNER = "+919200000001";

    private IamClient iam;
    private JsonNode owner;
    private UUID businessId;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
    }

    @Test
    void ownerListsWorkersPageByPageWithoutThemselves() throws Exception {
        for (String mobile : List.of(WORKER_A, WORKER_B, WORKER_C)) {
            iam.addWorker(owner, businessId, iam.signIn(mobile), mobile);
        }

        JsonNode first = iam.read(iam.getScoped(owner, businessId, "/v1/iam/workers?size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2)));
        JsonNode second = iam.read(iam.getScoped(owner, businessId, "/v1/iam/workers?page=1&size=2")
                .andExpect(status().isOk()));

        List<String> mobiles = new ArrayList<>();
        first.get("items").forEach(item -> mobiles.add(item.get("mobile").asString()));
        second.get("items").forEach(item -> mobiles.add(item.get("mobile").asString()));
        assertThat(mobiles).containsExactlyInAnyOrder(WORKER_A, WORKER_B, WORKER_C);
    }

    @Test
    void listedWorkersCarryOnlyMembershipFacts() throws Exception {
        iam.addWorker(owner, businessId, iam.signIn(WORKER_A), WORKER_A);

        JsonNode item = iam.read(iam.getScoped(owner, businessId, "/v1/iam/workers")).get("items").get(0);

        assertThat(item.propertyNames()).containsExactlyInAnyOrder("membershipId", "mobile", "status", "joinedAt");
        assertThat(item.get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void ownerInspectsAWorkersAccess() throws Exception {
        UUID membership = iam.addWorker(owner, businessId, iam.signIn(WORKER_A), WORKER_A);
        jdbc.sql("""
                        INSERT INTO membership_permission (business_id, membership_id, permission, created_at)
                        VALUES (:b, :m, 'ORDER_READ', now()), (:b, :m, 'ORDER_CREATE', now())
                        """)
                .param("b", businessId).param("m", membership).update();
        jdbc.sql("UPDATE business_membership SET status = 'SUSPENDED' WHERE id = :id").param("id", membership).update();

        JsonNode detail = iam.read(iam.getScoped(owner, businessId, "/v1/iam/workers/" + membership)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.mobile").value(WORKER_A))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.permissions[0]").value("ORDER_READ"))
                .andExpect(jsonPath("$.permissions[1]").value("ORDER_CREATE"))
                .andExpect(jsonPath("$.godownScope.all").value(false)));

        assertThat(detail.propertyNames()).doesNotContain("userId");
    }

    @Test
    void workersCannotListOrInspect() throws Exception {
        JsonNode worker = iam.signIn(WORKER_A);
        UUID membership = iam.addWorker(owner, businessId, worker, WORKER_A);

        iam.getScoped(worker, businessId, "/v1/iam/workers")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        iam.getScoped(worker, businessId, "/v1/iam/workers/" + membership)
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantIdsDiscloseNothing() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID foreignWorker = iam.addWorker(otherOwner, otherBusiness, iam.signIn(WORKER_B), WORKER_B);
        iam.addWorker(owner, businessId, iam.signIn(WORKER_A), WORKER_A);

        String foreign = iam.getScoped(owner, businessId, "/v1/iam/workers/" + foreignWorker)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknown = iam.getScoped(owner, businessId, "/v1/iam/workers/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(foreign).doesNotContain(WORKER_B).doesNotContain("Other Shop");
        assertThat(detail(foreign)).isEqualTo(detail(unknown));

        // The other business's worker never appears in this business's list.
        JsonNode list = iam.read(iam.getScoped(owner, businessId, "/v1/iam/workers"));
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.toString()).doesNotContain(WORKER_B);

        // Selecting the other business is refused outright.
        iam.getScoped(owner, otherBusiness, "/v1/iam/workers").andExpect(status().isNotFound());
    }

    @Test
    void theOwnersOwnMembershipAndMalformedIdsAreNotWorkers() throws Exception {
        UUID ownerMembership = jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", businessId).query(UUID.class).single();

        iam.getScoped(owner, businessId, "/v1/iam/workers/" + ownerMembership).andExpect(status().isNotFound());
        iam.getScoped(owner, businessId, "/v1/iam/workers/not-a-uuid").andExpect(status().isNotFound());
    }

    @Test
    void emptyBusinessHasNoWorkers() throws Exception {
        iam.getScoped(owner, businessId, "/v1/iam/workers")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private String detail(String problemJson) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(problemJson).get("detail").asString();
    }
}
