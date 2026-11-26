package org.myorg.dispatchpay.catalog;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.catalog.ProductService.NewProduct;
import org.myorg.dispatchpay.catalog.ProductService.ProductChanges;
import org.myorg.dispatchpay.catalog.ProductService.ProductDetail;
import org.myorg.dispatchpay.catalog.ProductService.ProductSummary;
import org.myorg.dispatchpay.catalog.ProductService.Status;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.platform.web.PatchRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Product catalog of the business selected with {@value BusinessAccess#HEADER}. Every change
 * requires an {@code Idempotency-Key}; edits and conversion changes also require the product's
 * current {@code version}.
 */
@RestController
@RequestMapping("/v1/products")
public class ProductController {

    private static final String SKU = ProductRules.SKU;
    private static final String SKU_MESSAGE = ProductRules.SKU_MESSAGE;
    private static final int BRAND_MAX = ProductRules.BRAND_MAX;
    private static final int PACK_SIZE_MAX = ProductRules.PACK_SIZE_MAX;
    private static final long PRICE_MAX = ProductRules.PRICE_MAX;
    private static final Set<String> PATCH_FIELDS =
            Set.of("sku", "name", "status", "baseUnit", "brand", "packSize", "price", "version");

    /**
     * @param packSize free text that tells similar products apart, e.g. "500 g" or "Pack of 6"
     * @param price    selling price of one base unit in paise (integer minor units)
     */
    public record CreateProductRequest(
            @NotBlank @Pattern(regexp = SKU, message = SKU_MESSAGE) String sku,
            @NotBlank @Size(max = ProductRules.NAME_MAX) String name,
            @NotBlank String baseUnit,
            @Size(max = BRAND_MAX) String brand,
            @Size(max = PACK_SIZE_MAX) String packSize,
            @PositiveOrZero @Max(PRICE_MAX) Long price) {
    }

    public record AddAliasRequest(@NotBlank @Size(max = ProductRules.ALIAS_MAX) String alias) {
    }

    public record ConversionRequest(@NotBlank String unit, @NotNull BigDecimal factor) {
    }

    public record ReplaceConversionsRequest(
            @NotNull @Size(max = 50) List<@NotNull @Valid ConversionRequest> conversions,
            @NotNull @PositiveOrZero Long version) {
    }

    /** Idempotency payloads: the business (and product) are part of the content. */
    record CreatePayload(UUID businessId, NewProduct product) {
    }

    record UpdatePayload(UUID businessId, UUID productId, ProductChanges changes, long version) {
    }

    record AliasPayload(UUID businessId, UUID productId, String alias) {
    }

    record ConversionsPayload(UUID businessId, UUID productId, Map<Unit, BigDecimal> conversions, long version) {
    }

    private final ProductService productService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public ProductController(ProductService productService, IdempotentCommandExecutor idempotentExecutor) {
        this.productService = productService;
        this.idempotentExecutor = idempotentExecutor;
    }

    /** Search by SKU, name or approved alias ({@code q}); inactive products only with {@code includeInactive}. */
    @GetMapping
    PageResponse<ProductSummary> search(
            BusinessAccess access,
            PageQuery page,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return productService.search(access, q, includeInactive, page);
    }

    @GetMapping("/{id}")
    ProductDetail get(BusinessAccess access, @PathVariable String id) {
        return productService.get(access, parse(id));
    }

    @PostMapping
    ResponseEntity<ProductDetail> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateProductRequest body,
            BusinessAccess access) {
        NewProduct product = new NewProduct(body.sku().strip(), body.name().strip(),
                unit(body.baseUnit(), "baseUnit"), blankToNull(body.brand()), blankToNull(body.packSize()),
                body.price());
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "products.create",
                idempotencyKey, new CreatePayload(access.businessId(), product));
        return idempotentExecutor
                .execute(request, ProductDetail.class, () -> productService.create(access, product))
                .toResponse(HttpStatus.CREATED);
    }

    /**
     * Omitted fields are left unchanged; {@code null} clears {@code brand}, {@code packSize} or
     * {@code price}. {@code baseUnit} may only repeat the current unit. {@code version} is required.
     */
    @PatchMapping("/{id}")
    ResponseEntity<ProductDetail> update(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @RequestBody JsonNode body,
            BusinessAccess access) {
        UUID productId = parse(id);
        PatchRequest patch = PatchRequest.of(body, PATCH_FIELDS);
        long version = patch.requiredWholeNumber("version", 0, Long.MAX_VALUE);
        String sku = patch.text("sku", 64);
        if (sku != null && !ProductRules.SKU_PATTERN.matcher(sku).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, SKU_MESSAGE);
        }
        String status = patch.text("status", 16);
        String baseUnit = patch.text("baseUnit", 16);
        ProductChanges changes = new ProductChanges(
                sku,
                patch.text("name", ProductRules.NAME_MAX),
                status == null ? null : status(status),
                baseUnit == null ? null : unit(baseUnit, "baseUnit"),
                patch.optionalText("brand", BRAND_MAX),
                patch.optionalText("packSize", PACK_SIZE_MAX),
                patch.optionalWholeNumber("price", 0, PRICE_MAX));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "products.update",
                idempotencyKey, new UpdatePayload(access.businessId(), productId, changes, version));
        return idempotentExecutor
                .execute(request, ProductDetail.class, () -> productService.update(access, productId, changes, version))
                .toResponse(HttpStatus.OK);
    }

    @PostMapping("/{id}/aliases")
    ResponseEntity<ProductDetail> addAlias(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody AddAliasRequest body,
            BusinessAccess access) {
        UUID productId = parse(id);
        String alias = body.alias().strip();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "products.aliases.add",
                idempotencyKey, new AliasPayload(access.businessId(), productId, alias));
        return idempotentExecutor
                .execute(request, ProductDetail.class, () -> productService.addAlias(access, productId, alias))
                .toResponse(HttpStatus.CREATED);
    }

    /** Replaces all conversions; each {@code factor} is the number of base units in one {@code unit}. */
    @PutMapping("/{id}/unit-conversions")
    ResponseEntity<ProductDetail> replaceConversions(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody ReplaceConversionsRequest body,
            BusinessAccess access) {
        UUID productId = parse(id);
        Map<Unit, BigDecimal> conversions = new LinkedHashMap<>();
        for (ConversionRequest conversion : body.conversions()) {
            Unit unit = unit(conversion.unit(), "unit");
            if (conversions.put(unit, conversion.factor()) != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, unit + " is listed more than once");
            }
        }
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "products.conversions.replace",
                idempotencyKey, new ConversionsPayload(access.businessId(), productId, conversions, body.version()));
        return idempotentExecutor
                .execute(request, ProductDetail.class,
                        () -> productService.replaceConversions(access, productId, conversions, body.version()))
                .toResponse(HttpStatus.OK);
    }

    private static Unit unit(String raw, String field) {
        Unit unit = ProductRules.unit(raw);
        if (unit == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must be one of " + List.of(Unit.values()));
        }
        return unit;
    }

    private static Status status(String raw) {
        try {
            return Status.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be ACTIVE or INACTIVE");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Product not found");
        }
    }
}
