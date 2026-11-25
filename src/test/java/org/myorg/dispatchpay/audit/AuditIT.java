package org.myorg.dispatchpay.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.myorg.dispatchpay.support.IamClient.bearer;
import static org.myorg.dispatchpay.support.IamClient.newKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.IamClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * M02.S01 gate: a failed transaction leaves no success audit; system events never impersonate users.
 * M02.S02 gate: an authorized root sees edits and permission changes; workers without permission
 * cannot read history.
 */
class AuditIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String WORKER_MOBILE = "+919100000001";
    private static final String OTHER_OWNER = "+919200000001";

    @Autowired
    AuditWriter auditWriter;

    @Autowired
    PlatformTransactionManager transactionManager;

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

    // --- M02.S01 write ------------------------------------------------------------------------

    @Test
    void aFailedTransactionLeavesNoAudit() {
        long before = count("audit_event");

        assertThatThrownBy(() -> inTransaction(() -> {
            auditWriter.append(event(AuditActor.user(ownerMembership), Map.of()));
            throw new IllegalStateException("mutation failed after auditing");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("audit_event")).isEqualTo(before);
    }

    @Test
    void aRejectedCommandLeavesNoAudit() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        long before = count("audit_event");

        // Stale version: the command fails, so nothing is recorded.
        replacePermissions(workerId, "[\"ORDER_READ\"]", 5).andExpect(status().isConflict());

        assertThat(count("audit_event")).isEqualTo(before);
    }

    @Test
    void auditOnlyWritesInsideAMutationTransaction() {
        assertThatThrownBy(() -> auditWriter.append(event(AuditActor.user(ownerMembership), Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void systemEventsNeverImpersonateUsers() {
        assertThatThrownBy(() -> new AuditActor(AuditActor.Type.SYSTEM, ownerMembership))
                .isInstanceOf(IllegalArgumentException.class);

        // Background work records the user who caused it as initiator, never as actor.
        inTransaction(() -> auditWriter.append(new AuditEvent(businessId, AuditActor.system(), ownerMembership, null,
                AuditSource.SYSTEM, "test.background", "business", businessId.toString(), Map.of())));
        assertThat(jdbc.sql("""
                        SELECT actor_type || '/' || coalesce(actor_membership_id::text, '-') || '/'
                               || initiator_membership_id
                        FROM audit_event WHERE action = 'test.background'
                        """).query(String.class).single())
                .isEqualTo("SYSTEM/-/" + ownerMembership);

        // The database refuses a system event that names a user as actor.
        assertThatThrownBy(() -> inTransaction(() -> jdbc.sql("""
                        INSERT INTO audit_event (id, business_id, occurred_at, actor_type, actor_membership_id, source,
                                                 action, resource_type, resource_id, changes)
                        VALUES (gen_random_uuid(), :b, now(), 'SYSTEM', :m, 'SYSTEM', 'x', 'x', 'x', '{}')
                        """).param("b", businessId).param("m", ownerMembership).update()))
                .hasStackTraceContaining("ck_audit_event_actor");
    }

    @Test
    void eventsCanOnlyBeAttributedToMembersOfTheirBusiness() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");
        UUID foreignMembership = jdbc.sql("SELECT owner_membership_id FROM business WHERE id = :id")
                .param("id", otherBusiness).query(UUID.class).single();

        assertThatThrownBy(() -> inTransaction(() ->
                auditWriter.append(event(AuditActor.user(foreignMembership), Map.of()))))
                .hasStackTraceContaining("fk_audit_event_actor");
    }

    @Test
    void historyIsImmutable() {
        assertThatThrownBy(() -> inTransaction(() -> jdbc.sql("UPDATE audit_event SET action = 'forged'").update()))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> inTransaction(() -> jdbc.sql("DELETE FROM audit_event").update()))
                .hasStackTraceContaining("append-only");
        assertThat(count("audit_event")).isEqualTo(1);
    }

    @Test
    void recordsAttributionSourceAndCorrelation() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);

        mockMvc.perform(put("/v1/iam/workers/" + workerId + "/permissions")
                        .header("Authorization", bearer(owner))
                        .header("X-Business-Id", businessId.toString())
                        .header("Idempotency-Key", newKey())
                        .header("X-Correlation-Id", "corr-audit-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"ORDER_READ\"],\"version\":0}"))
                .andExpect(status().isOk());

        JsonNode event = history(owner, "?resourceType=worker").get("items").get(0);
        assertThat(event.get("action").asString()).isEqualTo("iam.worker.permissions.replace");
        assertThat(event.get("actorType").asString()).isEqualTo("USER");
        assertThat(event.get("actorMembershipId").asString()).isEqualTo(ownerMembership.toString());
        assertThat(event.get("initiatorMembershipId").asString()).isEqualTo(ownerMembership.toString());
        assertThat(event.get("approverMembershipId").isNull()).isTrue();
        assertThat(event.get("source").asString()).isEqualTo("MANUAL");
        assertThat(event.get("resourceId").asString()).isEqualTo(workerId.toString());
        assertThat(event.get("correlationId").asString()).isEqualTo("corr-audit-0001");
    }

    @Test
    void sensitiveFieldsNeverReachHistory() {
        inTransaction(() -> auditWriter.append(event(AuditActor.user(ownerMembership),
                Map.of("refreshToken", new AuditChange(null, "raw-secret-value"),
                        "name", new AuditChange("Old", "New")))));

        String stored = jdbc.sql("SELECT changes::text FROM audit_event WHERE action = 'test.event'")
                .query(String.class).single();
        assertThat(stored).doesNotContain("raw-secret-value").contains("[REDACTED]").contains("New");
    }

    @Test
    void inviteAndAcceptAreAuditedWithoutTheTokenOrFullMobile() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        String token = invitationSender.lastToken(WORKER_MOBILE);

        String history = history(owner, "?resourceType=invitation").toString();
        assertThat(history).contains("iam.invitation.create", "iam.invitation.accept", "+91******0001")
                .doesNotContain(token).doesNotContain(WORKER_MOBILE);
    }

    // --- M02.S02 read -------------------------------------------------------------------------

    @Test
    void ownerSeesEditsAndPermissionChangesNewestFirst() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        replacePermissions(workerId, "[\"ORDER_READ\"]", 0).andExpect(status().isOk());

        JsonNode page = history(owner, "");
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
        JsonNode items = page.get("items");
        assertThat(items.get(0).get("action").asString()).isEqualTo("iam.worker.permissions.replace");
        assertThat(items.get(0).at("/changes/permissions/before").isEmpty()).isTrue();
        assertThat(items.get(0).at("/changes/permissions/after/0").asString()).isEqualTo("ORDER_READ");
        assertThat(items.get(1).get("action").asString()).isEqualTo("iam.invitation.accept");
        assertThat(items.get(2).get("action").asString()).isEqualTo("iam.invitation.create");
        assertThat(items.get(3).get("action").asString()).isEqualTo("business.create");
        assertThat(items.get(3).at("/changes/name/after").asString()).isEqualTo("Sharma Traders");
    }

    @Test
    void workersWithoutAuditReadCannotReadHistory() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        replacePermissions(workerId, "[\"ORDER_READ\"]", 0).andExpect(status().isOk());

        iam.getScoped(worker, businessId, "/v1/audit-events")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        replacePermissions(workerId, "[\"AUDIT_READ\"]", 1).andExpect(status().isOk());
        iam.getScoped(worker, businessId, "/v1/audit-events").andExpect(status().isOk());
    }

    @Test
    void historyIsTenantScoped() throws Exception {
        JsonNode otherOwner = iam.signIn(OTHER_OWNER);
        UUID otherBusiness = iam.createBusiness(otherOwner, "Other Shop");

        JsonNode mine = history(owner, "");
        assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.toString()).doesNotContain(otherBusiness.toString()).doesNotContain("Other Shop");

        iam.getScoped(owner, otherBusiness, "/v1/audit-events").andExpect(status().isNotFound());
    }

    @Test
    void filtersByResourceAndTime() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        UUID workerId = iam.addWorker(owner, businessId, worker, WORKER_MOBILE);
        replacePermissions(workerId, "[\"ORDER_READ\"]", 0).andExpect(status().isOk());

        assertThat(history(owner, "?resourceType=business").get("totalElements").asLong()).isEqualTo(1);
        assertThat(history(owner, "?resourceType=worker&resourceId=" + workerId).get("totalElements").asLong())
                .isEqualTo(1);
        assertThat(history(owner, "?resourceType=worker&resourceId=" + UUID.randomUUID())
                .get("totalElements").asLong()).isZero();

        Instant future = Instant.now().plusSeconds(3600);
        assertThat(history(owner, "?from=" + future).get("totalElements").asLong()).isZero();
        assertThat(history(owner, "?to=" + future).get("totalElements").asLong()).isEqualTo(4);
        assertThat(history(owner, "?to=2020-01-01T00:00:00Z").get("totalElements").asLong()).isZero();
    }

    @Test
    void paginates() throws Exception {
        JsonNode worker = iam.signIn(WORKER_MOBILE);
        iam.addWorker(owner, businessId, worker, WORKER_MOBILE);

        JsonNode page = history(owner, "?size=2&page=1");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("action").asString()).isEqualTo("business.create");
    }

    @Test
    void rejectsInvalidFilters() throws Exception {
        iam.getScoped(owner, businessId, "/v1/audit-events?from=yesterday")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        iam.getScoped(owner, businessId, "/v1/audit-events?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(get("/v1/audit-events").header("Authorization", bearer(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_SELECTED"));
    }

    @Test
    void sensitiveFieldsAreRedactedOnRead() throws Exception {
        // A row written without the writer (e.g. by a past bug) is still redacted when read.
        jdbc.sql("""
                        INSERT INTO audit_event (id, business_id, occurred_at, actor_type, actor_membership_id, source,
                                                 action, resource_type, resource_id, changes)
                        VALUES (gen_random_uuid(), :b, now(), 'USER', :m, 'MANUAL', 'test.legacy', 'business', :b,
                                '{"otpCode":{"before":null,"after":"123456"}}')
                        """)
                .param("b", businessId).param("m", ownerMembership).update();

        assertThat(history(owner, "?resourceType=business").toString()).contains("test.legacy", "[REDACTED]").doesNotContain("123456");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/audit-events").header("X-Business-Id", businessId.toString()))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ------------------------------------------------------------------------------

    private AuditEvent event(AuditActor actor, Map<String, AuditChange> changes) {
        return new AuditEvent(businessId, actor, actor.membershipId(), null, AuditSource.MANUAL, "test.event",
                "business", businessId.toString(), changes);
    }

    private JsonNode history(JsonNode caller, String query) throws Exception {
        return iam.read(iam.getScoped(caller, businessId, "/v1/audit-events" + query).andExpect(status().isOk()));
    }

    private ResultActions replacePermissions(UUID workerId, String permissionsJson, long version) throws Exception {
        return mockMvc.perform(put("/v1/iam/workers/" + workerId + "/permissions")
                .header("Authorization", bearer(owner))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}"));
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
