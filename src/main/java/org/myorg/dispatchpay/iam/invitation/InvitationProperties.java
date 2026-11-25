package org.myorg.dispatchpay.iam.invitation;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param ttl how long an invitation can be accepted
 */
@Validated
@ConfigurationProperties("dispatchpay.invitation")
public record InvitationProperties(@NotNull Duration ttl) {
}
