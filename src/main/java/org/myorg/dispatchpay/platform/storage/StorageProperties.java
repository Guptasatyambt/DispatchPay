package org.myorg.dispatchpay.platform.storage;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param endpoint        S3 API endpoint the server talks to
 * @param publicEndpoint  endpoint pre-signed URLs are issued for, when clients reach storage at a
 *                        different address than the server; blank to use {@code endpoint}
 * @param bucket          private bucket holding every object
 * @param pathStyleAccess bucket in the path rather than the host name (MinIO)
 * @param createBucket    create the bucket at startup when missing; local stacks only
 */
@Validated
@ConfigurationProperties("dispatchpay.storage")
public record StorageProperties(
        @NotNull URI endpoint,
        String publicEndpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        boolean pathStyleAccess,
        boolean createBucket) {

    URI presignEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : URI.create(publicEndpoint);
    }
}
