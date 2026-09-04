package com.lumora.pos.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where a shop's uploaded archive goes, and how much of it is kept (M5-06).
 *
 * <p>Two stores exist and the configuration decides which. {@code s3} is the deployed one; the
 * filesystem is what runs on a laptop and in the test suite. That is not a fallback dressed up as a
 * choice — a filesystem store on the deployed host would be <em>wrong</em>, because the container's
 * disk is wiped on every redeploy, and a backup that quietly evaporates is worse than none. {@link
 * S3#configured()} is checked at startup and the service refuses to pretend.
 *
 * @param directory root of the filesystem store. Ignored entirely when S3 is configured.
 * @param maxBytes the largest archive accepted. A cap is not paranoia: the body is streamed
 *     straight to storage on a 512 MB host, and the request that decides how much of somebody
 *     else's disk to use arrives over the network.
 * @param keepPerTenant how many archives survive per shop. Count, not age — unlike the till's
 *     30-day rule in {@code backup.cjs}, because here the cost is somebody's storage bill and a
 *     shop that uploads every twelve hours for a year would otherwise keep seven hundred.
 */
@ConfigurationProperties(prefix = "lumora.backup")
public record CloudBackupProperties(String directory, long maxBytes, int keepPerTenant, S3 s3) {

    public CloudBackupProperties {
        if (maxBytes <= 0) maxBytes = 512L * 1024 * 1024;
        if (keepPerTenant <= 0) keepPerTenant = 14;
        if (directory == null || directory.isBlank()) directory = "./cloud-backups";
        if (s3 == null) s3 = new S3(null, null, null, null, null);
    }

    /**
     * S3-compatible object storage. Cloudflare R2 in production, and the API is the same one AWS
     * S3, Backblaze B2 and MinIO speak — so the deployment is not soldered to a vendor.
     *
     * @param endpoint the account endpoint, e.g. {@code https://<account>.r2.cloudflarestorage.com}.
     *     Required for R2 and left empty for AWS proper, where the region determines it.
     * @param region {@code auto} for R2, which has no regions in the AWS sense but requires the
     *     signature to name one.
     */
    public record S3(
            String bucket,
            String endpoint,
            String region,
            String accessKeyId,
            String secretAccessKey) {

        /** All four of the credential parts, or none. Half-configured storage is a failed upload. */
        public boolean configured() {
            return notBlank(bucket) && notBlank(region) && notBlank(accessKeyId) && notBlank(secretAccessKey);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
