package org.myorg.dispatchpay.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * M03.S02 gate: ambiguous aliases return candidates, not silent selection.
 * M03.S03 gate: a duplicate SKU in a business fails; another business may use the same SKU.
 * M03.S04 gate: brand/pack ambiguity is preserved; invalid and inconsistent conversions are rejected.
 */
class ProductIT extends IntegrationTest {

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

    // --- M03.S03 create and edit ------------------------------------------------------------------

    @Test
    void createsAProduct() throws Exception {
        create(owner, businessId, "SALT-1KG", "Tata Salt 1 kg", "kg", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SALT-1KG"))
                .andExpect(jsonPath("$.name").value("Tata Salt 1 kg"))
                .andExpect(jsonPath("$.baseUnit").value("KG"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.aliases").isEmpty())
                .andExpect(jsonPath("$.conversions").isEmpty())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void brandPackSizeAndPriceAreStoredAndShown() throws Exception {
        UUID product = id(createJson("{\"sku\":\"TS-1KG\",\"name\":\"Tata Salt\",\"baseUnit\":\"KG\","
                + "\"brand\":\" Tata \",\"packSize\":\"1 kg pouch\",\"price\":2800}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brand").value("Tata"))
                .andExpect(jsonPath("$.packSize").value("1 kg pouch"))
                .andExpect(jsonPath("$.price").value(2800)));

        JsonNode item = search("?q=tata").get("items").get(0);
        assertThat(item.get("brand").asString()).isEqualTo("Tata");
        assertThat(item.get("packSize").asString()).isEqualTo("1 kg pouch");
        assertThat(item.get("price").asLong()).isEqualTo(2800);

        // Without them the fields are simply empty.
        createJson("{\"sku\":\"PLAIN\",\"name\":\"Plain\",\"baseUnit\":\"PCS\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brand").isEmpty())
                .andExpect(jsonPath("$.price").isEmpty());
        assertThat(product).isNotNull();
    }

    @Test
    void patchChangesOrClearsBrandPackSizeAndPrice() throws Exception {
        UUID product = id(createJson("{\"sku\":\"TS-1KG\",\"name\":\"Tata Salt\",\"baseUnit\":\"KG\","
                + "\"brand\":\"Tata\",\"packSize\":\"1 kg\",\"price\":2800}"));

        update(product, "{\"price\":2950,\"brand\":\"Tata Consumer\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(2950))
                .andExpect(jsonPath("$.brand").value("Tata Consumer"))
                .andExpect(jsonPath("$.packSize").value("1 kg"))
                .andExpect(jsonPath("$.version").value(1));
        // Explicit null clears; omitted fields stay.
        update(product, "{\"packSize\":null,\"price\":null,\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packSize").isEmpty())
                .andExpect(jsonPath("$.price").isEmpty())
                .andExpect(jsonPath("$.brand").value("Tata Consumer"))
                .andExpect(jsonPath("$.name").value("Tata Salt"));

        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=product&resourceId=" + product)).get("items").get(0);
        assertThat(event.at("/changes/price/before").asLong()).isEqualTo(2950);
        assertThat(event.at("/changes/price/after").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidBrandPackSizeAndPrice() throws Exception {
        createJson("{\"sku\":\"A\",\"name\":\"A\",\"baseUnit\":\"KG\",\"price\":-1}")
                .andExpect(status().isUnprocessableContent());
        createJson("{\"sku\":\"A\",\"name\":\"A\",\"baseUnit\":\"KG\",\"brand\":\"" + "b".repeat(121) + "\"}")
                .andExpect(status().isUnprocessableContent());
        UUID product = id(createJson("{\"sku\":\"A\",\"name\":\"A\",\"baseUnit\":\"KG\"}"));

        for (String body : List.of(
                "{\"price\":-5,\"version\":0}",
                "{\"price\":12.5,\"version\":0}",
                "{\"price\":\"100\",\"version\":0}",
                "{\"packSize\":\"" + "p".repeat(61) + "\",\"version\":0}",
                "{\"name\":null,\"version\":0}",
                "{\"colour\":\"red\",\"version\":0}",
                "{\"price\":100}",
                "[]")) {
            update(product, body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/products/" + product)).get("version").asLong())
                .isZero();
    }

    @Test
    void duplicateSkuFailsWithinABusinessButNotAcrossBusinesses() throws Exception {
        create(owner, businessId, "SALT-1KG", "Tata Salt 1 kg", "KG", newKey()).andExpect(status().isCreated());

        create(owner, businessId, "salt-1kg", "Another Salt", "KG", newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        create(otherOwner, iam.createBusiness(otherOwner, "Other Shop"), "SALT-1KG", "Their Salt", "KG", newKey())
                .andExpect(status().isCreated());
        assertThat(count("product")).isEqualTo(2);
    }

    @Test
    void concurrentCreatesWithOneSkuCreateOneProduct() throws Exception {
        List<ResultActions> results = concurrently(6,
                () -> create(owner, businessId, "SALT-1KG", "Tata Salt 1 kg", "KG", newKey()));

        long created = results.stream().filter(r -> r.andReturn().getResponse().getStatus() == 201).count();
        assertThat(created).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.andReturn().getResponse().getStatus()).isIn(201, 409));
        assertThat(count("product")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidProducts() throws Exception {
        create(owner, businessId, "SALT-1KG", "Tata Salt", "SACKFUL", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(owner, businessId, "SALT 1KG", "Tata Salt", "KG", newKey()).andExpect(status().isUnprocessableContent());
        create(owner, businessId, "SALT-1KG", "  ", "KG", newKey()).andExpect(status().isUnprocessableContent());
        create(owner, businessId, "x".repeat(65), "Tata Salt", "KG", newKey())
                .andExpect(status().isUnprocessableContent());
        assertThat(count("product")).isZero();
    }

    @Test
    void editsAreVersioned() throws Exception {
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));

        update(product, "{\"name\":\"Tata Salt 1 kg\",\"sku\":\"TS-1\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tata Salt 1 kg"))
                .andExpect(jsonPath("$.sku").value("TS-1"))
                .andExpect(jsonPath("$.version").value(1));
        update(product, "{\"name\":\"Stale\",\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void editingToAnotherProductsSkuConflicts() throws Exception {
        create(owner, businessId, "SALT-1KG", "Tata Salt 1 kg", "KG", newKey());
        UUID other = id(create(owner, businessId, "SUGAR-1KG", "Sugar 1 kg", "KG", newKey()));

        update(other, "{\"sku\":\"Salt-1kg\",\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void theBaseUnitNeverChanges() throws Exception {
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));

        update(product, "{\"baseUnit\":\"G\",\"version\":0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // Repeating the current unit is fine.
        update(product, "{\"baseUnit\":\"KG\",\"name\":\"Tata Salt 1 kg\",\"version\":0}").andExpect(status().isOk());

        // The database refuses it too, and refuses deleting a product.
        assertThatThrownBy(() -> jdbc.sql("UPDATE product SET base_unit = 'G'").update())
                .hasStackTraceContaining("cannot change");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM product").update())
                .hasStackTraceContaining("never deleted");
    }

    @Test
    void inactiveProductsLeaveSearchButStayReadable() throws Exception {
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));

        update(product, "{\"status\":\"INACTIVE\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        assertThat(search("?q=salt").get("totalElements").asLong()).isZero();
        assertThat(search("?q=salt&includeInactive=true").get("totalElements").asLong()).isEqualTo(1);
        iam.getScoped(owner, businessId, "/v1/products/" + product)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        update(product, "{\"status\":\"ACTIVE\",\"version\":1}").andExpect(status().isOk());
        assertThat(search("?q=salt").get("totalElements").asLong()).isEqualTo(1);
        update(product, "{\"status\":\"ARCHIVED\",\"version\":2}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void anUnchangedEditKeepsTheVersion() throws Exception {
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));

        update(product, "{\"name\":\"Tata Salt\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void retryWithTheSameKeyReplays() throws Exception {
        String key = newKey();
        JsonNode first = iam.read(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", key));

        JsonNode second = iam.read(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")));
        assertThat(second.get("id")).isEqualTo(first.get("id"));
    }

    @Test
    void workersNeedProductPermissions() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));

        iam.getScoped(worker, businessId, "/v1/products").andExpect(status().isForbidden());
        iam.getScoped(worker, businessId, "/v1/products/" + product).andExpect(status().isForbidden());

        grant(workerId, "[\"PRODUCT_READ\"]", 0);
        iam.getScoped(worker, businessId, "/v1/products").andExpect(status().isOk());
        create(worker, businessId, "SUGAR-1KG", "Sugar", "KG", newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        addAlias(worker, product, "namak").andExpect(status().isForbidden());

        grant(workerId, "[\"PRODUCT_READ\",\"PRODUCT_MANAGE\"]", 1);
        create(worker, businessId, "SUGAR-1KG", "Sugar", "KG", newKey()).andExpect(status().isCreated());
    }

    @Test
    void productsAreTenantScoped() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID foreign = id(create(otherOwner, otherBusiness, "SALT-1KG", "Their Salt", "KG", newKey()));

        iam.getScoped(owner, businessId, "/v1/products/" + foreign).andExpect(status().isNotFound());
        update(foreign, "{\"name\":\"Hijacked\",\"version\":0}").andExpect(status().isNotFound());
        addAlias(owner, foreign, "hijacked").andExpect(status().isNotFound());
        conversions(foreign, "[]", 0).andExpect(status().isNotFound());
        assertThat(search("?q=salt").get("totalElements").asLong()).isZero();
        iam.getScoped(owner, businessId, "/v1/products/not-a-uuid").andExpect(status().isNotFound());
    }

    @Test
    void changesAreAudited() throws Exception {
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));
        update(product, "{\"name\":\"Tata Salt 1 kg\",\"version\":0}").andExpect(status().isOk());
        addAlias(owner, product, "namak").andExpect(status().isCreated());
        conversions(product, "[{\"unit\":\"BAG\",\"factor\":25}]", 1).andExpect(status().isOk());

        JsonNode events = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=product&resourceId=" + product)).get("items");
        assertThat(events).hasSize(4);
        assertThat(events.get(0).get("action").asString()).isEqualTo("product.conversions.replace");
        assertThat(events.get(0).at("/changes/conversions/after/BAG").asString()).isEqualTo("25");
        assertThat(events.get(1).get("action").asString()).isEqualTo("product.alias.add");
        assertThat(events.get(2).get("action").asString()).isEqualTo("product.update");
        assertThat(events.get(2).at("/changes/name/before").asString()).isEqualTo("Tata Salt");
        assertThat(events.get(3).get("action").asString()).isEqualTo("product.create");
    }

    // --- M03.S02 search ---------------------------------------------------------------------------

    @Test
    void searchesBySkuNameAndAliasWithExactMatchesFirst() throws Exception {
        UUID salt = id(create(owner, businessId, "SALT-1KG", "Tata Salt 1 kg", "KG", newKey()));
        create(owner, businessId, "SUGAR-1KG", "Sugar 1 kg", "KG", newKey());
        UUID rockSalt = id(create(owner, businessId, "RS-1", "Rock Salt", "KG", newKey()));
        addAlias(owner, rockSalt, "salt").andExpect(status().isCreated());

        assertThat(names(search("?q=sugar-1"))).containsExactly("Sugar 1 kg");
        // "salt" is Rock Salt's exact alias, so it ranks first; Tata Salt still matches by name.
        JsonNode page = search("?q=SALT");
        assertThat(names(page)).containsExactly("Rock Salt", "Tata Salt 1 kg");
        assertThat(page.at("/items/0/matchedAliases/0").asString()).isEqualTo("salt");
        assertThat(page.at("/items/1/matchedAliases").isEmpty()).isTrue();
        assertThat(page.at("/items/1/id").asString()).isEqualTo(salt.toString());
        assertThat(search("").get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void ambiguousAliasesReturnEveryCandidate() throws Exception {
        UUID onekg = id(create(owner, businessId, "TS-1KG", "Tata Salt 1 kg", "KG", newKey()));
        UUID halfkg = id(create(owner, businessId, "TS-500G", "Tata Salt 500 g", "G", newKey()));
        addAlias(owner, onekg, "Tata namak").andExpect(status().isCreated());
        addAlias(owner, halfkg, "tata  NAMAK").andExpect(status().isCreated());

        JsonNode page = search("?q=tata namak");

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(names(page)).containsExactlyInAnyOrder("Tata Salt 1 kg", "Tata Salt 500 g");
        page.get("items").forEach(item -> assertThat(item.get("matchedAliases")).hasSize(1));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey());
        create(owner, businessId, "SUGAR_1KG", "Sugar", "KG", newKey());

        assertThat(search("?q=%").get("totalElements").asLong()).isZero();
        assertThat(names(search("?q=_1"))).containsExactly("Sugar");
    }

    @Test
    void searchPaginates() throws Exception {
        for (int i = 1; i <= 3; i++) {
            create(owner, businessId, "SKU-" + i, "Item " + i, "PCS", newKey());
        }

        JsonNode page = search("?size=2&page=1");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(names(page)).containsExactly("Item 3");
    }

    // --- M03.S04 aliases --------------------------------------------------------------------------

    @Test
    void aliasesAreApprovedByTheirAuthor() throws Exception {
        UUID product = id(create(owner, businessId, "SALT-1KG", "Tata Salt", "KG", newKey()));
        String ownerMembership = jdbc.sql("SELECT owner_membership_id::text FROM business WHERE id = :id")
                .param("id", businessId).query(String.class).single();

        addAlias(owner, product, "  Namak ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aliases[0].alias").value("Namak"))
                .andExpect(jsonPath("$.aliases[0].approvedByMembershipId").value(ownerMembership))
                .andExpect(jsonPath("$.version").value(0));
        addAlias(owner, product, "NAMAK")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        addAlias(owner, product, " ").andExpect(status().isUnprocessableContent());
    }

    @Test
    void theSameAliasMayBelongToSeveralProducts() throws Exception {
        UUID first = id(create(owner, businessId, "TS-1KG", "Tata Salt 1 kg", "KG", newKey()));
        UUID second = id(create(owner, businessId, "TS-500G", "Tata Salt 500 g", "G", newKey()));

        addAlias(owner, first, "tata salt").andExpect(status().isCreated());
        addAlias(owner, second, "tata salt").andExpect(status().isCreated());
        assertThat(count("product_alias")).isEqualTo(2);
    }

    // --- M03.S04 conversions ----------------------------------------------------------------------

    @Test
    void replacesConversions() throws Exception {
        UUID product = id(create(owner, businessId, "SOAP", "Lifebuoy Soap", "PCS", newKey()));

        conversions(product, "[{\"unit\":\"box\",\"factor\":12},{\"unit\":\"CARTON\",\"factor\":\"144.000\"},"
                + "{\"unit\":\"DOZEN\",\"factor\":12}]", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions.length()").value(3))
                .andExpect(jsonPath("$.conversions[0].unit").value("BOX"))
                .andExpect(jsonPath("$.conversions[0].factor").value(12))
                .andExpect(jsonPath("$.conversions[1].unit").value("CARTON"))
                .andExpect(jsonPath("$.conversions[1].factor").value(144))
                .andExpect(jsonPath("$.version").value(1));

        conversions(product, "[{\"unit\":\"BOX\",\"factor\":10}]", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions.length()").value(1))
                .andExpect(jsonPath("$.conversions[0].factor").value(10));
        conversions(product, "[]", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions").isEmpty());
    }

    @Test
    void decimalFactorsArePreserved() throws Exception {
        UUID rice = id(create(owner, businessId, "RICE", "Basmati Rice", "KG", newKey()));
        UUID eggs = id(create(owner, businessId, "EGGS", "Eggs", "DOZEN", newKey()));

        conversions(rice, "[{\"unit\":\"G\",\"factor\":0.001},{\"unit\":\"BAG\",\"factor\":\"25.5\"},"
                + "{\"unit\":\"QUINTAL\",\"factor\":100}]", 0)
                .andExpect(status().isOk());
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/products/" + rice)).get("conversions").toString())
                .contains("\"BAG\",\"factor\":25.5").contains("\"G\",\"factor\":0.001");
        // One piece is a twelfth of a dozen, rounded to six places.
        conversions(eggs, "[{\"unit\":\"PCS\",\"factor\":0.083333}]", 0).andExpect(status().isOk());
    }

    @Test
    void invalidAndInconsistentConversionsAreRejected() throws Exception {
        UUID rice = id(create(owner, businessId, "RICE", "Basmati Rice", "KG", newKey()));
        conversions(rice, "[{\"unit\":\"BAG\",\"factor\":25}]", 0).andExpect(status().isOk());

        List<String> rejected = List.of(
                "[{\"unit\":\"G\",\"factor\":1}]",                    // 1 G is always 0.001 KG
                "[{\"unit\":\"QUINTAL\",\"factor\":50}]",             // 1 QUINTAL is always 100 KG
                "[{\"unit\":\"KG\",\"factor\":1}]",                   // the base unit itself
                "[{\"unit\":\"BAG\",\"factor\":0}]",
                "[{\"unit\":\"BAG\",\"factor\":-25}]",
                "[{\"unit\":\"BAG\",\"factor\":0.0000001}]",          // more than 6 decimals
                "[{\"unit\":\"BAG\",\"factor\":12345678901234}]",     // more than 13 integer digits
                "[{\"unit\":\"BAG\",\"factor\":25},{\"unit\":\"bag\",\"factor\":30}]",
                "[{\"unit\":\"SACKFUL\",\"factor\":25}]",
                "[{\"unit\":\"BAG\"}]");
        for (String body : rejected) {
            conversions(rice, body, 1)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        JsonNode detail = iam.read(iam.getScoped(owner, businessId, "/v1/products/" + rice));
        assertThat(detail.get("version").asLong()).isEqualTo(1);
        assertThat(detail.get("conversions").toString()).isEqualTo("[{\"unit\":\"BAG\",\"factor\":25}]");

        UUID eggs = id(create(owner, businessId, "EGGS", "Eggs", "PCS", newKey()));
        conversions(eggs, "[{\"unit\":\"DOZEN\",\"factor\":10}]", 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("1 DOZEN is always 12 PCS"));
    }

    @Test
    void conversionChangesAreVersioned() throws Exception {
        UUID product = id(create(owner, businessId, "SOAP", "Lifebuoy Soap", "PCS", newKey()));
        conversions(product, "[{\"unit\":\"BOX\",\"factor\":12}]", 0).andExpect(status().isOk());

        conversions(product, "[{\"unit\":\"BOX\",\"factor\":24}]", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private ResultActions create(JsonNode caller, UUID business, String sku, String name, String baseUnit,
            String key) throws Exception {
        return mockMvc.perform(post("/v1/products")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"baseUnit\":\"" + baseUnit + "\"}"));
    }

    private ResultActions createJson(String body) throws Exception {
        return mockMvc.perform(post("/v1/products")
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(UUID product, String body) throws Exception {
        return mockMvc.perform(patch("/v1/products/" + product)
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions addAlias(JsonNode caller, UUID product, String alias) throws Exception {
        return mockMvc.perform(post("/v1/products/" + product + "/aliases")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"" + alias + "\"}"));
    }

    private ResultActions conversions(UUID product, String conversionsJson, long version) throws Exception {
        return mockMvc.perform(put("/v1/products/" + product + "/unit-conversions")
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversions\":" + conversionsJson + ",\"version\":" + version + "}"));
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

    private JsonNode search(String query) throws Exception {
        return iam.read(iam.getScoped(owner, businessId, "/v1/products" + query).andExpect(status().isOk()));
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
