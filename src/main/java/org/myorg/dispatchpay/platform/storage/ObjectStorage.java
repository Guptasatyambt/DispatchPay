package org.myorg.dispatchpay.platform.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Private object storage. Objects are never publicly readable; clients reach one object at a time
 * through short-lived pre-signed URLs. Every call is a network call, so never make one inside a
 * database transaction.
 */
public interface ObjectStorage {

    /**
     * @param headers headers the client must send exactly as given (they are part of the signature)
     */
    record PresignedUrl(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record ObjectInfo(long size, String contentType) {
    }

    /** A URL to upload exactly {@code size} bytes of {@code contentType} to {@code key}. */
    PresignedUrl presignUpload(String key, String contentType, long size, Duration ttl);

    /** A URL to download {@code key} as an attachment named {@code fileName}. */
    PresignedUrl presignDownload(String key, String fileName, Duration ttl);

    Optional<ObjectInfo> stat(String key);

    /** Reads at most {@code maxBytes}; a larger object fails instead of being read into memory. */
    byte[] read(String key, long maxBytes);

    void write(String key, byte[] content, String contentType);

    /** Whether the bucket is reachable, for readiness. */
    boolean isAvailable();
}
