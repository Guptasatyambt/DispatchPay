package org.myorg.dispatchpay.iam.invitation;

import java.time.Instant;

import org.myorg.dispatchpay.iam.auth.MobileNumber;

/**
 * Delivers an invitation token to the invited mobile, typically as an SMS app link. Implementations
 * must never log the token (outside a dev-only sender) and are called after the invitation commits.
 */
public interface InvitationSender {

    void send(MobileNumber mobile, String businessName, String token, Instant expiresAt);
}
