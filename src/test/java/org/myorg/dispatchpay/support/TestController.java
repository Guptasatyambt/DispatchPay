package org.myorg.dispatchpay.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exercise the platform plumbing; exists only on the test classpath. */
@RestController
@RequestMapping("/test")
public class TestController {

    public record ValidatedPayload(@NotBlank String name, @Min(1) int quantity) {
    }

    private final IdempotentCommandExecutor idempotentExecutor;
    private final WidgetService widgetService;

    public TestController(IdempotentCommandExecutor idempotentExecutor, WidgetService widgetService) {
        this.idempotentExecutor = idempotentExecutor;
        this.widgetService = widgetService;
    }

    @PostMapping("/validation")
    ValidatedPayload validate(@Valid @RequestBody ValidatedPayload payload) {
        return payload;
    }

    @GetMapping("/api-error")
    void apiError() {
        throw new ApiException(ErrorCode.CONFLICT, "Widget already exists");
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("password=hunter2 leaked from internals");
    }

    @GetMapping("/page")
    PageQuery page(PageQuery query) {
        return query;
    }

    @PostMapping("/widgets")
    ResponseEntity<WidgetService.WidgetCreated> createWidget(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @RequestBody WidgetService.CreateWidget command) {
        IdempotentRequest request = new IdempotentRequest("test-scope", "widgets.create", idempotencyKey, command);
        return idempotentExecutor
                .execute(request, WidgetService.WidgetCreated.class, () -> widgetService.create(command))
                .toResponse(HttpStatus.CREATED);
    }
}
