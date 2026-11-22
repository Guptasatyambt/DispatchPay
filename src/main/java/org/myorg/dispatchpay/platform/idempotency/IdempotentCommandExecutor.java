package org.myorg.dispatchpay.platform.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a mutating command at most once per idempotency key.
 *
 * <p>The key record and the command's effects commit in one transaction, so:
 * <ul>
 *   <li>same key + same content returns the stored original response without re-executing;</li>
 *   <li>same key + different content fails with {@link ErrorCode#IDEMPOTENCY_KEY_REUSED};</li>
 *   <li>concurrent duplicates serialize on the key and the later one replays;</li>
 *   <li>a failed command rolls back its key, so the client may retry it.</li>
 * </ul>
 * Commands must not make external network calls (they run inside the transaction).
 */
@Component
public class IdempotentCommandExecutor {

    public static final String HEADER = "Idempotency-Key";

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final IdempotencyRepository repository;
    private final RequestHasher hasher;
    private final JsonMapper jsonMapper;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotentCommandExecutor(IdempotencyRepository repository, RequestHasher hasher, JsonMapper jsonMapper,
            IdempotencyProperties properties, Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public <R> IdempotentResult<R> execute(IdempotentRequest request, Class<R> responseType, Supplier<R> command) {
        String key = validateKey(request.key());
        String requestHash = hasher.hash(request.operation(), request.payload());
        Instant now = clock.instant();

        repository.deleteExpired(request.scope(), request.operation(), key, now);
        UUID id = UUID.randomUUID();
        if (repository.tryInsert(id, request.scope(), request.operation(), key, requestHash,
                now, now.plus(properties.retention()))) {
            R response = command.get();
            repository.complete(id, jsonMapper.writeValueAsString(response), clock.instant());
            return new IdempotentResult<>(response, false);
        }

        IdempotencyRepository.StoredRecord existing = repository.find(request.scope(), request.operation(), key)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT,
                        "A request with this idempotency key is being processed; retry"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This idempotency key was already used with a different request");
        }
        return new IdempotentResult<>(jsonMapper.readValue(existing.responseBody(), responseType), true);
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HEADER + " header is required");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    HEADER + " must be 8-128 characters of letters, digits, '.', '_', ':' or '-'");
        }
        return key;
    }
}
