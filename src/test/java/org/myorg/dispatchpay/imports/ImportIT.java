package org.myorg.dispatchpay.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.imports.ImportRepository.JobRow;
import org.myorg.dispatchpay.platform.storage.ObjectStorage;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * M04.S01 gate: unsafe file types, excessive size and unauthorized import types are rejected.
 * M04.S02 gate: malformed rows have stable row-level errors; preview changes nothing.
 * M04.S03 gate: retries do not duplicate data; failure is clearly reported and leaves nothing behind.
 * M04.S04 gate: another tenant cannot obtain the file; expired links fail.
 */
class ImportIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    private static final String PRODUCTS_CSV = """
            sku,name,baseUnit,brand,packSize,price,aliases,conversions
            SOAP-01,Lux Soap,PCS,Lux,100 g,2800,lux|lux sabun,BOX=12|CARTON=144
            SALT-1KG,Tata Salt,kg,Tata,1 kg,2500,,G=0.001
            """;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ImportWorker worker;

    @Autowired
    private ImportProcessor processor;

    @Autowired
    private ObjectStorage storage;

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

    // --- M04.S01 create ---------------------------------------------------------------------------

    @Test
    void createIssuesAPrivateUploadUrlBoundToTheDeclaredFile() throws Exception {
        byte[] content = bytes(PRODUCTS_CSV);
        JsonNode created = iam.read(create(owner, "products", "products.csv", content.length, newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("PRODUCTS"))
                .andExpect(jsonPath("$.status").value("AWAITING_UPLOAD"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.headers.content-type").value("text/csv"))
                .andExpect(jsonPath("$.upload.headers.content-length").value(String.valueOf(content.length))));
        JsonNode upload = created.get("upload");
        assertThat(upload.get("url").asString()).contains("imports/" + businessId);

        // The bucket is private: the object key alone gives no access.
        String unsigned = upload.get("url").asString().replaceAll("\\?.*", "");
        assertThat(send(HttpRequest.newBuilder(URI.create(unsigned)).GET().build()).statusCode()).isEqualTo(403);
        // The URL accepts only the declared size.
        byte[] larger = bytes(PRODUCTS_CSV + "EXTRA-1,Extra,PCS,,,,,\n");
        assertThat(upload(upload, larger)).isNotEqualTo(200);
        assertThat(upload(upload, content)).isEqualTo(200);
    }

    @Test
    void unsafeFileNamesAndSizesAreRejected() throws Exception {
        for (String fileName : List.of("products.xlsx", "products.exe", "../products.csv", "a\\b.csv", ".csv",
                "x".repeat(252) + ".csv")) {
            create(owner, "PRODUCTS", fileName, 100, newKey())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        create(owner, "PRODUCTS", "products.csv", 0, newKey()).andExpect(status().isUnprocessableContent());
        create(owner, "PRODUCTS", "products.csv", 2 * 1024 * 1024 + 1, newKey())
                .andExpect(status().isUnprocessableContent());
        create(owner, "INVOICES", "invoices.csv", 100, newKey()).andExpect(status().isUnprocessableContent());
        create(owner, "PRODUCTS", "products.csv", 2 * 1024 * 1024, newKey()).andExpect(status().isCreated());
        assertThat(count("import_job")).isEqualTo(1);
    }

    @Test
    void contentThatIsNotCsvTextIsRejectedAtValidation() throws Exception {
        byte[] binary = {'P', 'K', 3, 4, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFE, 0, 1};
        UUID id = uploaded(owner, "PRODUCTS", binary);
        validate(owner, id, newKey()).andExpect(status().isAccepted());
        worker.processPending();

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("INVALID");
        assertThat(job.at("/failure/code").asString()).isIn("FILE_ENCODING", "FILE_NOT_CSV");
        assertThat(count("import_row")).isZero();
    }

    @Test
    void importTypesNeedImportAndTypePermissions() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);

        create(worker, "PRODUCTS", "p.csv", 10, newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        grant(workerId, "[\"IMPORT_MANAGE\"]", 0);
        create(worker, "PRODUCTS", "p.csv", 10, newKey()).andExpect(status().isForbidden());
        grant(workerId, "[\"PRODUCT_MANAGE\"]", 1);
        create(worker, "PRODUCTS", "p.csv", 10, newKey()).andExpect(status().isForbidden());

        grant(workerId, "[\"IMPORT_MANAGE\",\"PRODUCT_MANAGE\"]", 2);
        UUID products = id(create(worker, "PRODUCTS", "p.csv", 10, newKey()).andExpect(status().isCreated()));
        create(worker, "CUSTOMERS", "c.csv", 10, newKey()).andExpect(status().isForbidden());
        create(worker, "OPENING_STOCK", "s.csv", 10, newKey()).andExpect(status().isForbidden());

        // An import of a type the member may not handle cannot be read either.
        UUID customers = id(create(owner, "CUSTOMERS", "c.csv", 10, newKey()));
        iam.getScoped(worker, businessId, "/v1/imports/" + customers).andExpect(status().isForbidden());
        iam.getScoped(worker, businessId, "/v1/imports/" + products).andExpect(status().isOk());
    }

    // --- M04.S02 validate -------------------------------------------------------------------------

    @Test
    void validationIsRefusedUntilTheFileIsUploaded() throws Exception {
        UUID id = id(create(owner, "PRODUCTS", "products.csv", 100, newKey()));

        validate(owner, id, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_UPLOADED"));
        assertThat(get(owner, id, "").get("status").asString()).isEqualTo("AWAITING_UPLOAD");
    }

    @Test
    void validationPreviewsRowsWithoutChangingAnything() throws Exception {
        UUID id = uploaded(owner, "PRODUCTS", bytes(PRODUCTS_CSV));

        validate(owner, id, newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("VALIDATING"))
                .andExpect(jsonPath("$.validatedByMembershipId").value(ownerMembership.toString()));
        assertThat(worker.processPending()).isEqualTo(1);

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(job.get("totalRows").asInt()).isEqualTo(2);
        assertThat(job.get("errorRows").asInt()).isZero();
        assertThat(job.get("commitAvailable").asBoolean()).isTrue();
        assertThat(job.get("columns")).hasSize(8);
        JsonNode first = job.at("/rows/items/0");
        assertThat(first.get("rowNumber").asInt()).isEqualTo(2);
        assertThat(first.at("/values/sku").asString()).isEqualTo("SOAP-01");
        assertThat(first.get("errors")).isEmpty();
        assertThat(first.get("resourceId").isNull()).isTrue();

        assertThat(count("product")).isZero();
        assertThat(count("product_alias")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_type = 'product'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void malformedRowsHaveStableRowLevelErrors() throws Exception {
        String csv = """
                sku,name,baseUnit,price,aliases,conversions
                OK-1,Fine Product,PCS,100,,
                ,Missing Sku,PCS,,,
                BAD SKU,Bad Sku,PCS,,,
                OK-2,Bad Unit,LITRE,,,
                OK-3,Bad Price,PCS,12.50,,
                OK-4,Bad Conversion,KG,,,G=2|BOX=0
                ok-1,Duplicate,PCS,,a|A,
                OK-5,Too Few
                """;
        UUID first = validated(owner, "PRODUCTS", bytes(csv));
        UUID second = validated(owner, "PRODUCTS", bytes(csv));

        JsonNode job = get(owner, first, "?errorsOnly=true&size=50");
        assertThat(job.get("status").asString()).isEqualTo("INVALID");
        assertThat(job.at("/failure/code").asString()).isEqualTo("ROW_ERRORS");
        assertThat(job.get("totalRows").asInt()).isEqualTo(8);
        assertThat(job.get("errorRows").asInt()).isEqualTo(7);
        assertThat(job.get("commitAvailable").asBoolean()).isFalse();
        assertThat(errorCodes(job, 3)).containsExactly("sku:REQUIRED");
        assertThat(errorCodes(job, 4)).containsExactly("sku:INVALID_FORMAT");
        assertThat(errorCodes(job, 5)).containsExactly("baseUnit:INVALID_UNIT");
        assertThat(errorCodes(job, 6)).containsExactly("price:INVALID_NUMBER");
        assertThat(errorCodes(job, 7)).containsExactly("conversions:INVALID_CONVERSION",
                "conversions:INVALID_CONVERSION");
        assertThat(errorCodes(job, 8)).containsExactly("aliases:DUPLICATE_ALIAS", "sku:DUPLICATE_IN_FILE");
        assertThat(errorCodes(job, 9)).contains("null:COLUMN_COUNT", "baseUnit:REQUIRED");

        // The same file gets exactly the same errors.
        assertThat(get(owner, second, "?errorsOnly=true&size=50").get("rows"))
                .isEqualTo(job.get("rows"));
        commit(owner, first, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(count("product")).isZero();
    }

    @Test
    void headerProblemsRejectTheWholeFile() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes("sku,name,colour\nA,Apple,red\n"));

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("INVALID");
        assertThat(job.at("/failure/code").asString()).isEqualTo("HEADER_INVALID");
        assertThat(job.at("/failure/message").asString()).contains("colour");
        assertThat(job.at("/rows/totalElements").asLong()).isZero();
    }

    @Test
    void validationStopsWhenTheMemberLosesAccessBeforeTheWorkerRuns() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        grant(workerId, "[\"IMPORT_MANAGE\",\"PRODUCT_MANAGE\"]", 0);
        UUID id = uploaded(worker, "PRODUCTS", bytes(PRODUCTS_CSV));
        validate(worker, id, newKey()).andExpect(status().isAccepted());

        grant(workerId, "[\"IMPORT_MANAGE\"]", 1);
        this.worker.processPending();

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("INVALID");
        assertThat(job.at("/failure/code").asString()).isEqualTo("ACCESS_REVOKED");
    }

    // --- M04.S03 commit ---------------------------------------------------------------------------

    @Test
    void commitCreatesEveryRowAttributedToTheImport() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes(PRODUCTS_CSV));

        commit(owner, id, newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMMITTING"))
                .andExpect(jsonPath("$.committedByMembershipId").value(ownerMembership.toString()));
        worker.processPending();

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(job.get("committedAt").isNull()).isFalse();
        UUID soap = UUID.fromString(job.at("/rows/items/0/resourceId").asString());
        JsonNode product = iam.read(iam.getScoped(owner, businessId, "/v1/products/" + soap));
        assertThat(product.get("sku").asString()).isEqualTo("SOAP-01");
        assertThat(product.get("baseUnit").asString()).isEqualTo("PCS");
        assertThat(product.get("price").asLong()).isEqualTo(2800);
        assertThat(product.get("packSize").asString()).isEqualTo("100 g");
        assertThat(product.get("aliases")).hasSize(2);
        assertThat(product.at("/aliases/0/approvedByMembershipId").asString()).isEqualTo(ownerMembership.toString());
        assertThat(product.at("/conversions/0/unit").asString()).isEqualTo("BOX");
        assertThat(product.at("/conversions/0/factor").asInt()).isEqualTo(12);
        assertThat(count("product")).isEqualTo(2);

        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=product&resourceId=" + soap)).at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("product.create");
        assertThat(event.get("source").asString()).isEqualTo("IMPORT");
        assertThat(event.get("actorType").asString()).isEqualTo("SYSTEM");
        assertThat(event.get("actorMembershipId").isNull()).isTrue();
        assertThat(event.get("initiatorMembershipId").asString()).isEqualTo(ownerMembership.toString());
        JsonNode history = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=import&resourceId=" + id)).get("items");
        assertThat(history).extracting(item -> item.get("action").asString()).containsExactly(
                "import.commit.finish", "import.commit", "import.validation.finish", "import.validate",
                "import.create");
    }

    @Test
    void retriedCommitsDoNotDuplicateData() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes(PRODUCTS_CSV));
        String key = newKey();
        commit(owner, id, key).andExpect(status().isAccepted());
        commit(owner, id, key)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        commit(owner, id, newKey()).andExpect(status().isConflict());

        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(worker.processPending()).isZero();
        commit(owner, id, newKey()).andExpect(status().isConflict());
        assertThat(count("product")).isEqualTo(2);
        assertThat(count("product_alias")).isEqualTo(2);
    }

    @Test
    void aCrashedWorkersStepIsRetriedAndTheStaleWorkerCannotFinishIt() throws Exception {
        JobRow stale = claimCommitting();
        UUID id = stale.id();
        // The first worker crashed: its lease runs out and another worker takes over.
        jdbc.sql("UPDATE import_job SET lease_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", id).update();
        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(count("product")).isEqualTo(2);

        // The first worker wakes up: it no longer holds the lease, so nothing happens twice.
        processor.commit(stale);
        assertThat(count("product")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM import_job WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("COMMITTED");
    }

    @Test
    void commitFailsWithoutWritingAnythingWhenDataChangedSinceValidation() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes(PRODUCTS_CSV));
        mockMvc.perform(post("/v1/products")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"salt-1kg\",\"name\":\"Created Meanwhile\",\"baseUnit\":\"KG\"}"))
                .andExpect(status().isCreated());

        commit(owner, id, newKey()).andExpect(status().isAccepted());
        worker.processPending();

        JsonNode job = get(owner, id, "?errorsOnly=true");
        assertThat(job.get("status").asString()).isEqualTo("FAILED");
        assertThat(job.at("/failure/code").asString()).isEqualTo("COMMIT_CONFLICT");
        assertThat(job.get("errorRows").asInt()).isEqualTo(1);
        assertThat(errorCodes(job, 3)).containsExactly("sku:ALREADY_EXISTS");
        // All or nothing: the valid row was not created either.
        assertThat(count("product")).isEqualTo(1);
        assertThat(iam.read(iam.getScoped(owner, businessId, "/v1/imports/" + id + "/errors")
                .andExpect(status().isOk())).get("url").asString()).contains("errors-failed.csv");
    }

    @Test
    void customersImportWithAliasesAndCredit() throws Exception {
        String csv = """
                username,name,contactPerson,phone,address,paymentTermsDays,creditLimit,aliases
                Sharma-Traders,Sharma Traders,Ravi,+91 98765 43210,Main Rd,30,5000000,sharma ji|sharmaji
                gupta,Gupta Stores,,,,,,
                """;
        UUID id = validated(owner, "CUSTOMERS", bytes(csv));
        commit(owner, id, newKey()).andExpect(status().isAccepted());
        worker.processPending();

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("COMMITTED");
        JsonNode customer = iam.read(iam.getScoped(owner, businessId,
                "/v1/customers/" + job.at("/rows/items/0/resourceId").asString()));
        assertThat(customer.get("username").asString()).isEqualTo("sharma-traders");
        assertThat(customer.at("/credit/paymentTermsDays").asInt()).isEqualTo(30);
        assertThat(customer.at("/credit/creditLimit").asLong()).isEqualTo(5_000_000);
        assertThat(customer.get("aliases")).hasSize(2);
        assertThat(customer.get("createdByMembershipId").asString()).isEqualTo(ownerMembership.toString());
        assertThat(count("customer")).isEqualTo(2);
    }

    @Test
    void customerCreditColumnsNeedTheReceivablesPermission() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        grant(workerId, "[\"IMPORT_MANAGE\",\"CUSTOMER_MANAGE\"]", 0);
        String csv = """
                username,name,paymentTermsDays,creditLimit
                sharma,Sharma Traders,30,
                gupta,Gupta Stores,,
                """;
        UUID id = validated(worker, "CUSTOMERS", bytes(csv));

        JsonNode job = get(worker, id, "?errorsOnly=true");
        assertThat(job.get("errorRows").asInt()).isEqualTo(1);
        assertThat(errorCodes(job, 2)).containsExactly("paymentTermsDays:PERMISSION_REQUIRED");
    }

    @Test
    void openingStockRowsAreValidatedAgainstCatalogGodownsAndScope() throws Exception {
        UUID main = godown("Main Godown");
        godown("Shop");
        UUID soap = product("{\"sku\":\"SOAP-01\",\"name\":\"Lux Soap\",\"baseUnit\":\"PCS\"}");
        product("{\"sku\":\"OIL-1L\",\"name\":\"Sunflower Oil\",\"baseUnit\":\"L\"}");
        product("{\"sku\":\"SALT-1KG\",\"name\":\"Tata Salt\",\"baseUnit\":\"KG\"}");
        mockMvc.perform(put("/v1/products/" + soap + "/unit-conversions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversions\":[{\"unit\":\"BOX\",\"factor\":12}],\"version\":0}"))
                .andExpect(status().isOk());
        String csv = """
                sku,godown,quantity,unit
                SOAP-01,main godown,24,BOX
                SOAP-01,Shop,10,
                SOAP-01,Shop,5,PCS
                SOAP-01,Annex,1,
                NOPE,Main Godown,1,
                OIL-1L,Main Godown,0,
                SALT-1KG,Main Godown,1,BAG
                """;
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        grant(workerId, "[\"IMPORT_MANAGE\",\"STOCK_INWARD\"]", 0);
        assignGodowns(workerId, "[\"" + main + "\"]", 1);

        UUID id = validated(worker, "OPENING_STOCK", bytes(csv));
        JsonNode job = get(worker, id, "?errorsOnly=true");
        assertThat(errorCodes(job, 2)).isEmpty();
        assertThat(errorCodes(job, 3)).containsExactly("godown:GODOWN_NOT_IN_SCOPE");
        assertThat(errorCodes(job, 4)).containsExactly("godown:GODOWN_NOT_IN_SCOPE", "sku:DUPLICATE_IN_FILE");
        assertThat(errorCodes(job, 5)).containsExactly("godown:UNKNOWN_GODOWN");
        assertThat(errorCodes(job, 6)).containsExactly("sku:UNKNOWN_PRODUCT");
        assertThat(errorCodes(job, 7)).containsExactly("quantity:OUT_OF_RANGE");
        assertThat(errorCodes(job, 8)).containsExactly("unit:UNIT_NOT_CONVERTIBLE");
    }

    @Test
    void openingStockCommitPostsOneOpeningMovementPerRow() throws Exception {
        UUID main = godown("Main Godown");
        UUID shop = godown("Shop");
        UUID soap = product("{\"sku\":\"SOAP-01\",\"name\":\"Lux Soap\",\"baseUnit\":\"PCS\"}");
        mockMvc.perform(put("/v1/products/" + soap + "/unit-conversions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversions\":[{\"unit\":\"BOX\",\"factor\":12}],\"version\":0}"))
                .andExpect(status().isOk());

        UUID id = validated(owner, "OPENING_STOCK",
                bytes("sku,godown,quantity,unit\nSOAP-01,Main Godown,2,BOX\nSOAP-01,shop,2.5,\n"));
        assertThat(get(owner, id, "").get("commitAvailable").asBoolean()).isTrue();
        commit(owner, id, newKey()).andExpect(status().isAccepted());
        worker.processPending();

        JsonNode job = get(owner, id, "");
        assertThat(job.get("status").asString()).isEqualTo("COMMITTED");
        UUID movement = UUID.fromString(job.at("/rows/items/0/resourceId").asString());
        assertThat(jdbc.sql("""
                        SELECT type || ' ' || quantity || ' ' || entered_quantity || ' ' || entered_unit || ' '
                               || source_type || ' ' || source_line || ' ' || actor_type
                        FROM stock_movement WHERE id = :id AND source_id = :importId
                        """)
                .param("id", movement).param("importId", id).query(String.class).single())
                .isEqualTo("OPENING 24.000000 2.000000 BOX IMPORT 2 SYSTEM");
        assertThat(balance(main, soap)).isEqualByComparingTo("24");
        assertThat(balance(shop, soap)).isEqualByComparingTo("2.5");
        JsonNode event = iam.read(iam.getScoped(owner, businessId,
                "/v1/audit-events?resourceType=stock_movement&resourceId=" + movement)).at("/items/0");
        assertThat(event.get("action").asString()).isEqualTo("stock.opening");
        assertThat(event.get("source").asString()).isEqualTo("IMPORT");
        assertThat(event.get("actorType").asString()).isEqualTo("SYSTEM");
        assertThat(event.get("initiatorMembershipId").asString()).isEqualTo(ownerMembership.toString());

        // Opening stock is only for pairs without movements: a second opening import is refused.
        UUID again = validated(owner, "OPENING_STOCK", bytes("sku,godown,quantity\nSOAP-01,Main Godown,1\n"));
        assertThat(errorCodes(get(owner, again, "?errorsOnly=true"), 2)).containsExactly("null:STOCK_EXISTS");
    }

    @Test
    void openingStockCommitFailsWholeWhenAPairGainedStockSinceValidation() throws Exception {
        UUID main = godown("Main Godown");
        UUID soap = product("{\"sku\":\"SOAP-01\",\"name\":\"Lux Soap\",\"baseUnit\":\"PCS\"}");
        product("{\"sku\":\"OIL-1L\",\"name\":\"Sunflower Oil\",\"baseUnit\":\"L\"}");
        UUID id = validated(owner, "OPENING_STOCK",
                bytes("sku,godown,quantity\nOIL-1L,Main Godown,5\nSOAP-01,Main Godown,10\n"));
        mockMvc.perform(post("/v1/stock/inwards")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"godownId\":\"" + main + "\",\"lines\":[{\"productId\":\"" + soap
                                + "\",\"quantity\":3}]}"))
                .andExpect(status().isCreated());

        commit(owner, id, newKey()).andExpect(status().isAccepted());
        worker.processPending();

        JsonNode job = get(owner, id, "?errorsOnly=true");
        assertThat(job.get("status").asString()).isEqualTo("FAILED");
        assertThat(job.at("/failure/code").asString()).isEqualTo("COMMIT_CONFLICT");
        assertThat(errorCodes(job, 3)).containsExactly("null:STOCK_EXISTS");
        assertThat(count("stock_movement")).isEqualTo(1);
    }

    private BigDecimal balance(UUID godownId, UUID productId) {
        return jdbc.sql("SELECT quantity FROM stock_balance WHERE godown_id = :godownId AND product_id = :productId")
                .param("godownId", godownId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .single();
    }

    // --- M04.S04 error report ---------------------------------------------------------------------

    @Test
    void errorReportIsASanitizedShortLivedDownload() throws Exception {
        String csv = "sku,name,baseUnit\n\"=HYPERLINK(\"\"http://evil\"\")\",Evil,PCS\nOK-1,Fine,PCS\n";
        UUID id = validated(owner, "PRODUCTS", bytes(csv));

        JsonNode report = iam.read(iam.getScoped(owner, businessId, "/v1/imports/" + id + "/errors")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("import-" + id + "-errors.csv")));
        assertThat(report.get("expiresAt").asString()).isNotBlank();
        HttpResponse<String> download = send(HttpRequest.newBuilder(URI.create(report.get("url").asString()))
                .GET().build());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        List<Csv.Record> lines = Csv.parse(download.body().substring(1));
        assertThat(lines.get(0).cells()).containsExactly("row", "sku", "name", "baseUnit", "errors");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).cells().get(0)).isEqualTo("2");
        assertThat(lines.get(1).cells().get(1)).isEqualTo("'=HYPERLINK(\"http://evil\")");
        assertThat(lines.get(1).cells().get(4)).contains("[INVALID_FORMAT]");

        // A second request reuses the stored report.
        String key = jdbc.sql("SELECT error_object_key FROM import_job WHERE id = :id").param("id", id)
                .query(String.class).single();
        iam.getScoped(owner, businessId, "/v1/imports/" + id + "/errors").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT error_object_key FROM import_job WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo(key);
    }

    @Test
    void expiredLinksFail() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes("sku,name,baseUnit\n,Missing,PCS\n"));
        iam.getScoped(owner, businessId, "/v1/imports/" + id + "/errors").andExpect(status().isOk());
        String key = jdbc.sql("SELECT error_object_key FROM import_job WHERE id = :id").param("id", id)
                .query(String.class).single();

        ObjectStorage.PresignedUrl link = storage.presignDownload(key, "errors.csv", Duration.ofSeconds(1));
        Thread.sleep(2_500);
        assertThat(send(HttpRequest.newBuilder(URI.create(link.url())).GET().build()).statusCode()).isEqualTo(403);
    }

    @Test
    void anotherTenantCannotObtainTheImportOrItsFiles() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes("sku,name,baseUnit\n,Secret Product,PCS\n"));
        JsonNode other = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(other, "Other Shop");

        for (String path : List.of("", "/errors")) {
            String body = iam.getScoped(other, otherBusiness, "/v1/imports/" + id + path)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Secret Product");
            iam.getScoped(other, businessId, "/v1/imports/" + id + path).andExpect(status().isNotFound());
        }
        commit(other, otherBusiness, id, newKey()).andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/imports/" + id + "/validate")
                        .header("Authorization", bearer(other))
                        .header("X-Business-Id", otherBusiness.toString())
                        .header("Idempotency-Key", newKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void errorReportOnlyExistsForRowErrors() throws Exception {
        UUID valid = validated(owner, "PRODUCTS", bytes(PRODUCTS_CSV));
        iam.getScoped(owner, businessId, "/v1/imports/" + valid + "/errors")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private ResultActions create(JsonNode caller, String type, String fileName, long size, String key)
            throws Exception {
        return mockMvc.perform(post("/v1/imports")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"fileName\":\"" + fileName.replace("\\", "\\\\")
                        + "\",\"sizeBytes\":" + size + "}"));
    }

    /** Creates an import and uploads {@code content} to its URL. */
    private UUID uploaded(JsonNode caller, String type, byte[] content) throws Exception {
        JsonNode created = iam.read(create(caller, type, "file.csv", content.length, newKey())
                .andExpect(status().isCreated()));
        assertThat(upload(created.get("upload"), content)).isEqualTo(200);
        return UUID.fromString(created.get("id").asString());
    }

    /** Uploads, validates and runs the worker. */
    private UUID validated(JsonNode caller, String type, byte[] content) throws Exception {
        UUID id = uploaded(caller, type, content);
        validate(caller, id, newKey()).andExpect(status().isAccepted());
        worker.processPending();
        return id;
    }

    /** A validated products import whose commit was claimed by a worker that then crashed. */
    private JobRow claimCommitting() throws Exception {
        UUID id = validated(owner, "PRODUCTS", bytes(PRODUCTS_CSV));
        commit(owner, id, newKey()).andExpect(status().isAccepted());
        JobRow claimed = processor.claimNext().orElseThrow();
        assertThat(claimed.id()).isEqualTo(id);
        return claimed;
    }

    /** Sends the file to the pre-signed URL with its signed headers; returns the storage's status. */
    private int upload(JsonNode upload, byte[] content) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.get("url").asString()))
                .method(upload.get("method").asString(), HttpRequest.BodyPublishers.ofByteArray(content));
        upload.get("headers").properties().forEach(entry -> {
            // The HTTP client sets Content-Length from the body itself.
            if (!entry.getKey().equalsIgnoreCase("Content-Length")) {
                request.header(entry.getKey(), entry.getValue().asString());
            }
        });
        return send(request.build()).statusCode();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private ResultActions validate(JsonNode caller, UUID id, String key) throws Exception {
        return mockMvc.perform(post("/v1/imports/" + id + "/validate")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", key));
    }

    private ResultActions commit(JsonNode caller, UUID id, String key) throws Exception {
        return commit(caller, businessId, id, key);
    }

    private ResultActions commit(JsonNode caller, UUID business, UUID id, String key) throws Exception {
        return mockMvc.perform(post("/v1/imports/" + id + "/commit")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", business.toString())
                .header("Idempotency-Key", key));
    }

    private JsonNode get(JsonNode caller, UUID id, String query) throws Exception {
        return iam.read(iam.getScoped(caller, businessId, "/v1/imports/" + id + query).andExpect(status().isOk()));
    }

    /** {@code column:CODE} of each error of row {@code rowNumber} in the job's row page. */
    private static List<String> errorCodes(JsonNode job, int rowNumber) {
        List<String> codes = new ArrayList<>();
        job.at("/rows/items").forEach(row -> {
            if (row.get("rowNumber").asInt() == rowNumber) {
                row.get("errors").forEach(error -> codes.add(
                        (error.get("column").isNull() ? "null" : error.get("column").asString()) + ":"
                                + error.get("code").asString()));
            }
        });
        return codes;
    }

    private UUID godown(String name) throws Exception {
        return id(mockMvc.perform(post("/v1/godowns")
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()));
    }

    private UUID product(String json) throws Exception {
        return id(mockMvc.perform(post("/v1/products")
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
