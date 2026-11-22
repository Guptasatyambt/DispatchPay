package org.myorg.dispatchpay.iam.auth.session;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.myorg.dispatchpay.iam.auth.AuthProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived access tokens carrying identity and session only. Permissions are
 * deliberately absent: authorization is always evaluated against current database state.
 */
@Component
public class AccessTokenIssuer {

    public static final String SESSION_CLAIM = "sid";

    public record AccessToken(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final AuthProperties.Jwt properties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties.jwt();
        this.clock = clock;
    }

    public AccessToken issue(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(SESSION_CLAIM, sessionId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, expiresAt);
    }
}
