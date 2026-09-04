package com.lumora.pos.backup;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Chooses the store, once, at startup (M5-06).
 *
 * <p>A bean method rather than two {@code @ConditionalOnProperty} components, because the choice
 * deserves to be readable in one place and to be able to say out loud what it picked. The line it
 * logs is the only thing standing between a correctly deployed backup and a silently ephemeral
 * one: on the free host, a filesystem store <em>works</em> — writes succeed, rows are inserted,
 * the till is told everything is fine — and the archives are gone at the next deploy.
 */
@Configuration
@Profile("cloud")
public class CloudBackupConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudBackupConfiguration.class);

    @Bean
    public CloudBackupStore cloudBackupStore(CloudBackupProperties properties) {
        if (properties.s3().configured()) {
            return new S3BackupStore(properties.s3());
        }
        log.warn(
                "Cloud backups have no object storage configured and will be written to {}. "
                        + "On a host with an ephemeral filesystem this loses every archive at the "
                        + "next deploy. Set LUMORA_BACKUP_S3_* to fix it.",
                properties.directory());
        return new FilesystemBackupStore(Path.of(properties.directory()));
    }
}
