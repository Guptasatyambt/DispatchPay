package org.myorg.dispatchpay.iam.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** M01.S10 gate: a worker is denied operations outside their godown scope even with the action permission. */
class WorkerGodownScopeIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    private IamClient iam;
    private JsonNode owner;
    private JsonNode worker;
    private UUID businessId;
    private UUID workerId;
    private UUID main;
    private UUID annex;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
        worker = iam.signIn(WORKER_MOBILE);
        workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        main = createGodown(owner, businessId, "Main Godown");
        annex = createGodown(owner, businessId, "Annex");
    }

    @Test
    void ownerAssignsGodownsAndTheWorkerSeesTheScope() throws Exception {
        assign(owner, workerId, List.of(main, annex), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.godownScope.all").value(false))
                .andExpect(jsonPath("$.godownScope.godownIds.length()").value(2))
                .andExpect(jsonPath("$.version").value(1));

        JsonNode access = iam.read(iam.getScoped(worker, businessId, "/v1/iam/permissions"));
        assertThat(access.at("/godownScope/all").asBoolean()).isFalse();
        assertThat(access.at("/godownScope/godownIds").toString()).contains(main.toString(), annex.toString());
    }

    @Test
    void assignmentReplacesTheWholeScope() throws Exception {
        assign(owner, workerId, List.of(main, annex), 0).andExpect(status().isOk());

        assign(owner, workerId, List.of(annex), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.godownScope.godownIds.length()").value(1))
                .andExpect(jsonPath("$.godownScope.godownIds[0]").value(annex.toString()));
        assign(owner, workerId, List.of(), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.godownScope.godownIds").isEmpty());
        assertThat(count("membership_godown")).isZero();
    }

    @Test
    void workerIsDeniedOutsideScopeEvenWithTheActionPermission() throws Exception {
        grant(workerId, "[\"GODOWN_MANAGE\"]", 0);
        assign(owner, workerId, List.of(main), 1).andExpect(status().isOk());

        renameGodown(worker, main, "Main Godown 2", 0).andExpect(status().isOk());
        renameGodown(worker, annex, "Annex 2", 0)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Removing a godown from scope applies to the worker's next request.
        assign(owner, workerId, List.of(), 2).andExpect(status().isOk());
        renameGodown(worker, main, "Main Godown 3", 1).andExpect(status().isForbidden());
    }

    @Test
    void crossTenantGodownsAreRejected() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID foreign = createGodown(otherOwner, otherBusiness, "Their Godown");

        String foreignBody = assign(owner, workerId, List.of(main, foreign), 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(foreignBody).contains(foreign.toString()).doesNotContain("Their Godown")
                .doesNotContain(main.toString());
        // An unknown id is reported the same way.
        UUID unknown = UUID.randomUUID();
        assign(owner, workerId, List.of(unknown), 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown godowns: " + unknown));
        assertThat(count("membership_godown")).isZero();
        assertThat(version()).isZero();

        // The database refuses it too.
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO membership_godown (business_id, membership_id, godown_id, created_at)
                        VALUES (:b, :m, :g, now())
                        """).param("b", businessId).param("m", workerId).param("g", foreign).update())
                .hasStackTraceContaining("fk_membership_godown_godown");
    }

    @Test
    void aStaleVersionConflicts() throws Exception {
        assign(owner, workerId, List.of(main), 0).andExpect(status().isOk());

        assign(owner, workerId, List.of(annex), 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void workersCannotAssignGodowns() throws Exception {
        grant(workerId, "[\"GODOWN_MANAGE\"]", 0);

        assign(worker, workerId, List.of(main, annex), 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(count("membership_godown")).isZero();
    }

    @Test
    void theOwnerHasEveryGodownAndCannotBeScoped() throws Exception {
        UUID ownerMembership = jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", businessId).query(UUID.class).single();

        assign(owner, ownerMembership, List.of(main), 0).andExpect(status().isNotFound());
        iam.getScoped(owner, businessId, "/v1/iam/permissions")
                .andExpect(jsonPath("$.godownScope.all").value(true));
    }

    @Test
    void assignmentsAreAudited() throws Exception {
        assign(owner, workerId, List.of(main), 0).andExpect(status().isOk());

        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=worker&resourceId=" + workerId)).get("items").get(0);
        assertThat(event.get("action").asString()).isEqualTo("iam.worker.godowns.replace");
        assertThat(event.at("/changes/godownIds/before").isEmpty()).isTrue();
        assertThat(event.at("/changes/godownIds/after/0").asString()).isEqualTo(main.toString());
    }

    // --- helpers ------------------------------------------------------------------------------

    private ResultActions assign(JsonNode caller, UUID membershipId, List<UUID> godownIds, long version)
            throws Exception {
        String ids = String.join(",", godownIds.stream().map(id -> "\"" + id + "\"").toList());
        return mockMvc.perform(put("/v1/iam/workers/" + membershipId + "/godowns")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"godownIds\":[" + ids + "],\"version\":" + version + "}"));
    }

    private void grant(UUID membershipId, String permissionsJson, long version) throws Exception {
        mockMvc.perform(put("/v1/iam/workers/" + membershipId + "/permissions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private UUID createGodown(JsonNode caller, UUID business, String name) throws Exception {
        JsonNode created = iam.read(mockMvc.perform(post("/v1/godowns")
                        .header("Authorization", bearer(caller))
                        .header("X-Business-Id", business.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()));
        return UUID.fromString(created.get("id").asString());
    }

    private ResultActions renameGodown(JsonNode caller, UUID godown, String name, long version) throws Exception {
        return mockMvc.perform(patch("/v1/godowns/" + godown)
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"version\":" + version + "}"));
    }

    private long version() {
        return jdbc.sql("SELECT version FROM business_membership WHERE id = :id")
                .param("id", workerId).query(Long.class).single();
    }
}
