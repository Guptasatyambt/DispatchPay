package org.myorg.dispatchpay.iam.security;

import java.util.UUID;

import org.myorg.dispatchpay.iam.auth.session.AccessTokenIssuer;
import org.myorg.dispatchpay.iam.auth.session.SessionService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects access tokens whose session has been revoked or expired, or whose user is disabled.
 * Runs only after the signature and standard claims have been checked, so forged or expired
 * tokens never reach the database.
 */
class ActiveSessionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult INACTIVE = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The session is no longer active", null));

    private final OAuth2TokenValidator<Jwt> standardValidator;
    private final SessionService sessionService;

    ActiveSessionValidator(OAuth2TokenValidator<Jwt> standardValidator, SessionService sessionService) {
        this.standardValidator = standardValidator;
        this.sessionService = sessionService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        OAuth2TokenValidatorResult standard = standardValidator.validate(jwt);
        if (standard.hasErrors()) {
            return standard;
        }
        UUID userId = parse(jwt.getSubject());
        UUID sessionId = parse(jwt.getClaimAsString(AccessTokenIssuer.SESSION_CLAIM));
        if (userId == null || sessionId == null || !sessionService.isActive(sessionId, userId)) {
            return INACTIVE;
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static UUID parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
