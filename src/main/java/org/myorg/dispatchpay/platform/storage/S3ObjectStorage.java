package org.myorg.dispatchpay.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** {@link ObjectStorage} on an S3-compatible service (MinIO locally, S3 elsewhere). */
class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    /** Creates the bucket when missing. Failure is logged, not fatal: readiness reports it. */
    void ensureBucket() {
        try {
            try {
                client.headBucket(request -> request.bucket(bucket));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                client.createBucket(request -> request.bucket(bucket));
                log.info("Created storage bucket {}", bucket);
            }
        } catch (SdkException e) {
            log.warn("Storage bucket {} is not reachable: {}", bucket, e.getMessage());
        }
    }

    @Override
    public PresignedUrl presignUpload(String key, String contentType, long size, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        return toUrl(presigner.presignPutObject(request -> request.signatureDuration(ttl).putObjectRequest(put)));
    }

    @Override
    public PresignedUrl presignDownload(String key, String fileName, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition("attachment; filename=\"" + fileName + "\"")
                .build();
        return toUrl(presigner.presignGetObject(request -> request.signatureDuration(ttl).getObjectRequest(get)));
    }

    @Override
    public Optional<ObjectInfo> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(request -> request.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public byte[] read(String key, long maxBytes) {
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(request -> request.bucket(bucket).key(key))) {
            byte[] content = readAtMost(in, maxBytes);
            if (content.length > maxBytes) {
                throw new IllegalStateException("Object " + key + " is larger than " + maxBytes + " bytes");
            }
            return content;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(String key, byte[] content, String contentType) {
        client.putObject(request -> request.bucket(bucket).key(key).contentType(contentType),
                RequestBody.fromBytes(content));
    }

    @Override
    public boolean isAvailable() {
        try {
            client.headBucket(request -> request.bucket(bucket));
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    private static byte[] readAtMost(InputStream in, long maxBytes) throws IOException {
        return in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
    }

    /** The headers the client must replay; Host is set by every HTTP client itself. */
    private static PresignedUrl toUrl(PresignedRequest presigned) {
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!"host".equals(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUrl(presigned.url().toString(), presigned.httpRequest().method().name(), headers,
                presigned.expiration());
    }
}
