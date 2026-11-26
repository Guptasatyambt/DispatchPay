package org.myorg.dispatchpay.imports;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.myorg.dispatchpay.imports.ImportRepository.JobRow;
import org.myorg.dispatchpay.platform.storage.ObjectStorage;
import org.myorg.dispatchpay.platform.storage.ObjectStorage.ObjectInfo;
import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polling import worker. It claims one waiting step at a time under a lease, reads the file from
 * object storage outside any transaction, and hands the result to {@link ImportProcessor}, which
 * finishes the step in one transaction. Any number of instances may poll; a step whose worker
 * crashed is claimed again once its lease expires, and a step that keeps failing is given up after
 * {@code max-attempts}.
 */
@Component
public class ImportWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);
    private static final int BATCH_SIZE = 20;

    private final ImportProcessor processor;
    private final ObjectStorage storage;
    private final ImportProperties properties;

    ImportWorker(ImportProcessor processor, ObjectStorage storage, ImportProperties properties) {
        this.processor = processor;
        this.storage = storage;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${dispatchpay.imports.poll-interval}")
    public void poll() {
        processPending();
    }

    /** Runs up to one batch of waiting steps and returns how many were run. */
    public int processPending() {
        int processed = 0;
        while (processed < BATCH_SIZE) {
            Optional<JobRow> claimed = processor.claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            process(claimed.get());
            processed++;
        }
        return processed;
    }

    private void process(JobRow job) {
        String previous = MDC.get(CorrelationId.MDC_KEY);
        MDC.put(CorrelationId.MDC_KEY, "import-" + job.id());
        try {
            if (job.attempts() > properties.maxAttempts()) {
                log.error("Import {} gave up {} after {} attempts", job.id(), job.status(), job.attempts() - 1);
                processor.giveUp(job);
                return;
            }
            switch (job.status()) {
                case VALIDATING -> validate(job);
                case COMMITTING -> processor.commit(job);
                default -> throw new IllegalStateException("Import " + job.id() + " is " + job.status());
            }
        } catch (RuntimeException e) {
            log.warn("Import {} {} failed on attempt {}; it will be retried", job.id(), job.status(), job.attempts(),
                    e);
            processor.release(job);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previous);
            }
        }
    }

    private void validate(JobRow job) {
        long maxBytes = properties.maxFileSize().toBytes();
        Optional<ObjectInfo> info = storage.stat(job.objectKey());
        if (info.isEmpty()) {
            processor.rejectFile(job, ImportFile.FILE_MISSING, "No file was uploaded for this import", null, null);
            return;
        }
        if (info.get().size() > maxBytes) {
            processor.rejectFile(job, ImportFile.FILE_TOO_LARGE,
                    "The file is larger than " + properties.maxFileSize(), info.get().size(), null);
            return;
        }
        String contentType = info.get().contentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(ImportService.CONTENT_TYPE)) {
            processor.rejectFile(job, ImportFile.FILE_NOT_CSV, "The file must be uploaded as "
                    + ImportService.CONTENT_TYPE, info.get().size(), null);
            return;
        }
        byte[] content = storage.read(job.objectKey(), maxBytes);
        String sha256 = sha256(content);
        ImportFile.Parsed parsed;
        try {
            parsed = ImportFile.parse(content, job.type(), properties.maxRows());
        } catch (ImportFile.Rejected rejected) {
            processor.rejectFile(job, rejected.code(), rejected.getMessage(), (long) content.length, sha256);
            return;
        }
        processor.finishValidation(job, parsed, content.length, sha256);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
