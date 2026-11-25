package org.myorg.dispatchpay.iam.invitation;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.myorg.dispatchpay.iam.invitation.InvitationService.InvitationAccepted;
import org.myorg.dispatchpay.iam.invitation.InvitationService.InvitationCreated;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.idempotency.IdempotentResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner invites a worker by mobile; the invitee signs in with that mobile and accepts with the
 * token they received. Both require an {@code Idempotency-Key}. The token is never returned by the
 * API: it reaches only the invited mobile.
 */
@RestController
@RequestMapping("/v1/iam/invitations")
public class InvitationController {

    public record CreateInvitationRequest(@NotBlank @Size(max = 32) String mobile) {
    }

    public record AcceptInvitationRequest(@NotBlank @Size(max = 128) String token) {
    }

    /** Idempotency payloads: the business is part of the content, and only a hash of the token is kept. */
    record CreatePayload(UUID businessId, String mobile) {
    }

    record AcceptPayload(String tokenHash) {
    }

    private final InvitationService invitationService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public InvitationController(InvitationService invitationService, IdempotentCommandExecutor idempotentExecutor) {
        this.invitationService = invitationService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    ResponseEntity<InvitationCreated> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateInvitationRequest body,
            BusinessAccess access) {
        access.requireOwner();
        MobileNumber mobile = MobileNumber.parse(body.mobile());
        String token = invitationService.newToken();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "iam.invitations.create",
                idempotencyKey, new CreatePayload(access.businessId(), mobile.e164()));
        IdempotentResult<InvitationCreated> result = idempotentExecutor.execute(request, InvitationCreated.class,
                () -> invitationService.create(access, mobile, token));
        if (!result.replayed()) {
            invitationService.deliver(result.body(), token);
        }
        return result.toResponse(HttpStatus.CREATED);
    }

    @PostMapping("/accept")
    ResponseEntity<InvitationAccepted> accept(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AcceptInvitationRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String token = body.token().strip();
        IdempotentRequest request = new IdempotentRequest("user:" + userId, "iam.invitations.accept",
                idempotencyKey, new AcceptPayload(InvitationService.sha256(token)));
        return idempotentExecutor
                .execute(request, InvitationAccepted.class, () -> invitationService.accept(userId, token))
                .toResponse(HttpStatus.CREATED);
    }
}
