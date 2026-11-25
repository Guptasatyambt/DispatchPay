package org.myorg.dispatchpay.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives sign-in, businesses, invitations and business-scoped reads from tests. */
public final class IamClient {

    private final MockMvc mockMvc;
    private final CapturingOtpSender otpSender;
    private final CapturingInvitationSender invitationSender;
    private final JsonMapper json = JsonMapper.builder().build();

    public IamClient(MockMvc mockMvc, CapturingOtpSender otpSender, CapturingInvitationSender invitationSender) {
        this.mockMvc = mockMvc;
        this.otpSender = otpSender;
        this.invitationSender = invitationSender;
    }

    /** Requests and verifies an OTP; returns the token response. */
    public JsonNode signIn(String mobile) throws Exception {
        mockMvc.perform(post("/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\"}"));
        return read(mockMvc.perform(post("/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\",\"code\":\"" + otpSender.lastCode(mobile) + "\"}")));
    }

    public UUID createBusiness(JsonNode owner, String name) throws Exception {
        JsonNode created = read(mockMvc.perform(post("/v1/businesses")
                .header("Authorization", bearer(owner))
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")));
        return UUID.fromString(created.get("id").asString());
    }

    public ResultActions invite(JsonNode caller, UUID businessId, String mobile, String idempotencyKey)
            throws Exception {
        return mockMvc.perform(post("/v1/iam/invitations")
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\"}"));
    }

    public ResultActions accept(JsonNode caller, String token, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/v1/iam/invitations/accept")
                .header("Authorization", bearer(caller))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    /** Invites {@code mobile} and accepts as {@code worker} (signed in with that mobile); returns the membership id. */
    public UUID addWorker(JsonNode owner, UUID businessId, JsonNode worker, String mobile) throws Exception {
        invite(owner, businessId, mobile, newKey());
        JsonNode accepted = read(accept(worker, invitationSender.lastToken(mobile), newKey()));
        return UUID.fromString(accepted.get("membershipId").asString());
    }

    public ResultActions getScoped(JsonNode caller, UUID businessId, String path) throws Exception {
        return mockMvc.perform(get(path)
                .header("Authorization", bearer(caller))
                .header("X-Business-Id", businessId.toString()));
    }

    public JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    public static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    public static UUID userId(JsonNode tokens) {
        return UUID.fromString(tokens.get("userId").asString());
    }

    public static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asString();
    }
}
