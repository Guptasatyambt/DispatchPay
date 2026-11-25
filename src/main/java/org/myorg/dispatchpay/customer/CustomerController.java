package org.myorg.dispatchpay.customer;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.customer.CustomerService.CustomerChanges;
import org.myorg.dispatchpay.customer.CustomerService.CustomerDetail;
import org.myorg.dispatchpay.customer.CustomerService.CustomerSummary;
import org.myorg.dispatchpay.customer.CustomerService.NewCustomer;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.platform.web.PatchRequest;
import org.myorg.dispatchpay.platform.web.PatchRequest.Change;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Customers of the business selected with {@value BusinessAccess#HEADER}. Every change requires an
 * {@code Idempotency-Key}; edits also require the customer's current {@code version}.
 */
@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    private static final int NAME_MAX = 200;
    private static final int CONTACT_MAX = 120;
    private static final int PHONE_MAX = 20;
    private static final int ADDRESS_MAX = 500;
    private static final int TERMS_MAX = 365;
    /** Upper bound for a credit limit in paise (₹1,000 crore). */
    private static final long CREDIT_LIMIT_MAX = 1_000_000_000_000L;
    private static final String PHONE = "\\+?[0-9][0-9 -]{5,19}";
    private static final String PHONE_MESSAGE = "phone must be 6-20 digits, optionally with a leading +, spaces or -";
    private static final Pattern PHONE_PATTERN = Pattern.compile(PHONE);
    private static final String USERNAME = "[A-Za-z0-9][A-Za-z0-9._-]{2,39}";
    private static final String USERNAME_MESSAGE =
            "username must be 3-40 letters, digits, '.', '_' or '-', starting with a letter or digit";
    private static final Pattern USERNAME_PATTERN = Pattern.compile(USERNAME);
    private static final Set<String> PATCH_FIELDS = Set.of("username", "name", "contactPerson", "phone", "address",
            "paymentTermsDays", "creditLimit", "version");

    /**
     * @param username         unique within the business, ignoring case; stored lower-case
     * @param paymentTermsDays days allowed for payment, 0 for cash
     * @param creditLimit      in paise
     */
    public record CreateCustomerRequest(
            @NotBlank @Size(max = 60) String username,
            @NotBlank @Size(max = NAME_MAX) String name,
            @Size(max = CONTACT_MAX) String contactPerson,
            @jakarta.validation.constraints.Pattern(regexp = PHONE, message = PHONE_MESSAGE) String phone,
            @Size(max = ADDRESS_MAX) String address,
            @PositiveOrZero @Max(TERMS_MAX) Integer paymentTermsDays,
            @PositiveOrZero @Max(CREDIT_LIMIT_MAX) Long creditLimit) {
    }

    public record AddAliasRequest(@NotBlank @Size(max = 120) String alias) {
    }

    /** Idempotency payloads: the business (and customer) are part of the content. */
    record CreatePayload(UUID businessId, NewCustomer customer) {
    }

    record UpdatePayload(UUID businessId, UUID customerId, CustomerChanges changes, long version) {
    }

    record AliasPayload(UUID businessId, UUID customerId, String alias) {
    }

    private final CustomerService customerService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public CustomerController(CustomerService customerService, IdempotentCommandExecutor idempotentExecutor) {
        this.customerService = customerService;
        this.idempotentExecutor = idempotentExecutor;
    }

    /** Search by name, phone or approved alias ({@code q}). */
    @GetMapping
    PageResponse<CustomerSummary> search(BusinessAccess access, PageQuery page,
            @RequestParam(required = false) String q) {
        return customerService.search(access, q, page);
    }

    @GetMapping("/{id}")
    CustomerDetail get(BusinessAccess access, @PathVariable String id) {
        return customerService.get(access, parse(id));
    }

    @PostMapping
    ResponseEntity<CustomerDetail> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateCustomerRequest body,
            BusinessAccess access) {
        NewCustomer customer = new NewCustomer(username(body.username()), body.name().strip(),
                blankToNull(body.contactPerson()), blankToNull(body.phone()), blankToNull(body.address()),
                body.paymentTermsDays(), body.creditLimit());
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "customers.create",
                idempotencyKey, new CreatePayload(access.businessId(), customer));
        return idempotentExecutor
                .execute(request, CustomerDetail.class, () -> customerService.create(access, customer))
                .toResponse(HttpStatus.CREATED);
    }

    /**
     * Omitted fields are left unchanged; {@code null} clears an optional field (everything except
     * {@code username} and {@code name}). {@code version} is required.
     */
    @PatchMapping("/{id}")
    ResponseEntity<CustomerDetail> update(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @RequestBody JsonNode body,
            BusinessAccess access) {
        UUID customerId = parse(id);
        PatchRequest patch = PatchRequest.of(body, PATCH_FIELDS);
        long version = patch.requiredWholeNumber("version", 0, Long.MAX_VALUE);
        Change<String> phone = patch.optionalText("phone", PHONE_MAX);
        if (phone != null && phone.value() != null && !PHONE_PATTERN.matcher(phone.value()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, PHONE_MESSAGE);
        }
        Change<Long> terms = patch.optionalWholeNumber("paymentTermsDays", 0, TERMS_MAX);
        String username = patch.text("username", 40);
        CustomerChanges changes = new CustomerChanges(
                username == null ? null : username(username),
                patch.text("name", NAME_MAX),
                patch.optionalText("contactPerson", CONTACT_MAX),
                phone,
                patch.optionalText("address", ADDRESS_MAX),
                terms == null ? null : new Change<>(terms.value() == null ? null : terms.value().intValue()),
                patch.optionalWholeNumber("creditLimit", 0, CREDIT_LIMIT_MAX));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "customers.update",
                idempotencyKey, new UpdatePayload(access.businessId(), customerId, changes, version));
        return idempotentExecutor
                .execute(request, CustomerDetail.class,
                        () -> customerService.update(access, customerId, changes, version))
                .toResponse(HttpStatus.OK);
    }

    @PostMapping("/{id}/aliases")
    ResponseEntity<CustomerDetail> addAlias(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody AddAliasRequest body,
            BusinessAccess access) {
        UUID customerId = parse(id);
        String alias = body.alias().strip();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "customers.aliases.add",
                idempotencyKey, new AliasPayload(access.businessId(), customerId, alias));
        return idempotentExecutor
                .execute(request, CustomerDetail.class, () -> customerService.addAlias(access, customerId, alias))
                .toResponse(HttpStatus.CREATED);
    }

    /** Validated and lower-cased, so uniqueness ignores case. */
    private static String username(String raw) {
        String username = raw.strip();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, USERNAME_MESSAGE);
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Customer not found");
        }
    }
}
