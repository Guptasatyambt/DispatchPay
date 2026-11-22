package org.myorg.dispatchpay.iam.business;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.iam.business.BusinessService.BusinessCreated;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
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
 * Any signed-in user may register a business and becomes its owner. Requires an
 * {@code Idempotency-Key}, scoped to the user: a retried or double-submitted request returns
 * the original business instead of creating another.
 */
@RestController
@RequestMapping("/v1/businesses")
public class BusinessController {

    static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    public record CreateBusinessRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 64) String timezone) {

        @AssertTrue(message = "timezone must be an IANA time zone such as Asia/Kolkata")
        public boolean isTimezoneKnown() {
            if (timezone == null) {
                return true;
            }
            try {
                return ZoneId.of(timezone).getId().equals(timezone) && timezone.contains("/");
            } catch (DateTimeException e) {
                return false;
            }
        }

        CreateBusinessRequest normalized() {
            return new CreateBusinessRequest(name.strip(), timezone == null ? DEFAULT_TIMEZONE : timezone);
        }
    }

    private final BusinessService businessService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public BusinessController(BusinessService businessService, IdempotentCommandExecutor idempotentExecutor) {
        this.businessService = businessService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    ResponseEntity<BusinessCreated> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateBusinessRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        CreateBusinessRequest command = body.normalized();
        IdempotentRequest request = new IdempotentRequest("user:" + userId, "businesses.create", idempotencyKey,
                command);
        return idempotentExecutor
                .execute(request, BusinessCreated.class,
                        () -> businessService.create(userId, command.name(), command.timezone()))
                .toResponse(HttpStatus.CREATED);
    }
}
