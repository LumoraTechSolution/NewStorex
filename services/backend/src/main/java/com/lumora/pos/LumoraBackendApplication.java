package com.lumora.pos;

import com.lumora.pos.backup.CloudBackupProperties;
import com.lumora.pos.sync.SyncProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One jar, two profiles.
 *
 * <p>{@code desktop} runs on the shop PC bound to 127.0.0.1 and is the source of truth for a sale.
 * {@code cloud} runs hosted and multi-tenant, and only ever receives what the outbox pushes to it.
 *
 * <p>Scheduling is enabled here because the outbox drain (M0-08) is a {@code @Scheduled} job. It is
 * the only thing in the system allowed to touch the network on a sale's behalf, and it does so long
 * after the sale is already final.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SyncProperties.class, CloudBackupProperties.class})
public class LumoraBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(LumoraBackendApplication.class, args);
    }
}
