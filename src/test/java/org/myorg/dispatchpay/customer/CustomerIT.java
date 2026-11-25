package org.myorg.dispatchpay.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * M03.S05 gate: no cross-tenant data through search.
 * M03.S06 gate: edits are attributed and optimistic conflicts are detected.
 * M03.S07 gate: similar customer names are not merged automatically.
 */
class CustomerIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    private IamClient iam;
    private JsonNode owner;
    private UUID businessId;
    private UUID ownerMembership;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
        ownerMembership = jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", businessId).query(UUID.class).single();
    }

    // --- M03.S06 create and edit ------------------------------------------------------------------

    @Test
    void createsACustomerAttributedToItsCreator() throws Exception {
        create(owner, businessId, "{\"name\":\" Gupta Kirana \",\"contactPerson\":\"Ramesh Gupta\","
                + "\"phone\":\"+91 98765 11111\",\"address\":\"Main Bazaar, Indore\","
                + "\"paymentTermsDays\":15,\"creditLimit\":5000000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Gupta Kirana"))
                .andExpect(jsonPath("$.contactPerson").value("Ramesh Gupta"))
                .andExpect(jsonPath("$.phone").value("+91 98765 11111"))
                .andExpect(jsonPath("$.credit.paymentTermsDays").value(15))
                .andExpect(jsonPath("$.credit.creditLimit").value(5000000))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerMembership.toString()))
                .andExpect(jsonPath("$.updatedByMembershipId").value(ownerMembership.toString()))
                .andExpect(jsonPath("$.version").value(0));
    }

    // --- username ---------------------------------------------------------------------------------

    @Test
    void usernameIsStoredLowerCaseAndShown() throws Exception {
        UUID customer = id(create(owner, businessId, "{\"username\":\" Gupta.Kirana_01 \",\"name\":\"Gupta Kirana\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("gupta.kirana_01")));
        create(owner, businessId, "{\"username\":\"kirana-world\",\"name\":\"Gupta Kirana World\"}");

        JsonNode page = search(owner, businessId, "?q=GUPTA.KIRANA_01");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.at("/items/0/username").asString()).isEqualTo("gupta.kirana_01");
        // An exact username match ranks first.
        assertThat(search(owner, businessId, "?q=kirana-world").at("/items/0/username").asString())
                .isEqualTo("kirana-world");
        assertThat(customer).isNotNull();
    }

    @Test
    void usernameIsUniqueWithinABusinessIgnoringCase() throws Exception {
        create(owner, businessId, "{\"username\":\"gupta\",\"name\":\"Gupta Kirana\"}").andExpect(status().isCreated());

        create(owner, businessId, "{\"username\":\"GUPTA\",\"name\":\"Another Gupta\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(count("customer")).isEqualTo(1);

        // Another business may use the same username.
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        create(otherOwner, iam.createBusiness(otherOwner, "Other Shop"), "{\"username\":\"gupta\",\"name\":\"G\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentCreatesWithOneUsernameCreateOneCustomer() throws Exception {
        List<ResultActions> results = concurrently(6,
                () -> create(owner, businessId, "{\"username\":\"gupta\",\"name\":\"Gupta Kirana\"}"));

        long created = results.stream().filter(r -> r.andReturn().getResponse().getStatus() == 201).count();
        assertThat(created).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.andReturn().getResponse().getStatus()).isIn(201, 409));
        assertThat(count("customer")).isEqualTo(1);
    }

    @Test
    void usernameIsRequiredAndValidated() throws Exception {
        for (String username : List.of("", "ab", "-gupta", "gupta kirana", "gupta@kirana", "x".repeat(41))) {
            mockMvc.perform(post("/v1/customers")
                            .header("Authorization", bearer(owner))
                            .header("X-Business-Id", businessId.toString())
                            .header("Idempotency-Key", newKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"name\":\"Gupta\"}"))
                    .andExpect(status().isUnprocessableContent());
        }
        mockMvc.perform(post("/v1/customers")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gupta\"}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(count("customer")).isZero();
    }

    @Test
    void usernameCanBeChangedButNotClearedOrDuplicated() throws Exception {
        create(owner, businessId, "{\"username\":\"taken\",\"name\":\"Taken\"}");
        UUID customer = id(create(owner, businessId, "{\"username\":\"gupta\",\"name\":\"Gupta Kirana\"}"));

        update(owner, customer, "{\"username\":\"Gupta-Kirana\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("gupta-kirana"))
                .andExpect(jsonPath("$.version").value(1));
        update(owner, customer, "{\"username\":\"TAKEN\",\"version\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        update(owner, customer, "{\"username\":null,\"version\":1}").andExpect(status().isUnprocessableContent());
        update(owner, customer, "{\"username\":\"a b\",\"version\":1}").andExpect(status().isUnprocessableContent());

        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=customer&resourceId=" + customer)).get("items").get(0);
        assertThat(event.at("/changes/username/before").asString()).isEqualTo("gupta");
        assertThat(event.at("/changes/username/after").asString()).isEqualTo("gupta-kirana");
    }

    @Test
    void theDatabaseEnforcesUsernameFormatAndUniqueness() throws Exception {
        UUID customer = id(create(owner, businessId, "{\"username\":\"gupta\",\"name\":\"Gupta Kirana\"}"));
        create(owner, businessId, "{\"username\":\"verma\",\"name\":\"Verma\"}");

        assertThatThrownBy(() -> jdbc.sql("UPDATE customer SET username = 'Has Space' WHERE id = :id")
                .param("id", customer).update())
                .hasStackTraceContaining("ck_customer_username");
        assertThatThrownBy(() -> jdbc.sql("UPDATE customer SET username = 'verma' WHERE id = :id")
                .param("id", customer).update())
                .hasStackTraceContaining("uq_customer_username");
    }

    @Test
    void editsAreAttributedToTheEditor() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        grant(workerId, "[\"CUSTOMER_READ\",\"CUSTOMER_MANAGE\"]", 0);
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\"}"));

        update(worker, customer, "{\"phone\":\"9876511111\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("9876511111"))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerMembership.toString()))
                .andExpect(jsonPath("$.updatedByMembershipId").value(workerId.toString()))
                .andExpect(jsonPath("$.version").value(1));

        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=customer&resourceId=" + customer)).get("items").get(0);
        assertThat(event.get("action").asString()).isEqualTo("customer.update");
        assertThat(event.get("actorMembershipId").asString()).isEqualTo(workerId.toString());
        assertThat(event.at("/changes/phone/after").asString()).isEqualTo("9876511111");
    }

    @Test
    void aStaleEditConflicts() throws Exception {
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\"}"));
        update(owner, customer, "{\"name\":\"Gupta Kirana Store\",\"version\":0}").andExpect(status().isOk());

        update(owner, customer, "{\"name\":\"Lost Update\",\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        iam.getScoped(owner, businessId, "/v1/customers/" + customer)
                .andExpect(jsonPath("$.name").value("Gupta Kirana Store"));
    }

    @Test
    void concurrentEditsFromOneVersionLetExactlyOneWin() throws Exception {
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\"}"));

        List<ResultActions> results = concurrently(6,
                () -> update(owner, customer, "{\"address\":\"" + UUID.randomUUID() + "\",\"version\":0}"));

        long ok = results.stream().filter(r -> r.andReturn().getResponse().getStatus() == 200).count();
        assertThat(ok).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.andReturn().getResponse().getStatus()).isIn(200, 409));
    }

    @Test
    void nullClearsOptionalFieldsAndOmittedFieldsStay() throws Exception {
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\",\"phone\":\"9876511111\","
                + "\"address\":\"Indore\",\"creditLimit\":100000}"));

        update(owner, customer, "{\"phone\":null,\"creditLimit\":null,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.credit.creditLimit").isEmpty())
                .andExpect(jsonPath("$.address").value("Indore"));
        // An edit that changes nothing keeps the version.
        update(owner, customer, "{\"address\":\"Indore\",\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsInvalidCustomers() throws Exception {
        create(owner, businessId, "{\"name\":\" \"}").andExpect(status().isUnprocessableContent());
        create(owner, businessId, "{\"name\":\"A\",\"phone\":\"call me\"}")
                .andExpect(status().isUnprocessableContent());
        create(owner, businessId, "{\"name\":\"A\",\"paymentTermsDays\":400}")
                .andExpect(status().isUnprocessableContent());
        create(owner, businessId, "{\"name\":\"A\",\"creditLimit\":-1}").andExpect(status().isUnprocessableContent());
        assertThat(count("customer")).isZero();

        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\"}"));
        for (String body : List.of(
                "{\"name\":null,\"version\":0}",
                "{\"phone\":\"abc\",\"version\":0}",
                "{\"paymentTermsDays\":-1,\"version\":0}",
                "{\"creditLimit\":10.5,\"version\":0}",
                "{\"gstin\":\"X\",\"version\":0}",
                "{\"name\":\"B\"}")) {
            update(owner, customer, body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void workersNeedCustomerPermissions() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\"}"));

        iam.getScoped(worker, businessId, "/v1/customers").andExpect(status().isForbidden());
        iam.getScoped(worker, businessId, "/v1/customers/" + customer).andExpect(status().isForbidden());

        grant(workerId, "[\"CUSTOMER_READ\"]", 0);
        iam.getScoped(worker, businessId, "/v1/customers").andExpect(status().isOk());
        create(worker, businessId, "{\"name\":\"New\"}").andExpect(status().isForbidden());
        update(worker, customer, "{\"name\":\"X\",\"version\":0}").andExpect(status().isForbidden());
        addAlias(worker, customer, "gk").andExpect(status().isForbidden());
    }

    // --- M03.S05 search and receivables -----------------------------------------------------------

    @Test
    void receivablesFieldsNeedTheirOwnPermission() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\",\"paymentTermsDays\":30,"
                + "\"creditLimit\":5000000}"));
        grant(workerId, "[\"CUSTOMER_READ\",\"CUSTOMER_MANAGE\"]", 0);

        String hidden = iam.getScoped(worker, businessId, "/v1/customers/" + customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credit").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(hidden).doesNotContain("5000000");
        // Editing other fields is fine, and the response still hides receivables...
        update(worker, customer, "{\"name\":\"Gupta Kirana Store\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credit").isEmpty());
        // ...but setting them is not.
        update(worker, customer, "{\"creditLimit\":9000000,\"version\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        create(worker, businessId, "{\"name\":\"New\",\"paymentTermsDays\":7}").andExpect(status().isForbidden());

        grant(workerId, "[\"CUSTOMER_READ\",\"CUSTOMER_MANAGE\",\"CUSTOMER_RECEIVABLES_READ\"]", 1);
        iam.getScoped(worker, businessId, "/v1/customers/" + customer)
                .andExpect(jsonPath("$.credit.paymentTermsDays").value(30))
                .andExpect(jsonPath("$.credit.creditLimit").value(5000000));
        update(worker, customer, "{\"creditLimit\":9000000,\"version\":1}").andExpect(status().isOk());
    }

    @Test
    void searchesByNamePhoneAndAlias() throws Exception {
        create(owner, businessId, "{\"name\":\"Gupta Kirana\",\"phone\":\"+91 98765 11111\"}");
        UUID verma = id(create(owner, businessId, "{\"name\":\"Verma General Store\"}"));
        addAlias(owner, verma, "VGS").andExpect(status().isCreated());

        assertThat(names(search(owner, businessId, "?q=kirana"))).containsExactly("Gupta Kirana");
        assertThat(names(search(owner, businessId, "?q=98765 111"))).containsExactly("Gupta Kirana");
        JsonNode byAlias = search(owner, businessId, "?q=vgs");
        assertThat(names(byAlias)).containsExactly("Verma General Store");
        assertThat(byAlias.at("/items/0/matchedAliases/0").asString()).isEqualTo("VGS");
        assertThat(search(owner, businessId, "").get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void noCrossTenantDataThroughSearch() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID foreign = id(create(otherOwner, otherBusiness, "{\"name\":\"Gupta Kirana\",\"phone\":\"9000000001\","
                + "\"creditLimit\":7777777}"));
        addAlias(otherOwner, otherBusiness, foreign, "gk").andExpect(status().isCreated());
        create(owner, businessId, "{\"name\":\"Gupta Kirana\"}");

        for (String query : List.of("?q=gupta", "?q=9000000001", "?q=gk", "")) {
            String body = iam.getScoped(owner, businessId, "/v1/customers" + query).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(foreign.toString()).doesNotContain("9000000001");
        }
        assertThat(search(owner, businessId, "?q=gupta").get("totalElements").asLong()).isEqualTo(1);

        String detail = iam.getScoped(owner, businessId, "/v1/customers/" + foreign)
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("7777777");
        update(owner, foreign, "{\"name\":\"Hijacked\",\"version\":0}").andExpect(status().isNotFound());
        addAlias(owner, foreign, "hijacked").andExpect(status().isNotFound());
        iam.getScoped(owner, otherBusiness, "/v1/customers").andExpect(status().isNotFound());
    }

    // --- M03.S07 aliases --------------------------------------------------------------------------

    @Test
    void similarNamesAreNotMerged() throws Exception {
        UUID first = id(create(owner, businessId, "{\"name\":\"Sharma Stores\",\"address\":\"Indore\"}"));
        UUID second = id(create(owner, businessId, "{\"name\":\"SHARMA  stores\",\"address\":\"Bhopal\"}"));
        create(owner, businessId, "{\"name\":\"Sharma Store\"}").andExpect(status().isCreated());

        assertThat(second).isNotEqualTo(first);
        assertThat(count("customer")).isEqualTo(3);
        assertThat(count("customer_alias")).isZero();
        JsonNode page = search(owner, businessId, "?q=sharma store");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void aliasesAreExplicitlyApprovedAndKeepEveryPlausibleMatch() throws Exception {
        UUID indore = id(create(owner, businessId, "{\"name\":\"Sharma Stores\",\"address\":\"Indore\"}"));
        UUID bhopal = id(create(owner, businessId, "{\"name\":\"Sharma Stores\",\"address\":\"Bhopal\"}"));

        addAlias(owner, indore, " Sharma ji ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aliases[0].alias").value("Sharma ji"))
                .andExpect(jsonPath("$.aliases[0].approvedByMembershipId").value(ownerMembership.toString()));
        addAlias(owner, bhopal, "sharma JI").andExpect(status().isCreated());
        addAlias(owner, indore, "SHARMA  JI")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        JsonNode page = search(owner, businessId, "?q=sharma ji");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        page.get("items").forEach(item -> assertThat(item.get("matchedAliases")).hasSize(1));
    }

    @Test
    void changesAreAudited() throws Exception {
        UUID customer = id(create(owner, businessId, "{\"name\":\"Gupta Kirana\",\"creditLimit\":100}"));
        update(owner, customer, "{\"name\":\"Gupta Kirana Store\",\"version\":0}").andExpect(status().isOk());
        addAlias(owner, customer, "gk").andExpect(status().isCreated());

        JsonNode events = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=customer&resourceId=" + customer)).get("items");
        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("action").asString()).isEqualTo("customer.alias.add");
        assertThat(events.get(1).get("action").asString()).isEqualTo("customer.update");
        assertThat(events.get(2).get("action").asString()).isEqualTo("customer.create");
        assertThat(events.get(2).at("/changes/creditLimit/after").asLong()).isEqualTo(100);
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Adds a unique username when the body has none, so tests about other fields stay short. */
    private ResultActions create(JsonNode caller, UUID business, String body) throws Exception {
        if (!body.contains("\"username\"")) {
            body = body.replaceFirst("\\{", "{\"username\":\"u" + UUID.randomUUID().toString().substring(0, 8) + "\",");
        }
        return mockMvc.perform(post("/v1/customers")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(JsonNode caller, UUID customer, String body) throws Exception {
        return mockMvc.perform(patch("/v1/customers/" + customer)
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions addAlias(JsonNode caller, UUID customer, String alias) throws Exception {
        return addAlias(caller, businessId, customer, alias);
    }

    private ResultActions addAlias(JsonNode caller, UUID business, UUID customer, String alias) throws Exception {
        return mockMvc.perform(post("/v1/customers/" + customer + "/aliases")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"" + alias + "\"}"));
    }

    private void grant(UUID workerId, String permissionsJson, long version) throws Exception {
        mockMvc.perform(put("/v1/iam/workers/" + workerId + "/permissions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode search(JsonNode caller, UUID business, String query) throws Exception {
        return iam.read(iam.getScoped(caller, business, "/v1/customers" + query).andExpect(status().isOk()));
    }

    private UUID id(ResultActions created) throws Exception {
        return UUID.fromString(iam.read(created).get("id").asString());
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
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
