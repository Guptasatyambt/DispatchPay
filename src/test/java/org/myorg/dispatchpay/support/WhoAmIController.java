package org.myorg.dispatchpay.support;

import java.util.Map;

import org.myorg.dispatchpay.iam.auth.session.AccessTokenIssuer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected test endpoint covered by the real API security chain. */
@RestController
public class WhoAmIController {

    @GetMapping("/v1/test/whoami")
    Map<String, String> whoAmI(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("userId", jwt.getSubject(), "sessionId", jwt.getClaimAsString(AccessTokenIssuer.SESSION_CLAIM));
    }
}
