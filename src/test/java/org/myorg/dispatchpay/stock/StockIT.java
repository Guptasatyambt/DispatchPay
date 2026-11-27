package org.myorg.dispatchpay.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * M05.S01 gate: balances agree with movements for fixtures.
 * M05.S02 gate: movement pagination is stable and tenant-safe.
 * M05.S03 gate: a duplicate command posts one movement.
 * M05.S04 gate: concurrent adjustments cannot bypass the stock floor.
 * M05.S05 gate: a seeded variance raises an actionable alert.
 */
class StockIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    @Autowired
    private StockReconciler reconciler;

    @Autowired
    private MeterRegistry meters;

    private IamClient iam;
    private JsonNode owner;
    private UUID businessId;
    private UUID ownerMembership;
    private UUID main;
    private UUID shop;
    private UUID soap;
    private UUID salt;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
        ownerMembership = jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", businessId).query(UUID.class).single();
        main = godown(owner, businessId, "Main Godown");
        shop = godown(owner, businessId, "Shop");
        soap = product(owner, businessId, "{\"sku\":\"SOAP-01\",\"name\":\"Lux Soap\",\"baseUnit\":\"PCS\"}");
        salt = product(owner, businessId, "{\"sku\":\"SALT-1KG\",\"name\":\"Tata Salt\",\"baseUnit\":\"KG\"}");
        mockMvc.perform(put("/v1/products/" + soap + "/unit-conversions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversions\":[{\"unit\":\"BOX\",\"factor\":12}],\"version\":0}"))
                .andExpect(status().isOk());
    }

    // --- M05.S03 opening and inward ---------------------------------------------------------------

    @Test
    void inwardPostsOneMovementPerLineInBaseUnitsAndUpdatesBalances() throws Exception {
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"reference\":\" BILL-42 \",\"note\":\"truck 1\","
                + "\"lines\":[" + line(soap, "2", "box") + "," + line(salt, "7.25", null) + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INWARD"))
                .andExpect(jsonPath("$.godownId").value(main.toString()))
                .andExpect(jsonPath("$.reference").value("BILL-42"))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerMembership.toString()))
                .andExpect(jsonPath("$.lines[0].line").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unit").value("BOX"))
                .andExpect(jsonPath("$.lines[0].unitFactor").value(12))
                .andExpect(jsonPath("$.lines[0].baseQuantity").value(24))
                .andExpect(jsonPath("$.lines[0].baseUnit").value("PCS"))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(24))
                .andExpect(jsonPath("$.lines[1].unit").value("KG"))
                .andExpect(jsonPath("$.lines[1].balanceAfter").value(7.25));
        inward(owner, newKey(), inwardBody(main, soap, "6")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(30));

        assertThat(balance(main, soap)).isEqualByComparingTo("30");
        assertThat(balance(main, salt)).isEqualByComparingTo("7.25");
        assertThat(count("stock_movement")).isEqualTo(3);
        assertThat(count("stock_document")).isEqualTo(2);

        JsonNode event = iam.read(iam.getScoped(owner, businessId, "/v1/audit-events?resourceType=stock_document"))
                .at("/items/1");
        assertThat(event.get("action").asString()).isEqualTo("stock.inward");
        assertThat(event.get("actorMembershipId").asString()).isEqualTo(ownerMembership.toString());
        assertThat(event.at("/changes/lines/after").size()).isEqualTo(2);
        assertThat(event.at("/changes/reference/after").asString()).isEqualTo("BILL-42");
    }

    @Test
    void aDuplicateCommandPostsOneMovement() throws Exception {
        String key = newKey();
        String body = inwardBody(main, soap, "10");
        JsonNode first = iam.read(inward(owner, key, body).andExpect(status().isCreated()));
        JsonNode replay = iam.read(inward(owner, key, body).andExpect(status().isCreated()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(replay.at("/lines/0/movementId")).isEqualTo(first.at("/lines/0/movementId"));

        inward(owner, key, inwardBody(main, soap, "11"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        inward(owner, null, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        // Concurrent duplicates serialize on the key: still one movement.
        String concurrentKey = newKey();
        String concurrentBody = inwardBody(shop, soap, "4");
        concurrently(4, () -> inward(owner, concurrentKey, concurrentBody));
        assertThat(count("stock_movement")).isEqualTo(2);
        assertThat(balance(main, soap)).isEqualByComparingTo("10");
        assertThat(balance(shop, soap)).isEqualByComparingTo("4");
    }

    @Test
    void openingStockIsOnlyPostedBeforeAnyOtherMovementOfThePair() throws Exception {
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"source\":\"opening\",\"lines\":["
                + line(soap, "50", null) + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("OPENING"));
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"source\":\"OPENING\",\"lines\":["
                + line(salt, "5", null) + "," + line(soap, "1", null) + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_STOCK_EXISTS"));
        // All or nothing: the salt line did not post either.
        assertThat(count("stock_movement")).isEqualTo(1);

        // The same product in another godown, or another product here, may still open.
        inward(owner, newKey(), "{\"godownId\":\"" + shop + "\",\"source\":\"OPENING\",\"lines\":["
                + line(soap, "5", null) + "]}").andExpect(status().isCreated());
        // Inward stock is not restricted.
        inward(owner, newKey(), inwardBody(main, soap, "1")).andExpect(status().isCreated());
        // Opening after an adjustment is refused too.
        adjust(owner, newKey(), adjustBody(main, salt, "COUNT_CORRECTION", null, "3")).andExpect(status().isCreated());
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"source\":\"OPENING\",\"lines\":["
                + line(salt, "5", null) + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_STOCK_EXISTS"));
    }

    @Test
    void invalidInwardRequestsAreRejectedWithoutPosting() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        inward(owner, newKey(), inwardBody(main, soap, "-1")).andExpect(status().isUnprocessableContent());
        inward(owner, newKey(), inwardBody(main, soap, "1.1234567")).andExpect(status().isUnprocessableContent());
        inward(owner, newKey(), inwardBody(main, soap, "12345678901234")).andExpect(status().isUnprocessableContent());
        // 0.000001 G is 0.000000001 KG, finer than the ledger records: refused, never rounded.
        mockMvc.perform(put("/v1/products/" + salt + "/unit-conversions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversions\":[{\"unit\":\"G\",\"factor\":0.001}],\"version\":0}"))
                .andExpect(status().isOk());
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(salt, "0.000001", "G")
                + "]}").andExpect(status().isUnprocessableContent());
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(salt, "500", "G")
                + "]}").andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].baseQuantity").value(0.5));
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(soap, "1", "KG") + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no conversion for KG")));
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(soap, "1", "CRATE") + "]}")
                .andExpect(status().isUnprocessableContent());
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(soap, "1", null) + ","
                + line(soap, "2", null) + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("lines[1]")));
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[]}")
                .andExpect(status().isUnprocessableContent());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            many.append(i == 0 ? "" : ",").append(line(UUID.randomUUID(), "1", null));
        }
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + many + "]}")
                .andExpect(status().isUnprocessableContent());
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"source\":\"ADJUSTMENT\",\"lines\":["
                + line(soap, "1", null) + "]}").andExpect(status().isUnprocessableContent());
        inward(owner, newKey(), inwardBody(main, UUID.randomUUID(), "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("lines[0]: product not found"));
        inward(owner, newKey(), inwardBody(UUID.randomUUID(), soap, "1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/v1/products/" + salt)
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"version\":1}"))
                .andExpect(status().isOk());
        inward(owner, newKey(), inwardBody(main, salt, "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
        assertThat(count("stock_movement")).isEqualTo(1);
        assertThat(count("stock_document")).isEqualTo(1);
    }

    // --- M05.S04 adjust ---------------------------------------------------------------------------

    @Test
    void adjustmentsAreSignedWithAReasonAndNeverGoBelowZero() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "10")).andExpect(status().isCreated());

        adjust(owner, newKey(), adjustBody(main, soap, "DAMAGE", "wet carton", "-4"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.reason").value("DAMAGE"))
                .andExpect(jsonPath("$.note").value("wet carton"))
                .andExpect(jsonPath("$.lines[0].baseQuantity").value(-4))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(6));
        adjust(owner, newKey(), adjustBody(main, soap, "LOSS", null, "-7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("only 6")));
        // A pair without stock cannot go negative either.
        adjust(owner, newKey(), adjustBody(shop, soap, "LOSS", null, "-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        adjust(owner, newKey(), adjustBody(main, soap, "COUNT_CORRECTION", null, "-6"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(0));
        adjust(owner, newKey(), adjustBody(main, soap, "COUNT_CORRECTION", null, "0.5"))
                .andExpect(status().isCreated());

        adjust(owner, newKey(), adjustBody(main, soap, "OTHER", null, "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("note is required when reason is OTHER"));
        adjust(owner, newKey(), adjustBody(main, soap, "OTHER", "found behind shelf", "1"))
                .andExpect(status().isCreated());
        adjust(owner, newKey(), adjustBody(main, soap, "MISC", null, "1")).andExpect(status().isUnprocessableContent());
        adjust(owner, newKey(), adjustBody(main, soap, "DAMAGE", null, "0")).andExpect(status().isUnprocessableContent());
        adjust(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(soap, "1", null) + "]}")
                .andExpect(status().isUnprocessableContent());

        assertThat(balance(main, soap)).isEqualByComparingTo("1.5");
        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=stock_document")).at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("stock.adjust");
        assertThat(event.at("/changes/reason/after").asString()).isEqualTo("OTHER");
    }

    @Test
    void inactiveProductsCanStillBeAdjusted() throws Exception {
        inward(owner, newKey(), inwardBody(main, salt, "3")).andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/products/" + salt)
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"version\":0}"))
                .andExpect(status().isOk());
        adjust(owner, newKey(), adjustBody(main, salt, "EXPIRY", null, "-3")).andExpect(status().isCreated());
        assertThat(balance(main, salt)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentAdjustmentsCannotBypassTheStockFloor() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "5")).andExpect(status().isCreated());

        List<ResultActions> results = concurrently(8,
                () -> adjust(owner, newKey(), adjustBody(main, soap, "LOSS", null, "-2")));
        List<Integer> statuses = new ArrayList<>();
        for (ResultActions result : results) {
            statuses.add(result.andReturn().getResponse().getStatus());
        }
        assertThat(statuses.stream().filter(status -> status == 201)).hasSize(2);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(6);
        assertThat(balance(main, soap)).isEqualByComparingTo("1");
        assertThat(reconciler.reconcile().variances()).isZero();
    }

    @Test
    void concurrentMultiLineDocumentsOverSharedKeysDoNotDeadlock() throws Exception {
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"lines\":[" + line(soap, "100", null) + ","
                + line(salt, "100", null) + "]}").andExpect(status().isCreated());
        // Opposite line orders: the ledger locks keys in one order whatever the request order.
        AtomicInteger calls = new AtomicInteger();
        List<ResultActions> results = concurrently(8, () -> {
            String lines = calls.getAndIncrement() % 2 == 0
                    ? line(soap, "-1", null) + "," + line(salt, "-1", null)
                    : line(salt, "-1", null) + "," + line(soap, "-1", null);
            return adjust(owner, newKey(), "{\"godownId\":\"" + main + "\",\"reason\":\"LOSS\",\"lines\":["
                    + lines + "]}");
        });
        for (ResultActions result : results) {
            assertThat(result.andReturn().getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(balance(main, soap)).isEqualByComparingTo("92");
        assertThat(balance(main, salt)).isEqualByComparingTo("92");
    }

    // --- permissions, scope and tenancy -----------------------------------------------------------

    @Test
    void workersNeedTheActionPermissionAndTheGodown() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        inward(owner, newKey(), inwardBody(main, soap, "10")).andExpect(status().isCreated());
        inward(owner, newKey(), inwardBody(shop, soap, "3")).andExpect(status().isCreated());

        inward(worker, newKey(), inwardBody(main, soap, "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        iam.getScoped(worker, businessId, "/v1/stock/balances").andExpect(status().isForbidden());
        iam.getScoped(worker, businessId, "/v1/stock/movements").andExpect(status().isForbidden());

        grant(workerId, "[\"STOCK_READ\",\"STOCK_INWARD\"]", 0);
        assignGodowns(workerId, "[\"" + main + "\"]", 1);
        inward(worker, newKey(), inwardBody(main, soap, "1")).andExpect(status().isCreated());
        inward(worker, newKey(), inwardBody(shop, soap, "1")).andExpect(status().isForbidden());
        adjust(worker, newKey(), adjustBody(main, soap, "LOSS", null, "-1")).andExpect(status().isForbidden());

        JsonNode balances = iam.read(iam.getScoped(worker, businessId, "/v1/stock/balances")
                .andExpect(status().isOk()));
        assertThat(balances.get("totalElements").asInt()).isEqualTo(1);
        assertThat(balances.at("/items/0/godownId").asString()).isEqualTo(main.toString());
        iam.getScoped(worker, businessId, "/v1/stock/balances?godownId=" + shop).andExpect(status().isForbidden());
        JsonNode movements = iam.read(iam.getScoped(worker, businessId, "/v1/stock/movements")
                .andExpect(status().isOk()));
        assertThat(movements.get("items")).hasSize(2);
        movements.get("items").forEach(item -> assertThat(item.get("godownId").asString()).isEqualTo(main.toString()));
        iam.getScoped(worker, businessId, "/v1/stock/movements?godownId=" + shop).andExpect(status().isForbidden());

        grant(workerId, "[\"STOCK_READ\",\"STOCK_INWARD\",\"STOCK_ADJUST\"]", 2);
        adjust(worker, newKey(), adjustBody(main, soap, "LOSS", null, "-1")).andExpect(status().isCreated());
        JsonNode adjusted = iam.read(iam.getScoped(worker, businessId, "/v1/stock/movements?type=ADJUSTMENT"))
                .at("/items/0");
        assertThat(adjusted.at("/actor/type").asString()).isEqualTo("USER");
        assertThat(adjusted.at("/actor/membershipId").asString()).isEqualTo(workerId.toString());
        assertThat(adjusted.get("initiatorMembershipId").asString()).isEqualTo(workerId.toString());
    }

    @Test
    void anotherBusinessCannotSeeOrUseThisStock() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "10")).andExpect(status().isCreated());
        JsonNode other = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(other, "Gupta Stores");
        UUID otherGodown = godown(other, otherBusiness, "Main Godown");

        assertThat(iam.read(iam.getScoped(other, otherBusiness, "/v1/stock/balances")).get("totalElements").asInt())
                .isZero();
        assertThat(iam.read(iam.getScoped(other, otherBusiness, "/v1/stock/movements?godownId=" + main))
                .get("items")).isEmpty();
        assertThat(iam.read(iam.getScoped(other, otherBusiness, "/v1/stock/movements?productId=" + soap))
                .get("items")).isEmpty();
        // Neither the godown nor the product of this business can be used there.
        inward(other, otherBusiness, newKey(), inwardBody(main, UUID.randomUUID(), "1"))
                .andExpect(status().isNotFound());
        inward(other, otherBusiness, newKey(), inwardBody(otherGodown, soap, "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("lines[0]: product not found"));
        // Nor can this business's member reach that business's stock by switching the header.
        iam.getScoped(owner, otherBusiness, "/v1/stock/balances").andExpect(status().isNotFound());
        assertThat(count("stock_movement")).isEqualTo(1);
    }

    // --- M05.S01 balances -------------------------------------------------------------------------

    @Test
    void balancesAgreeWithMovementsForFixtures() throws Exception {
        inward(owner, newKey(), "{\"godownId\":\"" + main + "\",\"source\":\"OPENING\",\"lines\":["
                + line(soap, "3", "BOX") + "," + line(salt, "12.5", null) + "]}").andExpect(status().isCreated());
        inward(owner, newKey(), inwardBody(shop, soap, "6")).andExpect(status().isCreated());
        adjust(owner, newKey(), adjustBody(main, soap, "DAMAGE", null, "-1.5")).andExpect(status().isCreated());
        adjust(owner, newKey(), adjustBody(shop, soap, "COUNT_CORRECTION", null, "-6")).andExpect(status().isCreated());
        inward(owner, newKey(), inwardBody(main, salt, "0.25")).andExpect(status().isCreated());

        JsonNode balances = iam.read(iam.getScoped(owner, businessId, "/v1/stock/balances")
                .andExpect(status().isOk()));
        assertThat(balances.get("totalElements").asInt()).isEqualTo(3);
        // Godown name order, then SKU.
        assertThat(row(balances, 0)).isEqualTo("Main Godown SALT-1KG 12.75 KG");
        assertThat(row(balances, 1)).isEqualTo("Main Godown SOAP-01 34.5 PCS");
        assertThat(row(balances, 2)).isEqualTo("Shop SOAP-01 0 PCS");
        assertThat(balances.at("/items/0/productName").asString()).isEqualTo("Tata Salt");

        JsonNode nonZero = iam.read(iam.getScoped(owner, businessId, "/v1/stock/balances?nonZero=true"));
        assertThat(nonZero.get("totalElements").asInt()).isEqualTo(2);
        JsonNode filtered = iam.read(iam.getScoped(owner, businessId,
                "/v1/stock/balances?godownId=" + main + "&productId=" + soap));
        assertThat(filtered.get("items")).hasSize(1);
        iam.getScoped(owner, businessId, "/v1/stock/balances?godownId=nope").andExpect(status().isUnprocessableContent());

        // Every balance equals the sum of its movements and the last movement's balanceAfter.
        Integer disagreeing = jdbc.sql("""
                        SELECT count(*) FROM stock_balance b
                        WHERE b.quantity <> (SELECT sum(quantity) FROM stock_movement m
                                             WHERE m.godown_id = b.godown_id AND m.product_id = b.product_id)
                           OR b.quantity <> (SELECT balance_after FROM stock_movement m
                                             WHERE m.seq = b.last_movement_seq)
                        """).query(Integer.class).single();
        assertThat(disagreeing).isZero();
        assertThat(reconciler.reconcile().variances()).isZero();
    }

    // --- M05.S02 movements ------------------------------------------------------------------------

    @Test
    void movementPagesAreStableWhileNewMovementsArrive() throws Exception {
        for (int i = 1; i <= 5; i++) {
            inward(owner, newKey(), inwardBody(main, soap, String.valueOf(i))).andExpect(status().isCreated());
        }
        JsonNode first = iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?size=2")
                .andExpect(status().isOk()));
        assertThat(quantities(first)).containsExactly("5", "4");
        String cursor = first.get("nextCursor").asString();

        // New movements land before the cursor: later pages neither repeat nor skip rows.
        inward(owner, newKey(), inwardBody(main, soap, "100")).andExpect(status().isCreated());
        JsonNode second = iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?size=2&cursor=" + cursor));
        assertThat(quantities(second)).containsExactly("3", "2");
        JsonNode third = iam.read(iam.getScoped(owner, businessId,
                "/v1/stock/movements?size=2&cursor=" + second.get("nextCursor").asString()));
        assertThat(quantities(third)).containsExactly("1");
        assertThat(third.get("nextCursor").isNull()).isTrue();

        JsonNode item = first.at("/items/0");
        assertThat(item.get("type").asString()).isEqualTo("INWARD");
        assertThat(item.get("sku").asString()).isEqualTo("SOAP-01");
        assertThat(item.get("balanceAfter").asString()).isEqualTo("15");
        assertThat(item.at("/source/type").asString()).isEqualTo("STOCK_DOCUMENT");
        assertThat(item.at("/source/line").asInt()).isEqualTo(1);
        assertThat(item.at("/actor/membershipId").asString()).isEqualTo(ownerMembership.toString());

        iam.getScoped(owner, businessId, "/v1/stock/movements?cursor=garbage").andExpect(status().isUnprocessableContent());
        iam.getScoped(owner, businessId, "/v1/stock/movements?size=101").andExpect(status().isUnprocessableContent());
        iam.getScoped(owner, businessId, "/v1/stock/movements?type=SALE").andExpect(status().isUnprocessableContent());
    }

    @Test
    void movementsFilterByGodownProductTypeAndTime() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "5")).andExpect(status().isCreated());
        inward(owner, newKey(), inwardBody(shop, salt, "5")).andExpect(status().isCreated());
        adjust(owner, newKey(), adjustBody(main, soap, "THEFT", "night shift", "-1")).andExpect(status().isCreated());

        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?godownId=" + shop))
                .get("items")).hasSize(1);
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?productId=" + soap))
                .get("items")).hasSize(2);
        JsonNode adjustments = iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?type=adjustment"));
        assertThat(adjustments.get("items")).hasSize(1);
        assertThat(adjustments.at("/items/0/reason").asString()).isEqualTo("THEFT");
        assertThat(adjustments.at("/items/0/note").asString()).isEqualTo("night shift");
        assertThat(adjustments.at("/items/0/quantity").asString()).isEqualTo("-1");

        String postedAt = adjustments.at("/items/0/postedAt").asString();
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?from=" + postedAt))
                .get("items")).hasSize(1);
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/stock/movements?to=" + postedAt))
                .get("items")).hasSize(2);
        iam.getScoped(owner, businessId, "/v1/stock/movements?from=" + postedAt + "&to=" + postedAt)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theLedgerIsAppendOnly() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "5")).andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE stock_movement SET quantity = 6").update()).hasStackTraceContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM stock_movement").update()).hasStackTraceContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE stock_balance SET quantity = -1").update()).hasStackTraceContaining("ck_stock_balance");
    }

    // --- M05.S05 reconciliation -------------------------------------------------------------------

    @Test
    void aSeededVarianceRaisesAnActionableAlertWithoutRewritingHistory() throws Exception {
        inward(owner, newKey(), inwardBody(main, soap, "10")).andExpect(status().isCreated());
        inward(owner, newKey(), inwardBody(shop, salt, "4")).andExpect(status().isCreated());
        assertThat(reconciler.reconcile()).isEqualTo(new StockReconciler.Report(false, 0, 0, 0, 0));

        // Seed a variance: the projection drifts from the ledger.
        jdbc.sql("UPDATE stock_balance SET quantity = 12 WHERE godown_id = :g").param("g", main).update();
        StockReconciler.Report report = reconciler.reconcile();
        assertThat(report.variances()).isEqualTo(1);
        assertThat(report.opened()).isEqualTo(1);
        assertThat(meters.get(StockReconciler.OPEN_DISCREPANCIES).gauge().value()).isEqualTo(1.0);
        assertThat(jdbc.sql("""
                        SELECT business_id || ' ' || godown_id || ' ' || product_id || ' ' || balance_quantity
                               || ' ' || movement_quantity || ' ' || status
                        FROM stock_discrepancy
                        """).query(String.class).single())
                .isEqualTo(businessId + " " + main + " " + soap + " 12.000000 10.000000 OPEN");
        // Neither side was rewritten.
        assertThat(balance(main, soap)).isEqualByComparingTo("12");
        assertThat(count("stock_movement")).isEqualTo(2);

        // Seen again: still one open discrepancy, not a new one.
        assertThat(reconciler.reconcile().opened()).isZero();
        assertThat(count("stock_discrepancy")).isEqualTo(1);

        // Once the projection agrees again, the discrepancy is resolved.
        jdbc.sql("UPDATE stock_balance SET quantity = 10 WHERE godown_id = :g").param("g", main).update();
        report = reconciler.reconcile();
        assertThat(report.resolved()).isEqualTo(1);
        assertThat(report.open()).isZero();
        assertThat(meters.get(StockReconciler.OPEN_DISCREPANCIES).gauge().value()).isZero();
        assertThat(jdbc.sql("SELECT status FROM stock_discrepancy").query(String.class).single())
                .isEqualTo("RESOLVED");
    }

    // --- helpers ----------------------------------------------------------------------------------

    private ResultActions inward(JsonNode caller, String key, String body) throws Exception {
        return inward(caller, businessId, key, body);
    }

    private ResultActions inward(JsonNode caller, UUID business, String key, String body) throws Exception {
        var request = post("/v1/stock/inwards")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private ResultActions adjust(JsonNode caller, String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/stock/adjustments")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String inwardBody(UUID godownId, UUID productId, String quantity) {
        return "{\"godownId\":\"" + godownId + "\",\"lines\":[" + line(productId, quantity, null) + "]}";
    }

    private static String adjustBody(UUID godownId, UUID productId, String reason, String note, String quantity) {
        return "{\"godownId\":\"" + godownId + "\",\"reason\":\"" + reason + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"")
                + ",\"lines\":[" + line(productId, quantity, null) + "]}";
    }

    private static String line(UUID productId, String quantity, String unit) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + (unit == null ? "" : ",\"unit\":\"" + unit + "\"") + "}";
    }

    private BigDecimal balance(UUID godownId, UUID productId) {
        return jdbc.sql("SELECT quantity FROM stock_balance WHERE godown_id = :godownId AND product_id = :productId")
                .param("godownId", godownId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .single();
    }

    private static String row(JsonNode page, int index) {
        JsonNode item = page.at("/items/" + index);
        return item.get("godownName").asString() + " " + item.get("sku").asString() + " "
                + item.get("quantity").asString() + " " + item.get("baseUnit").asString();
    }

    private static List<String> quantities(JsonNode page) {
        List<String> quantities = new ArrayList<>();
        page.get("items").forEach(item -> quantities.add(item.get("quantity").asString()));
        return quantities;
    }

    private UUID godown(JsonNode caller, UUID business, String name) throws Exception {
        return id(mockMvc.perform(post("/v1/godowns")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()));
    }

    private UUID product(JsonNode caller, UUID business, String json) throws Exception {
        return id(mockMvc.perform(post("/v1/products")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated()));
    }

    private void grant(UUID workerId, String permissionsJson, long version) throws Exception {
        workerPut(workerId, "/permissions", "{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}");
    }

    private void assignGodowns(UUID workerId, String idsJson, long version) throws Exception {
        workerPut(workerId, "/godowns", "{\"godownIds\":" + idsJson + ",\"version\":" + version + "}");
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
