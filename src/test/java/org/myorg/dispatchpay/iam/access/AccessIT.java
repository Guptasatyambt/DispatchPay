package org.myorg.dispatchpay.iam.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M01.S05 gate: a user with two memberships receives isolated access sets. */
class AccessIT extends IntegrationTest {

    private static final String OWNER_MOBILE = "+919876543210";
    private static final String OTHER_MOBILE = "+919123456789";
    private static final String THIRD_MOBILE = "+919000000001";

    private final JsonMapper json = JsonMapper.builder().build();

    // --- GET /v1/iam/me -----------------------------------------------------------------------

    @Test
    void meListsTheCallersOwnMembershipsOnly() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);
        JsonNode other = signIn(OTHER_MOBILE);
        UUID owned = createBusiness(user, "Sharma Traders");
        UUID employer = createBusiness(other, "Gupta Distributors");
        UUID workerMembership = addWorker(employer, userId(user));
        createBusiness(other, "Unrelated Shop");

        JsonNode me = read(mockMvc.perform(get("/v1/iam/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId(user).toString()))
                .andExpect(jsonPath("$.mobile").value(OWNER_MOBILE)));

        JsonNode memberships = me.get("memberships");
        assertThat(memberships).hasSize(2);
        // Sorted by business name.
        assertThat(memberships.get(0).get("businessId").asString()).isEqualTo(employer.toString());
        assertThat(memberships.get(0).get("businessName").asString()).isEqualTo("Gupta Distributors");
        assertThat(memberships.get(0).get("membershipId").asString()).isEqualTo(workerMembership.toString());
        assertThat(memberships.get(0).get("role").asString()).isEqualTo("WORKER");
        assertThat(memberships.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(memberships.get(1).get("businessId").asString()).isEqualTo(owned.toString());
        assertThat(memberships.get(1).get("role").asString()).isEqualTo("OWNER");
        assertThat(memberships.get(1).get("timezone").asString()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void meIsEmptyBeforeJoiningAnyBusiness() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);

        mockMvc.perform(get("/v1/iam/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships").isEmpty());
    }

    @Test
    void meShowsSuspendedMemberships() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        suspend(addWorker(businessId, userId(worker)));

        mockMvc.perform(get("/v1/iam/me").header("Authorization", bearer(worker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships[0].status").value("SUSPENDED"));
    }

    // --- GET /v1/iam/permissions --------------------------------------------------------------

    @Test
    void ownerHoldsTheWholeCatalogAndEveryGodown() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");

        JsonNode access = read(permissions(owner, businessId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessId").value(businessId.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.godownScope.all").value(true))
                .andExpect(jsonPath("$.godownScope.godownIds").isEmpty()));

        assertThat(codes(access)).containsExactlyElementsOf(
                Arrays.stream(Permission.values()).map(Enum::name).toList());
    }

    @Test
    void workerHoldsExactlyTheirGrants() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        UUID membership = addWorker(businessId, userId(worker));
        grant(businessId, membership, Permission.ORDER_READ, Permission.ORDER_CREATE);

        JsonNode access = read(permissions(worker, businessId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.role").value("WORKER"))
                .andExpect(jsonPath("$.godownScope.all").value(false))
                .andExpect(jsonPath("$.godownScope.godownIds").isEmpty()));

        assertThat(codes(access)).containsExactly("ORDER_READ", "ORDER_CREATE");
    }

    @Test
    void workerWithoutGrantsIsDeniedEverything() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        addWorker(businessId, userId(worker));

        permissions(worker, businessId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void aUserWithTwoMembershipsReceivesIsolatedAccessSets() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);
        JsonNode firstOwner = signIn(OTHER_MOBILE);
        JsonNode secondOwner = signIn(THIRD_MOBILE);
        UUID first = createBusiness(firstOwner, "First Shop");
        UUID second = createBusiness(secondOwner, "Second Shop");
        UUID inFirst = addWorker(first, userId(user));
        UUID inSecond = addWorker(second, userId(user));
        grant(first, inFirst, Permission.ORDER_CREATE);
        grant(second, inSecond, Permission.CASH_RECEIPT_RECORD, Permission.STOCK_READ);

        assertThat(codes(read(permissions(user, first.toString())))).containsExactly("ORDER_CREATE");
        assertThat(codes(read(permissions(user, second.toString()))))
                .containsExactly("STOCK_READ", "CASH_RECEIPT_RECORD");
    }

    @Test
    void ownershipOfOneBusinessGrantsNothingInAnother() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);
        JsonNode other = signIn(OTHER_MOBILE);
        UUID owned = createBusiness(user, "Own Shop");
        UUID employer = createBusiness(other, "Employer");
        UUID membership = addWorker(employer, userId(user));
        grant(employer, membership, Permission.DISPATCH_READ);

        permissions(user, owned.toString())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.godownScope.all").value(true));
        JsonNode atEmployer = read(permissions(user, employer.toString())
                .andExpect(jsonPath("$.role").value("WORKER"))
                .andExpect(jsonPath("$.godownScope.all").value(false)));
        assertThat(codes(atEmployer)).containsExactly("DISPATCH_READ");
    }

    @Test
    void anotherWorkersGrantsDoNotLeak() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        JsonNode colleague = signIn(THIRD_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        UUID workerMembership = addWorker(businessId, userId(worker));
        UUID colleagueMembership = addWorker(businessId, userId(colleague));
        grant(businessId, workerMembership, Permission.ORDER_READ);
        grant(businessId, colleagueMembership, Permission.REPORT_READ, Permission.AUDIT_READ);

        assertThat(codes(read(permissions(worker, businessId.toString())))).containsExactly("ORDER_READ");
    }

    @Test
    void revokedGrantsDisappearImmediately() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        UUID membership = addWorker(businessId, userId(worker));
        grant(businessId, membership, Permission.ORDER_READ, Permission.ORDER_CONFIRM);
        assertThat(codes(read(permissions(worker, businessId.toString())))).hasSize(2);

        jdbc.sql("DELETE FROM membership_permission WHERE permission = 'ORDER_CONFIRM'").update();

        // Same access token: access is evaluated from the database, not the token.
        assertThat(codes(read(permissions(worker, businessId.toString())))).containsExactly("ORDER_READ");
    }

    @Test
    void unknownStoredPermissionCodesAreIgnored() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        UUID membership = addWorker(businessId, userId(worker));
        grant(businessId, membership, Permission.ORDER_READ);
        insertGrant(businessId, membership, "RETIRED_PERMISSION");

        assertThat(codes(read(permissions(worker, businessId.toString())))).containsExactly("ORDER_READ");
    }

    @Test
    void aGrantCannotPointAtAnotherBusinessesMembership() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode other = signIn(OTHER_MOBILE);
        JsonNode worker = signIn(THIRD_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        UUID foreign = createBusiness(other, "Other Shop");
        UUID membership = addWorker(businessId, userId(worker));

        assertThatThrownBy(() -> insertGrant(foreign, membership, Permission.ORDER_READ.name()))
                .hasStackTraceContaining("fk_membership_permission_membership");
    }

    @Test
    void foreignAndUnknownBusinessesAreIndistinguishable() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);
        JsonNode other = signIn(OTHER_MOBILE);
        UUID foreign = createBusiness(other, "Not Yours");

        String foreignBody = permissions(user, foreign.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String unknownBody = permissions(user, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(foreignBody).get("detail"))
                .isEqualTo(json.readTree(unknownBody).get("detail"));
        assertThat(foreignBody).doesNotContain("Not Yours");
    }

    @Test
    void suspendedMembershipIsDenied() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        JsonNode worker = signIn(OTHER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        UUID membership = addWorker(businessId, userId(worker));
        grant(businessId, membership, Permission.ORDER_READ);
        suspend(membership);

        permissions(worker, businessId.toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_SUSPENDED"));
    }

    @Test
    void suspensionInOneBusinessLeavesOthersAccessible() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);
        JsonNode other = signIn(OTHER_MOBILE);
        UUID owned = createBusiness(user, "Own Shop");
        UUID employer = createBusiness(other, "Employer");
        suspend(addWorker(employer, userId(user)));

        permissions(user, employer.toString()).andExpect(status().isForbidden());
        permissions(user, owned.toString()).andExpect(status().isOk());
    }

    @Test
    void requiresASelectedBusiness() throws Exception {
        JsonNode user = signIn(OWNER_MOBILE);

        mockMvc.perform(get("/v1/iam/permissions").header("Authorization", bearer(user)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_SELECTED"));
        permissions(user, " ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_SELECTED"));
        permissions(user, "not-a-uuid")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/iam/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/iam/permissions").header(BusinessAccess.HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedSessionLosesAccess() throws Exception {
        JsonNode owner = signIn(OWNER_MOBILE);
        UUID businessId = createBusiness(owner, "Sharma Traders");
        mockMvc.perform(post("/v1/sessions/revoke")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"CURRENT\"}"))
                .andExpect(status().isOk());

        permissions(owner, businessId.toString()).andExpect(status().isUnauthorized());
    }

    // --- helpers ------------------------------------------------------------------------------

    private ResultActions permissions(JsonNode caller, String businessId) throws Exception {
        MockHttpServletRequestBuilder request = get("/v1/iam/permissions").header("Authorization", bearer(caller));
        return mockMvc.perform(request.header(BusinessAccess.HEADER, businessId));
    }

    private JsonNode signIn(String mobile) throws Exception {
        mockMvc.perform(post("/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\"}"));
        return read(mockMvc.perform(post("/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\",\"code\":\"" + otpSender.lastCode(mobile) + "\"}")));
    }

    private UUID createBusiness(JsonNode owner, String name) throws Exception {
        JsonNode created = read(mockMvc.perform(post("/v1/businesses")
                        .header("Authorization", bearer(owner))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()));
        return UUID.fromString(created.get("id").asString());
    }

    /** Invitations arrive in M01.S06/S07; until then workers are added directly. */
    private UUID addWorker(UUID businessId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business_membership (id, business_id, user_id, status, created_at, updated_at)
                        VALUES (:id, :businessId, :userId, 'ACTIVE', now(), now())
                        """)
                .param("id", id).param("businessId", businessId).param("userId", userId).update();
        return id;
    }

    private void suspend(UUID membershipId) {
        jdbc.sql("UPDATE business_membership SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", membershipId).update();
    }

    private void grant(UUID businessId, UUID membershipId, Permission... permissions) {
        for (Permission permission : permissions) {
            insertGrant(businessId, membershipId, permission.name());
        }
    }

    private void insertGrant(UUID businessId, UUID membershipId, String code) {
        jdbc.sql("""
                        INSERT INTO membership_permission (business_id, membership_id, permission, created_at)
                        VALUES (:businessId, :membershipId, :code, now())
                        """)
                .param("businessId", businessId).param("membershipId", membershipId).param("code", code).update();
    }

    private static List<String> codes(JsonNode access) {
        List<String> codes = new ArrayList<>();
        access.get("permissions").forEach(node -> codes.add(node.asString()));
        return codes;
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
