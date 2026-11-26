package org.myorg.dispatchpay;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/** Private object storage for tests: a MinIO container with the application's bucket. */
@TestConfiguration(proxyBeanMethods = false)
public class StorageTestConfiguration {

    /** Same image as compose.yaml; upstream MinIO no longer publishes images. */
    private static final DockerImageName MINIO = DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .asCompatibleSubstituteFor("minio/minio");

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(MINIO);
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("dispatchpay.storage.endpoint", minio::getS3URL);
            registry.add("dispatchpay.storage.access-key", minio::getUserName);
            registry.add("dispatchpay.storage.secret-key", minio::getPassword);
            registry.add("dispatchpay.storage.bucket", () -> "dispatchpay-test");
            registry.add("dispatchpay.storage.create-bucket", () -> "true");
        };
    }
}
