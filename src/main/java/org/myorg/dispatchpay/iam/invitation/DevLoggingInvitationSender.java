package org.myorg.dispatchpay.iam.invitation;

import java.time.Instant;

import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local development only: prints the invitation token to the console instead of sending an SMS.
 * Other profiles must provide a real {@link InvitationSender} or the application will not start.
 */
@Component
@Profile("dev")
class DevLoggingInvitationSender implements InvitationSender {

    private static final Logger log = LoggerFactory.getLogger(DevLoggingInvitationSender.class);

    @Override
    public void send(MobileNumber mobile, String businessName, String token, Instant expiresAt) {
        log.warn("[DEV ONLY] Invitation for {} to join {}: token {} (expires {})", mobile.masked(), businessName,
                token, expiresAt);
    }
}
