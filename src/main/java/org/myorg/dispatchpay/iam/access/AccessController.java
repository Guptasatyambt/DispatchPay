package org.myorg.dispatchpay.iam.access;

import java.util.List;
import java.util.UUID;

import org.myorg.dispatchpay.iam.access.AccessService.MembershipSummary;
import org.myorg.dispatchpay.iam.access.BusinessAccess.GodownScope;
import org.myorg.dispatchpay.iam.access.BusinessAccess.Role;
import org.myorg.dispatchpay.iam.user.UserAccountRepository;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own identity and access. {@code /me} lists the businesses to choose from;
 * {@code /permissions} reports what the caller may do in the business selected with
 * {@value BusinessAccess#HEADER}. Both are informational: every operation re-evaluates access.
 */
@RestController
@RequestMapping("/v1/iam")
public class AccessController {

    public record MeResponse(UUID userId, String mobile, List<MembershipSummary> memberships) {
    }

    public record PermissionsResponse(
            UUID businessId,
            UUID membershipId,
            Role role,
            List<Permission> permissions,
            GodownScope godownScope) {
    }

    private final AccessService accessService;
    private final UserAccountRepository users;

    public AccessController(AccessService accessService, UserAccountRepository users) {
        this.accessService = accessService;
        this.users = users;
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String mobile = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required"))
                .mobile();
        return new MeResponse(userId, mobile, accessService.memberships(userId));
    }

    @GetMapping("/permissions")
    PermissionsResponse permissions(BusinessAccess access) {
        List<Permission> permissions = access.permissions().stream().sorted().toList();
        return new PermissionsResponse(access.businessId(), access.membershipId(), access.role(), permissions,
                access.godownScope());
    }
}
