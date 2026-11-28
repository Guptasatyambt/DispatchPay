package org.myorg.dispatchpay.order;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * M06.S01 gate: a draft creates neither a stock movement nor a receivable.
 * M06.S02 gate: workers see only allowed resources.
 * M06.S03 gate: a stale edit conflicts; fulfilled quantities cannot be overwritten.
 * M06.S04 gate: repeated confirmation is idempotent; stock is unchanged.
 * M06.S05 gate: quantities already dispatched cannot be cancelled.
 * M06.S06 gate: partial fulfilment is supported; competing drafts are allowed and rechecked at
 * dispatch confirmation (M07).
 */
class OrderIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    private IamClient iam;
    private JsonNode owner;
    private UUID businessId;
    private UUID main;
    private UUID shop;
    private UUID ram;
    private UUID soap;
    private UUID salt;

    @BeforeEach
    void setUp() throws Exception {
        iam = new IamClient(mockMvc, otpSender, invitationSender);
        owner = iam.signIn(OWNER_MOBILE);
        businessId = iam.createBusiness(owner, "Sharma Traders");
        main = create(owner, businessId, "/v1/godowns", "{\"name\":\"Main Godown\"}");
        shop = create(owner, businessId, "/v1/godowns", "{\"name\":\"Shop\"}");
        ram = customer("ram-stores", null);
        // Soap: ₹10.00 a piece, 12 pieces a box. Salt: no price.
        soap = create(owner, businessId, "/v1/products",
                "{\"sku\":\"SOAP-01\",\"name\":\"Lux Soap\",\"baseUnit\":\"PCS\",\"price\":1000}");
        salt = create(owner, businessId, "/v1/products",
                "{\"sku\":\"SALT-1KG\",\"name\":\"Tata Salt\",\"baseUnit\":\"KG\"}");
        mockMvc.perform(put("/v1/products/" + soap + "/unit-conversions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversions\":[{\"unit\":\"BOX\",\"factor\":12}],\"version\":0}"))
                .andExpect(status().isOk());
    }

    // --- M06.S01 create ---------------------------------------------------------------------------

    @Test
    void aDraftPricesLinesWithDiscountTaxAndChargesButMovesNoStock() throws Exception {
        JsonNode order = iam.read(createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\""
                + main + "\",\"requestedDate\":\"2026-11-30\",\"note\":\" phone order \",\"lines\":["
                + "{\"productId\":\"" + soap + "\",\"quantity\":2,\"unit\":\"box\",\"discountPercent\":10,"
                + "\"taxRate\":18},"
                + "{\"productId\":\"" + salt + "\",\"quantity\":3.5,\"unitPrice\":4550,\"taxRate\":5}],"
                + "\"charges\":[{\"label\":\"Freight\",\"amount\":5000}]}")
                .andExpect(status().isCreated()));

        assertThat(order.get("status").asString()).isEqualTo("DRAFT");
        assertThat(order.get("number").isNull()).isTrue();
        assertThat(order.get("revision").asInt()).isZero();
        assertThat(order.get("note").asString()).isEqualTo("phone order");
        assertThat(order.at("/customer/name").asString()).isEqualTo("Ram Stores");
        assertThat(order.get("godownName").asString()).isEqualTo("Main Godown");
        JsonNode soapLine = order.at("/lines/0");
        assertThat(soapLine.get("baseQuantity").asInt()).isEqualTo(24);
        assertThat(soapLine.get("unit").asString()).isEqualTo("BOX");
        assertThat(soapLine.get("unitPrice").isNull()).isTrue();
        // ₹10 × 12 per box: 2 boxes = 24000, 10% off = 2400, 18% tax on 21600 = 3888.
        assertThat(soapLine.get("effectiveUnitPrice").asLong()).isEqualTo(12000);
        assertThat(soapLine.get("amount").asLong()).isEqualTo(24000);
        assertThat(soapLine.get("discount").asLong()).isEqualTo(2400);
        assertThat(soapLine.get("tax").asLong()).isEqualTo(3888);
        assertThat(soapLine.get("total").asLong()).isEqualTo(25488);
        // 3.5 × 4550 = 15925; 5% tax = 796.25, rounded half-up to 796.
        assertThat(order.at("/lines/1/tax").asLong()).isEqualTo(796);
        JsonNode totals = order.get("totals");
        assertThat(totals.get("amount").asLong()).isEqualTo(39925);
        assertThat(totals.get("discount").asLong()).isEqualTo(2400);
        assertThat(totals.get("tax").asLong()).isEqualTo(4684);
        assertThat(totals.get("charges").asLong()).isEqualTo(5000);
        assertThat(totals.get("total").asLong()).isEqualTo(47209);

        assertThat(count("stock_movement")).isZero();
        assertThat(count("stock_balance")).isZero();
        assertThat(count("sales_order_revision")).isZero();
        JsonNode event = iam.read(iam.getScoped(owner, businessId, "/v1/audit-events?resourceType=sales_order"))
                .at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("order.create");
        assertThat(event.at("/changes/lines/after").size()).isEqualTo(2);
    }

    @Test
    void aDraftWithoutAnyPriceHasNoTotalsUntilPriced() throws Exception {
        JsonNode order = iam.read(createOrder(owner, newKey(), orderBody(ram, main, salt, "2"))
                .andExpect(status().isCreated()));
        assertThat(order.get("totals").isNull()).isTrue();
        assertThat(order.at("/lines/0/amount").isNull()).isTrue();

        confirm(owner, id(order), 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("has no price")));
    }

    @Test
    void invalidDraftsAreRejected() throws Exception {
        createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\"" + main + "\",\"lines\":["
                + line(soap, "1") + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("requestedDate is required"));
        createOrder(owner, newKey(), orderBody(ram, main, soap, "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("lines[0]: quantity must be greater than zero"));
        createOrder(owner, newKey(), orderBody(UUID.randomUUID(), main, soap, "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("customerId: customer not found"));
        createOrder(owner, newKey(), orderBody(ram, UUID.randomUUID(), soap, "1"))
                .andExpect(status().isNotFound());
        createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\"" + main
                + "\",\"requestedDate\":\"30-11-2026\",\"lines\":[" + line(soap, "1") + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("requestedDate must be a date like 2026-11-27"));
        createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\"" + main
                + "\",\"requestedDate\":\"2026-11-30\",\"lines\":[" + line(soap, "1") + "," + line(soap, "2") + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("lines[1]: the same product as lines[0]; list each product once"));
        createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\"" + main
                + "\",\"requestedDate\":\"2026-11-30\",\"lines\":[{\"productId\":\"" + soap
                + "\",\"quantity\":1,\"discountPercent\":12.345}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("lines[0]: discountPercent must be between 0 and 100 with at most 2 decimals"));
        createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\"" + main
                + "\",\"requestedDate\":\"2026-11-30\",\"lines\":[" + line(soap, "1")
                + "],\"charges\":[{\"label\":\" \",\"amount\":10}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("charges[0]: label is required"));
        createOrder(owner, newKey(), "{\"customerId\":\"" + ram + "\",\"godownId\":\"" + main
                + "\",\"requestedDate\":\"2026-11-30\",\"lines\":[{\"productId\":\"" + soap
                + "\",\"quantity\":1,\"unit\":\"KG\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no conversion for KG")));

        patchProduct(soap, "{\"status\":\"INACTIVE\",\"version\":1}");
        createOrder(owner, newKey(), orderBody(ram, main, soap, "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
        assertThat(count("sales_order")).isZero();
    }

    @Test
    void aDuplicateCreateReturnsTheOriginalDraft() throws Exception {
        String key = newKey();
        String body = orderBody(ram, main, soap, "5");
        JsonNode first = iam.read(createOrder(owner, key, body).andExpect(status().isCreated()));
        JsonNode replay = iam.read(createOrder(owner, key, body).andExpect(status().isCreated()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("sales_order")).isEqualTo(1);
        createOrder(owner, null, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // --- M06.S02 read -----------------------------------------------------------------------------

    @Test
    void ordersAreListedNewestFirstWithFiltersAndAStableCursor() throws Exception {
        UUID shyam = customer("shyam-kirana", null);
        UUID first = draft(ram, main, soap, "1", "2026-11-01");
        UUID second = draft(shyam, main, soap, "2", "2026-11-10");
        UUID third = draft(ram, shop, soap, "3", "2026-11-20");
        confirm(owner, second, 0).andExpect(status().isOk());

        JsonNode page = list("/v1/orders?size=2");
        assertThat(ids(page)).containsExactly(third.toString(), second.toString());
        UUID fourth = draft(ram, main, soap, "4", "2026-11-25");
        JsonNode next = list("/v1/orders?size=2&cursor=" + page.get("nextCursor").asString());
        assertThat(ids(next)).containsExactly(first.toString());
        assertThat(next.get("nextCursor").isNull()).isTrue();

        assertThat(ids(list("/v1/orders?status=CONFIRMED"))).containsExactly(second.toString());
        assertThat(ids(list("/v1/orders?status=DRAFT,CONFIRMED"))).hasSize(4);
        assertThat(ids(list("/v1/orders?customerId=" + shyam))).containsExactly(second.toString());
        assertThat(ids(list("/v1/orders?godownId=" + shop))).containsExactly(third.toString());
        assertThat(ids(list("/v1/orders?requestedFrom=2026-11-10&requestedTo=2026-11-20")))
                .containsExactly(third.toString(), second.toString());
        assertThat(ids(list("/v1/orders?q=so-0000"))).containsExactly(second.toString());
        JsonNode summary = list("/v1/orders?status=CONFIRMED").at("/items/0");
        assertThat(summary.get("number").asString()).isEqualTo("SO-000001");
        assertThat(summary.at("/customer/name").asString()).isEqualTo("Shyam Kirana");
        assertThat(summary.get("total").asLong()).isEqualTo(2000);
        assertThat(summary.get("lineCount").asInt()).isEqualTo(1);
        assertThat(ids(list("/v1/orders"))).contains(fourth.toString());

        iam.getScoped(owner, businessId, "/v1/orders?status=SHIPPED").andExpect(status().isUnprocessableContent());
        iam.getScoped(owner, businessId, "/v1/orders?cursor=nonsense").andExpect(status().isUnprocessableContent());
        iam.getScoped(owner, businessId, "/v1/orders?requestedFrom=2026-11-20&requestedTo=2026-11-10")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void workersSeeAndActOnlyOnOrdersOfTheirGodowns() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID atMain = draft(ram, main, soap, "1", "2026-11-30");
        UUID atShop = draft(ram, shop, soap, "1", "2026-11-30");

        iam.getScoped(worker, businessId, "/v1/orders").andExpect(status().isForbidden());
        createOrder(worker, newKey(), orderBody(ram, main, soap, "1")).andExpect(status().isForbidden());

        grant(workerId, "[\"ORDER_READ\",\"ORDER_CREATE\",\"ORDER_CONFIRM\"]", 0);
        assignGodowns(workerId, "[\"" + main + "\"]", 1);
        assertThat(ids(iam.read(iam.getScoped(worker, businessId, "/v1/orders").andExpect(status().isOk()))))
                .containsExactly(atMain.toString());
        iam.getScoped(worker, businessId, "/v1/orders/" + atMain).andExpect(status().isOk());
        iam.getScoped(worker, businessId, "/v1/orders/" + atShop).andExpect(status().isNotFound());
        iam.getScoped(worker, businessId, "/v1/orders?godownId=" + shop).andExpect(status().isForbidden());
        confirm(worker, atShop, 0).andExpect(status().isNotFound());
        createOrder(worker, newKey(), orderBody(ram, shop, soap, "1")).andExpect(status().isForbidden());
        JsonNode created = iam.read(createOrder(worker, newKey(), orderBody(ram, main, soap, "1"))
                .andExpect(status().isCreated()));
        assertThat(created.get("createdByMembershipId").asString()).isEqualTo(workerId.toString());
        confirm(worker, atMain, 0).andExpect(status().isOk());
        cancel(worker, atMain, "{\"reason\":\"DUPLICATE\"}").andExpect(status().isForbidden());

        // Another business cannot read or touch the order at all.
        JsonNode stranger = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(stranger, "Gupta Stores");
        iam.getScoped(stranger, otherBusiness, "/v1/orders/" + atMain).andExpect(status().isNotFound());
        assertThat(iam.read(iam.getScoped(stranger, otherBusiness, "/v1/orders")).get("items")).isEmpty();
        iam.getScoped(stranger, businessId, "/v1/orders/" + atMain).andExpect(status().isNotFound());
    }

    // --- M06.S03 edit -----------------------------------------------------------------------------

    @Test
    void draftsAreEditedWithTheCurrentVersionAndStaleEditsConflict() throws Exception {
        UUID order = draft(ram, main, soap, "1", "2026-11-30");
        JsonNode edited = iam.read(patchOrder(order, "{\"version\":0,\"godownId\":\"" + shop
                + "\",\"promisedDate\":\"2026-12-02\",\"lines\":[" + line(salt, "4") + "," + line(soap, "2")
                + "],\"charges\":[{\"label\":\"Loading\",\"amount\":300}]}").andExpect(status().isOk()));
        assertThat(edited.get("version").asLong()).isEqualTo(1);
        assertThat(edited.get("godownId").asString()).isEqualTo(shop.toString());
        assertThat(edited.get("promisedDate").asString()).isEqualTo("2026-12-02");
        assertThat(edited.get("lines")).hasSize(2);
        assertThat(edited.at("/lines/0/sku").asString()).isEqualTo("SALT-1KG");
        assertThat(edited.at("/charges/0/label").asString()).isEqualTo("Loading");

        patchOrder(order, "{\"version\":0,\"note\":\"late\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        patchOrder(order, "{\"version\":1,\"promisedDate\":null,\"colour\":\"red\"}")
                .andExpect(status().isUnprocessableContent());
        patchOrder(order, "{\"version\":1,\"promisedDate\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.promisedDate").isEmpty());
        JsonNode event = iam.read(iam.getScoped(owner, businessId, "/v1/audit-events?resourceType=sales_order"))
                .at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("order.update");
        assertThat(event.at("/changes/promisedDate/before").asString()).isEqualTo("2026-12-02");
    }

    @Test
    void aConfirmedOrderOnlyChangesDatesAndNoteAndNeverItsFulfilledQuantities() throws Exception {
        UUID order = draft(ram, main, soap, "10", "2026-11-30");
        JsonNode confirmed = iam.read(confirm(owner, order, 0).andExpect(status().isOk()));
        long version = confirmed.get("version").asLong();
        String lineId = confirmed.at("/lines/0/id").asString();
        dispatched(lineId, "4");

        patchOrder(order, "{\"version\":" + version + ",\"lines\":[" + line(soap, "2") + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        patchOrder(order, "{\"version\":" + version + ",\"requestedDate\":\"2026-12-01\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        JsonNode edited = iam.read(patchOrder(order, "{\"version\":" + version
                + ",\"promisedDate\":\"2026-12-05\",\"note\":\"after Diwali\"}").andExpect(status().isOk()));
        assertThat(edited.get("revision").asInt()).isEqualTo(2);
        assertThat(edited.get("promisedDate").asString()).isEqualTo("2026-12-05");
        assertThat(edited.at("/lines/0/dispatchedQuantity").asInt()).isEqualTo(4);
        assertThat(edited.at("/lines/0/baseQuantity").asInt()).isEqualTo(10);
        assertThat(revisions(order)).containsExactly("1 CONFIRM", "2 EDIT");
        String snapshot = jdbc.sql("SELECT snapshot ->> 'promisedDate' FROM sales_order_revision"
                        + " WHERE order_id = :id AND revision = 2")
                .param("id", order).query(String.class).single();
        assertThat(snapshot).isEqualTo("2026-12-05");
    }

    // --- M06.S04 confirm --------------------------------------------------------------------------

    @Test
    void confirmationFreezesPricesNumbersTheOrderAndRepeatsIdempotentlyWithoutTouchingStock() throws Exception {
        UUID order = draft(ram, main, soap, "1", "2026-11-30");
        JsonNode confirmed = iam.read(confirm(owner, order, 0).andExpect(status().isOk()));
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("number").asString()).isEqualTo("SO-000001");
        assertThat(confirmed.get("revision").asInt()).isEqualTo(1);
        assertThat(confirmed.at("/lines/0/unitPrice").asLong()).isEqualTo(1000);

        // A later product price change does not reprice the committed order.
        patchProduct(soap, "{\"price\":1500,\"version\":1}");
        JsonNode again = iam.read(confirm(owner, order, 0).andExpect(status().isOk()));
        assertThat(again.get("number").asString()).isEqualTo("SO-000001");
        assertThat(again.get("version")).isEqualTo(confirmed.get("version"));
        assertThat(again.at("/totals/total").asLong()).isEqualTo(1000);
        String key = newKey();
        confirm(owner, order, key, 0).andExpect(status().isOk());
        confirm(owner, order, key, 0).andExpect(status().isOk());
        assertThat(revisions(order)).containsExactly("1 CONFIRM");
        assertThat(count("stock_movement")).isZero();

        UUID second = draft(ram, main, soap, "1", "2026-11-30");
        confirm(owner, second, 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        JsonNode next = iam.read(confirm(owner, second, 0).andExpect(status().isOk()));
        assertThat(next.get("number").asString()).isEqualTo("SO-000002");
        assertThat(next.at("/lines/0/unitPrice").asLong()).isEqualTo(1500);

        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=sales_order")).at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("order.confirm");
        assertThat(event.at("/changes/lines/after/0/priceSource").asString()).isEqualTo("PRODUCT");
    }

    @Test
    void theCreditLimitCoversThisOrderAndOtherOpenOrdersForEveryone() throws Exception {
        UUID limited = customer("limited-co", 10000L);
        UUID first = draft(limited, main, soap, "6", "2026-11-30");
        UUID second = draft(limited, main, soap, "5", "2026-11-30");
        confirm(owner, first, 0).andExpect(status().isOk());
        confirm(owner, second, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));

        // Cancelling part of the first order frees credit.
        JsonNode firstOrder = iam.read(iam.getScoped(owner, businessId, "/v1/orders/" + first));
        cancel(owner, first, "{\"reason\":\"CUSTOMER_REQUEST\",\"lines\":[{\"orderLineId\":\""
                + firstOrder.at("/lines/0/id").asString() + "\",\"quantity\":1}]}").andExpect(status().isOk());
        confirm(owner, second, 0).andExpect(status().isOk());
    }

    @Test
    void concurrentConfirmationsCannotJointlyExceedTheCreditLimit() throws Exception {
        UUID limited = customer("limited-co", 10000L);
        UUID first = draft(limited, main, soap, "6", "2026-11-30");
        UUID second = draft(limited, shop, soap, "6", "2026-11-30");
        AtomicInteger turn = new AtomicInteger();
        List<ResultActions> results = concurrently(2,
                () -> confirm(owner, turn.getAndIncrement() == 0 ? first : second, 0));
        List<Integer> statuses = new ArrayList<>();
        for (ResultActions result : results) {
            statuses.add(result.andReturn().getResponse().getStatus());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM sales_order WHERE status = 'CONFIRMED'").query(Long.class).single())
                .isEqualTo(1);
    }

    // --- M06.S05 cancel ---------------------------------------------------------------------------

    @Test
    void cancellationTakesOnlyUndispatchedQuantitiesAndKeepsHistory() throws Exception {
        UUID order = draft(ram, main, soap, "2", "2026-11-30", "box");
        JsonNode confirmed = iam.read(confirm(owner, order, 0).andExpect(status().isOk()));
        String lineId = confirmed.at("/lines/0/id").asString();
        dispatched(lineId, "10");
        jdbc.sql("UPDATE sales_order SET status = 'PARTIALLY_DISPATCHED' WHERE id = :id").param("id", order).update();

        cancel(owner, order, "{\"reason\":\"OTHER\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("note is required when reason is OTHER"));
        cancel(owner, order, "{\"reason\":\"OUT_OF_STOCK\",\"lines\":[{\"orderLineId\":\"" + lineId
                + "\",\"quantity\":2}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_REMAINING"));

        JsonNode partly = iam.read(cancel(owner, order, "{\"reason\":\"OUT_OF_STOCK\",\"lines\":[{\"orderLineId\":\""
                + lineId + "\",\"quantity\":0.5}]}").andExpect(status().isOk()));
        assertThat(partly.get("status").asString()).isEqualTo("PARTIALLY_DISPATCHED");
        assertThat(partly.at("/lines/0/cancelledQuantity").asInt()).isEqualTo(6);
        assertThat(partly.at("/lines/0/remainingQuantity").asInt()).isEqualTo(8);
        // 18 pieces left on the order at ₹120 a box.
        assertThat(partly.at("/totals/total").asLong()).isEqualTo(18000);
        assertThat(partly.get("revision").asInt()).isEqualTo(2);

        JsonNode closed = iam.read(cancel(owner, order, "{\"reason\":\"CUSTOMER_REQUEST\",\"note\":\"enough\"}")
                .andExpect(status().isOk()));
        assertThat(closed.get("status").asString()).isEqualTo("FULFILLED");
        assertThat(closed.at("/lines/0/dispatchedQuantity").asInt()).isEqualTo(10);
        assertThat(closed.at("/lines/0/cancelledQuantity").asInt()).isEqualTo(14);
        assertThat(closed.get("cancellations")).hasSize(2);
        // 8 pieces are not a whole number of boxes, so the remainder is recorded in pieces.
        assertThat(closed.at("/cancellations/1/lines/0/unit").asString()).isEqualTo("PCS");
        assertThat(closed.at("/cancellations/1/lines/0/quantity").asInt()).isEqualTo(8);
        assertThat(revisions(order)).containsExactly("1 CONFIRM", "2 CANCEL", "3 CANCEL");

        cancel(owner, order, "{\"reason\":\"DUPLICATE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
    }

    @Test
    void aDraftIsCancelledWholeAndCannotBeConfirmedAfterwards() throws Exception {
        UUID order = draft(ram, main, soap, "3", "2026-11-30");
        cancel(owner, order, "{\"reason\":\"DUPLICATE\",\"lines\":[{\"orderLineId\":\"" + UUID.randomUUID()
                + "\",\"quantity\":1}]}").andExpect(status().isUnprocessableContent());
        JsonNode cancelled = iam.read(cancel(owner, order, "{\"reason\":\"DUPLICATE\"}").andExpect(status().isOk()));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("revision").asInt()).isZero();
        assertThat(cancelled.at("/totals/total").asLong()).isZero();
        confirm(owner, order, cancelled.get("version").asLong())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        assertThat(count("sales_order_revision")).isZero();
    }

    // --- M06.S06 dispatch drafts ------------------------------------------------------------------

    @Test
    void dispatchDraftsFulfilPartiallyAndCompetingDraftsAreAllowedWithoutStockEffect() throws Exception {
        UUID order = draft(ram, main, soap, "2", "2026-11-30", "box");
        dispatchDraft(order, newKey(), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        JsonNode confirmed = iam.read(confirm(owner, order, 0).andExpect(status().isOk()));
        String lineId = confirmed.at("/lines/0/id").asString();

        JsonNode partial = iam.read(dispatchDraft(order, newKey(), "{\"note\":\"first truck\",\"lines\":["
                + "{\"orderLineId\":\"" + lineId + "\",\"quantity\":1}]}").andExpect(status().isCreated()));
        assertThat(partial.get("status").asString()).isEqualTo("DRAFT");
        assertThat(partial.get("orderId").asString()).isEqualTo(order.toString());
        assertThat(partial.get("godownId").asString()).isEqualTo(main.toString());
        assertThat(partial.at("/lines/0/orderLineId").asString()).isEqualTo(lineId);
        assertThat(partial.at("/lines/0/unit").asString()).isEqualTo("BOX");
        assertThat(partial.at("/lines/0/baseQuantity").asInt()).isEqualTo(12);

        // A competing draft for everything remaining is allowed; dispatch confirmation rechecks.
        String key = newKey();
        JsonNode everything = iam.read(dispatchDraft(order, key, "{}").andExpect(status().isCreated()));
        assertThat(everything.at("/lines/0/quantity").asInt()).isEqualTo(2);
        JsonNode replay = iam.read(dispatchDraft(order, key, "{}").andExpect(status().isCreated()));
        assertThat(replay.get("id")).isEqualTo(everything.get("id"));

        dispatchDraft(order, newKey(), "{\"lines\":[{\"orderLineId\":\"" + lineId + "\",\"quantity\":3}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_REMAINING"));
        dispatchDraft(order, newKey(), "{\"lines\":[{\"orderLineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}")
                .andExpect(status().isUnprocessableContent());

        assertThat(count("dispatch")).isEqualTo(2);
        assertThat(count("stock_movement")).isZero();
        JsonNode read = iam.read(iam.getScoped(owner, businessId, "/v1/orders/" + order));
        assertThat(read.get("dispatches")).hasSize(2);
        assertThat(read.at("/lines/0/remainingQuantity").asInt()).isEqualTo(24);
        JsonNode event = iam.read(iam.getScoped(owner, businessId, "/v1/audit-events?resourceType=dispatch"))
                .at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("dispatch.create");

        // After part was dispatched and the rest cancelled, nothing is left to draft.
        dispatched(lineId, "12");
        cancel(owner, order, "{\"reason\":\"CUSTOMER_REQUEST\"}").andExpect(status().isOk());
        dispatchDraft(order, newKey(), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
    }

    @Test
    void dispatchDraftsNeedTheDispatchPermission() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        UUID order = draft(ram, main, soap, "2", "2026-11-30");
        confirm(owner, order, 0).andExpect(status().isOk());
        grant(workerId, "[\"ORDER_READ\"]", 0);
        assignGodowns(workerId, "[\"" + main + "\"]", 1);
        dispatchDraft(worker, order, newKey(), "{}").andExpect(status().isForbidden());
        grant(workerId, "[\"ORDER_READ\",\"DISPATCH_CREATE\"]", 2);
        dispatchDraft(worker, order, newKey(), "{}").andExpect(status().isCreated());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private UUID draft(UUID customer, UUID godown, UUID product, String quantity, String requestedDate)
            throws Exception {
        return draft(customer, godown, product, quantity, requestedDate, null);
    }

    private UUID draft(UUID customer, UUID godown, UUID product, String quantity, String requestedDate, String unit)
            throws Exception {
        return id(iam.read(createOrder(owner, newKey(), "{\"customerId\":\"" + customer + "\",\"godownId\":\""
                + godown + "\",\"requestedDate\":\"" + requestedDate + "\",\"lines\":[{\"productId\":\"" + product
                + "\",\"quantity\":" + quantity + (unit == null ? "" : ",\"unit\":\"" + unit + "\"") + "}]}")
                .andExpect(status().isCreated())));
    }

    private static String orderBody(UUID customer, UUID godown, UUID product, String quantity) {
        return "{\"customerId\":\"" + customer + "\",\"godownId\":\"" + godown
                + "\",\"requestedDate\":\"2026-11-30\",\"lines\":[" + line(product, quantity) + "]}";
    }

    private static String line(UUID productId, String quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private ResultActions createOrder(JsonNode caller, String key, String body) throws Exception {
        var request = post("/v1/orders")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private ResultActions patchOrder(UUID order, String body) throws Exception {
        return mockMvc.perform(patch("/v1/orders/" + order)
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions confirm(JsonNode caller, UUID order, long version) throws Exception {
        return confirm(caller, order, newKey(), version);
    }

    private ResultActions confirm(JsonNode caller, UUID order, String key, long version) throws Exception {
        return action(caller, order, "/confirm", key, "{\"version\":" + version + "}");
    }

    private ResultActions cancel(JsonNode caller, UUID order, String body) throws Exception {
        return action(caller, order, "/cancel", newKey(), body);
    }

    private ResultActions dispatchDraft(UUID order, String key, String body) throws Exception {
        return dispatchDraft(owner, order, key, body);
    }

    private ResultActions dispatchDraft(JsonNode caller, UUID order, String key, String body) throws Exception {
        return action(caller, order, "/dispatch-drafts", key, body);
    }

    private ResultActions action(JsonNode caller, UUID order, String path, String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/orders/" + order + path)
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode list(String path) throws Exception {
        return iam.read(iam.getScoped(owner, businessId, path).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private List<String> revisions(UUID order) {
        return jdbc.sql("SELECT revision || ' ' || change FROM sales_order_revision WHERE order_id = :id"
                        + " ORDER BY revision")
                .param("id", order)
                .query(String.class)
                .list();
    }

    /** Stands in for M07 dispatch confirmation, which is not built yet. */
    private void dispatched(String lineId, String baseQuantity) {
        jdbc.sql("UPDATE sales_order_line SET dispatched_quantity = CAST(:quantity AS numeric) WHERE id = :id")
                .param("quantity", baseQuantity)
                .param("id", UUID.fromString(lineId))
                .update();
    }

    private UUID customer(String username, Long creditLimit) throws Exception {
        String name = username.substring(0, 1).toUpperCase() + username.substring(1, username.indexOf('-')) + " "
                + username.substring(username.indexOf('-') + 1, username.indexOf('-') + 2).toUpperCase()
                + username.substring(username.indexOf('-') + 2);
        return create(owner, businessId, "/v1/customers", "{\"username\":\"" + username + "\",\"name\":\"" + name
                + "\"" + (creditLimit == null ? "" : ",\"creditLimit\":" + creditLimit) + "}");
    }

    private void patchProduct(UUID product, String body) throws Exception {
        mockMvc.perform(patch("/v1/products/" + product)
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private UUID create(JsonNode caller, UUID business, String path, String json) throws Exception {
        return id(iam.read(mockMvc.perform(post(path)
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated())));
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

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
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
