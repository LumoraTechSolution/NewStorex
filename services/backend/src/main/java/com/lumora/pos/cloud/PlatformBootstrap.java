package com.lumora.pos.cloud;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first platform admin, and the chicken-and-egg it resolves (M4-08).
 *
 * <p>Everything in M4-08 is done by a platform admin, including creating platform admins. Something
 * outside that loop has to make the first one, and the options are all bad in different ways:
 *
 * <ul>
 *   <li><b>A seeded row with a known password.</b> Every deployment of this build would ship with
 *       the same working credential for the whole estate. Not a starting point that can be made
 *       safe by a note in a README.
 *   <li><b>A signup page.</b> An unauthenticated route that mints super-admins, protected by
 *       nothing except being used quickly. It is a race with the internet, and the internet is
 *       faster.
 *   <li><b>A one-off SQL script.</b> Works, and is exactly the "you have to run Java by hand" that
 *       M4-08 exists to abolish.
 * </ul>
 *
 * <p>So: an email supplied by whoever is deploying, a password this process generates, and both
 * used <b>only</b> when the table is empty. The password is written once to the log, where the
 * operator starting the service is already looking, and never stored in recoverable form. Setting
 * the property again later does nothing, so leaving it in an environment file is not a standing
 * back door — the guard is the state of the table, not the absence of the config.
 */
@Component
@Profile("cloud")
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);

    /** 24 bytes of base64 — comfortably past the 16-character minimum, and typeable if it must be. */
    private static final int PASSWORD_BYTES = 24;

    private final PlatformAdminService admins;
    private final PlatformAuditService audit;
    private final String bootstrapEmail;
    private final String bootstrapName;
    private final SecureRandom random = new SecureRandom();

    public PlatformBootstrap(
            PlatformAdminService admins,
            PlatformAuditService audit,
            @Value("${lumora.platform.bootstrap-email:}") String bootstrapEmail,
            @Value("${lumora.platform.bootstrap-name:Lumora}") String bootstrapName) {
        this.admins = admins;
        this.audit = audit;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapName = bootstrapName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!admins.noneExist()) {
            return;
        }

        if (bootstrapEmail == null || bootstrapEmail.isBlank()) {
            // Warn rather than fail. A cloud with no admin still ingests from every till that
            // already holds a token, and refusing to start would take a working estate down over a
            // missing setting.
            log.warn(
                    "No platform admin exists and lumora.platform.bootstrap-email is not set. "
                            + "Nobody can sign in to /api/platform. Set LUMORA_PLATFORM_BOOTSTRAP_EMAIL "
                            + "and restart to create the first one.");
            return;
        }

        byte[] entropy = new byte[PASSWORD_BYTES];
        random.nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        PlatformAdminService.PlatformAdmin created =
                admins.create(bootstrapEmail, password, bootstrapName);

        // granted_by/admin null: nobody authorised this, the process did, and saying so is more
        // honest than attributing it to the account being created in the same breath.
        audit.record(null, "admin.bootstrap", null, Map.of("email", created.email()));

        log.warn(
                """

                ================ FIRST PLATFORM ADMIN CREATED ================
                  email:    {}
                  password: {}

                  This is the only time this password is shown. Sign in at
                  /api/platform/auth/login and change it. Creating it is
                  recorded in platform_audit.
                ==============================================================
                """,
                created.email(),
                password);
    }
}
