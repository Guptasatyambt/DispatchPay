package org.myorg.dispatchpay.godown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** M03.S01 gate: cross-tenant godown selection fails. */
class GodownIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
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
    void ownerCreatesAndListsGodowns() throws Exception {
        create(owner, businessId, "  Main Godown ", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Main Godown"))
                .andExpect(jsonPath("$.version").value(0));
        create(owner, businessId, "Annex", newKey()).andExpect(status().isCreated());

        JsonNode page = iam.read(iam.getScoped(owner, businessId, "/v1/godowns").andExpect(status().isOk()));
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(names(page)).containsExactly("Annex", "Main Godown");
    }

    @Test
    void namesAreUniquePerBusinessIgnoringCase() throws Exception {
        create(owner, businessId, "Main Godown", newKey()).andExpect(status().isCreated());

        create(owner, businessId, "MAIN GODOWN", newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Another business may use the same name.
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        create(otherOwner, iam.createBusiness(otherOwner, "Other Shop"), "Main Godown", newKey())
                .andExpect(status().isCreated());
    }

    @Test
    void retryWithTheSameKeyReplays() throws Exception {
        String key = newKey();
        JsonNode first = iam.read(create(owner, businessId, "Main Godown", key).andExpect(status().isCreated()));

        JsonNode second = iam.read(create(owner, businessId, "Main Godown", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")));
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(count("godown")).isEqualTo(1);
    }

    @Test
    void editsAreVersioned() throws Exception {
        UUID godown = id(create(owner, businessId, "Main Godown", newKey()));

        update(owner, businessId, godown, "Central Godown", 0, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Central Godown"))
                .andExpect(jsonPath("$.version").value(1));
        update(owner, businessId, godown, "Stale Edit", 0, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        JsonNode page = iam.read(iam.getScoped(owner, businessId, "/v1/godowns"));
        assertThat(names(page)).containsExactly("Central Godown");
    }

    @Test
    void renamingToAnExistingNameConflicts() throws Exception {
        create(owner, businessId, "Annex", newKey());
        UUID godown = id(create(owner, businessId, "Main Godown", newKey()));

        update(owner, businessId, godown, "annex", 0, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void crossTenantGodownSelectionFails() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID foreign = id(create(otherOwner, otherBusiness, "Their Godown", newKey()));

        // Editing another business's godown through one's own business: not found.
        String body = update(owner, businessId, foreign, "Hijacked", 0, newKey())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Their Godown");
        // Selecting the other business itself is refused.
        update(owner, otherBusiness, foreign, "Hijacked", 0, newKey()).andExpect(status().isNotFound());
        iam.getScoped(owner, otherBusiness, "/v1/godowns").andExpect(status().isNotFound());
        // Lists never include another business's godowns.
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/godowns")).get("totalElements").asLong()).isZero();
        // The other business's godown is untouched.
        assertThat(jdbc.sql("SELECT name FROM godown WHERE id = :id").param("id", foreign)
                .query(String.class).single()).isEqualTo("Their Godown");
    }

    @Test
    void workersNeedGodownManageToCreateOrEdit() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID godown = id(create(owner, businessId, "Main Godown", newKey()));
        assignGodowns(workerId, "[\"" + godown + "\"]", 0);

        create(worker, businessId, "Worker Godown", newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        update(worker, businessId, godown, "Renamed", 0, newKey()).andExpect(status().isForbidden());

        grant(workerId, "[\"GODOWN_MANAGE\"]", 1);
        create(worker, businessId, "Worker Godown", newKey()).andExpect(status().isCreated());
        update(worker, businessId, godown, "Renamed", 0, newKey()).andExpect(status().isOk());
    }

    @Test
    void workersListOnlyGodownsInTheirScope() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID main = id(create(owner, businessId, "Main Godown", newKey()));
        create(owner, businessId, "Annex", newKey());

        assertThat(iam.read(iam.getScoped(worker, businessId, "/v1/godowns")).get("totalElements").asLong())
                .isZero();
        assignGodowns(workerId, "[\"" + main + "\"]", 0);
        assertThat(names(iam.read(iam.getScoped(worker, businessId, "/v1/godowns")))).containsExactly("Main Godown");
    }

    @Test
    void createsAndEditsAreAudited() throws Exception {
        UUID godown = id(create(owner, businessId, "Main Godown", newKey()));
        update(owner, businessId, godown, "Central Godown", 0, newKey()).andExpect(status().isOk());

        JsonNode events = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=godown&resourceId=" + godown)).get("items");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("action").asString()).isEqualTo("godown.update");
        assertThat(events.get(0).at("/changes/name/before").asString()).isEqualTo("Main Godown");
        assertThat(events.get(0).at("/changes/name/after").asString()).isEqualTo("Central Godown");
        assertThat(events.get(1).get("action").asString()).isEqualTo("godown.create");
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        create(owner, businessId, "   ", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(owner, businessId, "x".repeat(121), newKey()).andExpect(status().isUnprocessableContent());
        mockMvc.perform(post("/v1/godowns")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Main Godown\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(count("godown")).isZero();
    }

    // --- helpers ------------------------------------------------------------------------------

    private ResultActions create(JsonNode caller, UUID business, String name, String key) throws Exception {
        return mockMvc.perform(post("/v1/godowns")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"));
    }

    private ResultActions update(JsonNode caller, UUID business, UUID godown, String name, long version, String key)
            throws Exception {
        return mockMvc.perform(patch("/v1/godowns/" + godown)
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"version\":" + version + "}"));
    }

    private void assignGodowns(UUID workerId, String idsJson, long version) throws Exception {
        workerPut(workerId, "/godowns", "{\"godownIds\":" + idsJson + ",\"version\":" + version + "}");
    }

    private void grant(UUID workerId, String permissionsJson, long version) throws Exception {
        workerPut(workerId, "/permissions", "{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}");
    }

    private void workerPut(UUID workerId, String path, String body) throws Exception {
        mockMvc.perform(put("/v1/iam/workers/" + workerId + path)
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private UUID id(ResultActions created) throws Exception {
        return UUID.fromString(iam.read(created).get("id").asString());
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }
}
