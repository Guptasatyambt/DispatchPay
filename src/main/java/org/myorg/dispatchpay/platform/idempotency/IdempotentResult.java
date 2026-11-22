package org.myorg.dispatchpay.platform.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Command outcome, flagged when it is a stored response replayed for a repeated key. */
public record IdempotentResult<R>(R body, boolean replayed) {

    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    public ResponseEntity<R> toResponse(HttpStatus status) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}
