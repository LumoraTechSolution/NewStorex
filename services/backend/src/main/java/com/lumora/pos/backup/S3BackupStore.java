package com.lumora.pos.backup;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The deployed store: Cloudflare R2, reached over the S3 API (M5-06).
 *
 * <p><b>The body is streamed, never buffered.</b> {@code RequestBody.fromInputStream} with a known
 * length sends as it reads, which is the only version of this that works: the host has 512 MB of
 * memory and the archive can be a hundred, so buffering it would kill the process the first time a
 * shop with real history uploaded.
 *
 * <p><b>Path style, not virtual host style.</b> R2's account endpoint does not resolve
 * {@code <bucket>.<account>.r2.cloudflarestorage.com}, so the default addressing produces a DNS
 * failure that reads like a network problem and is not one.
 */
public class S3BackupStore implements CloudBackupStore {

    private final S3Client client;
    private final String bucket;
    private final String describedAs;

    public S3BackupStore(CloudBackupProperties.S3 config) {
        var builder =
                S3Client.builder()
                        .region(Region.of(config.region()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                config.accessKeyId(), config.secretAccessKey())))
                        .forcePathStyle(true);
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            builder = builder.endpointOverride(URI.create(config.endpoint()));
        }
        this.client = builder.build();
        this.bucket = config.bucket();
        // Never the credential, and never enough of it to be one. An operator needs to know which
        // bucket a shop's history is in; nothing in a log needs to know how to reach it.
        this.describedAs =
                "S3 bucket " + config.bucket() + " at " + (config.endpoint() == null ? "AWS" : config.endpoint());
    }

    @Override
    public void put(String key, InputStream body, long contentLength) {
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentLength(contentLength).build(),
                    RequestBody.fromInputStream(body, contentLength));
        } catch (S3Exception e) {
            // Wrapped rather than propagated: everything above this treats a failed write as "the
            // till still has it, ask again later", and an SDK type leaking upward would make the
            // service depend on the store it was written not to depend on.
            throw new UncheckedIOException(
                    "Could not upload " + key, new java.io.IOException(e.getMessage(), e));
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException alreadyGone) {
            // Retention has to be safe to run twice, and an object somebody removed by hand is not
            // an error worth failing a prune over.
        } catch (S3Exception e) {
            throw new UncheckedIOException(
                    "Could not delete " + key, new java.io.IOException(e.getMessage(), e));
        }
    }

    @Override
    public String describe() {
        return describedAs;
    }
}
