package org.myorg.dispatchpay.iam.access;

import java.util.UUID;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link BusinessAccess} parameters from the {@value BusinessAccess#HEADER} header and the caller's token. */
class BusinessAccessArgumentResolver implements HandlerMethodArgumentResolver {

    private final AccessService accessService;

    BusinessAccessArgumentResolver(AccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return BusinessAccess.class.equals(parameter.getParameterType());
    }

    @Override
    public BusinessAccess resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required");
        }
        String raw = request.getHeader(BusinessAccess.HEADER);
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.BUSINESS_NOT_SELECTED,
                    "Select a business with the " + BusinessAccess.HEADER + " header");
        }
        UUID businessId;
        try {
            businessId = UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, BusinessAccess.HEADER + " must be a business id");
        }
        return accessService.resolve(UUID.fromString(jwt.getSubject()), businessId);
    }
}
