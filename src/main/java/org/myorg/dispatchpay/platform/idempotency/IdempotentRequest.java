package org.myorg.dispatchpay.platform.idempotency;

import java.util.Objects;

/**
 * @param scope     who owns the key; keys are only unique within a scope (later: tenant + user)
 * @param operation stable command name, e.g. {@code orders.create}
 * @param key       raw {@value IdempotentCommandExecutor#HEADER} header value, validated on execution
 * @param payload   the request content; its canonical hash detects a key reused for different content
 */
public record IdempotentRequest(String scope, String operation, String key, Object payload) {

    public IdempotentRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
    }
}
