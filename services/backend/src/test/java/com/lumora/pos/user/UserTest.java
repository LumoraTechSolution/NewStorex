package com.lumora.pos.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.user.UserService.UserRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Users, roles and PINs (M3-08).
 *
 * <p>The role tests are pure and could live anywhere; they are here because what a role may do and
 * what the database will store have to stay in step, and splitting them across two files is how
 * they drift apart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class UserTest {

    @Autowired UserService users;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ roles (pure)

    /**
     * The permission that guards every other one belongs to exactly one role.
     *
     * <p>A MANAGER who could appoint managers is an owner in everything but name, and the shop
     * would have no way to tell the two apart afterwards.
     */
    @Test
    void onlyTheOwnerMayManageUsers() {
        assertThat(Role.OWNER.can(Permission.MANAGE_USERS)).isTrue();
        assertThat(Role.MANAGER.can(Permission.MANAGE_USERS)).isFalse();
        assertThat(Role.SUPERVISOR.can(Permission.MANAGE_USERS)).isFalse();
        assertThat(Role.CASHIER.can(Permission.MANAGE_USERS)).isFalse();
    }

    /**
     * A cashier sells and cannot hand money back. This is the M2-07 gate expressed as a role, and
     * the reason SUPERVISOR exists at all: in a small shop the person on the floor at 8pm is not
     * the manager, and a returns policy needing the manager's own PIN ends with that PIN written
     * on the till.
     */
    @Test
    void aCashierSellsButCannotRefund() {
        assertThat(Role.CASHIER.can(Permission.SELL)).isTrue();
        assertThat(Role.CASHIER.can(Permission.RUN_SHIFT)).isTrue();
        assertThat(Role.CASHIER.can(Permission.AUTHORISE_REFUND)).isFalse();
        assertThat(Role.CASHIER.can(Permission.BACK_OFFICE)).isFalse();

        assertThat(Role.SUPERVISOR.can(Permission.AUTHORISE_REFUND)).isTrue();
        assertThat(Role.SUPERVISOR.can(Permission.BACK_OFFICE)).isFalse();
    }

    /** Everyone works the till. A role that cannot sell would be a role with no reason to exist. */
    @Test
    void everyRoleCanSell() {
        for (Role role : Role.values()) {
            assertThat(role.can(Permission.SELL)).as("%s can sell", role).isTrue();
        }
    }

    /**
     * The enum and the V109 CHECK constraint list the same four names.
     *
     * <p>The duplication is deliberate — the database is the last line — so this test is what
     * stops it becoming a divergence. Adding a role to one side alone fails here.
     */
    @Test
    void everyRoleTheEnumKnowsIsStorable() {
        ShopFixture.Shop shop = fixtures.seed();
        for (Role role : Role.values()) {
            String code = "ROLE" + role.ordinal();
            UserRow created =
                    users.create(
                            shop.tenantId(), UUID.randomUUID(), code, "Role probe", role, "9999");
            assertThat(created.role()).isEqualTo(role);
        }
    }

    /**
     * A role string the database holds but this build does not know throws rather than guessing.
     *
     * <p>Guessing CASHIER silently demotes somebody and guessing OWNER silently promotes them.
     * Neither is a decision a parser is entitled to make, and the realistic cause — a till rolled
     * back to an older build — is one where being loud is the whole point.
     */
    @Test
    void anUnknownStoredRoleIsRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> Role.of("ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("newer version");
    }

    // ------------------------------------------------------------- authentication

    @Test
    void theRightCodeAndPinIdentifyTheUser() {
        ShopFixture.Shop shop = fixtures.seed();

        Operator operator =
                users.authenticate(shop.tenantId(), ShopFixture.MANAGER_CODE, ShopFixture.MANAGER_PIN);

        assertThat(operator.id()).isEqualTo(shop.managerId());
        assertThat(operator.role()).isEqualTo(Role.MANAGER);
    }

    /** The code is stored upper-case, so typing it in lower case is the same person. */
    @Test
    void theUserCodeIsNotCaseSensitive() {
        ShopFixture.Shop shop = fixtures.seed();

        Operator operator =
                users.authenticate(
                        shop.tenantId(),
                        ShopFixture.MANAGER_CODE.toLowerCase(java.util.Locale.ROOT),
                        ShopFixture.MANAGER_PIN);

        assertThat(operator.id()).isEqualTo(shop.managerId());
    }

    /**
     * A wrong PIN and a code nobody holds fail in the same words.
     *
     * <p>Distinguishing them would confirm which codes exist and turn one search into two much
     * smaller ones. The messages are asserted equal rather than merely both-failing, because
     * "both throw" would still pass if one of them started naming the reason.
     */
    @Test
    void anUnknownCodeAndAWrongPinAreIndistinguishable() {
        ShopFixture.Shop shop = fixtures.seed();

        Throwable wrongPin =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> users.authenticate(shop.tenantId(), ShopFixture.MANAGER_CODE, "0000"));
        Throwable noSuchUser =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> users.authenticate(shop.tenantId(), "GHOST", ShopFixture.MANAGER_PIN));

        assertThat(wrongPin).hasMessage(noSuchUser.getMessage());
        assertThat(wrongPin).hasMessageContaining("not recognised");
    }

    /** A deactivated user is not a user who can sign in, whatever their PIN still hashes to. */
    @Test
    void aDeactivatedUserCannotAuthenticate() {
        ShopFixture.Shop shop = fixtures.seed();
        UserRow leaver =
                users.create(
                        shop.tenantId(), UUID.randomUUID(), "LEAVER", "Left in June", Role.CASHIER, "4821");

        users.setActive(shop.tenantId(), leaver.id(), false);

        assertThatThrownBy(() -> users.authenticate(shop.tenantId(), "LEAVER", "4821"))
                .hasMessageContaining("not recognised");
    }

    /**
     * {@code authorise} returns the operator or throws. It has no third outcome, and no boolean an
     * {@code if} could forget to check.
     */
    @Test
    void authoriseRefusesAPermissionTheRoleDoesNotHold() {
        ShopFixture.Shop shop = fixtures.seed();

        assertThatThrownBy(
                        () ->
                                users.authorise(
                                        shop.tenantId(),
                                        ShopFixture.CASHIER_CODE,
                                        ShopFixture.MANAGER_PIN,
                                        Permission.AUTHORISE_REFUND))
                .hasMessageContaining("cannot authorise refunds");

        Operator allowed =
                users.authorise(
                        shop.tenantId(),
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        Permission.AUTHORISE_REFUND);
        assertThat(allowed.id()).isEqualTo(shop.managerId());
    }

    // ------------------------------------------------------------- administration

    @Test
    void twoUsersCannotShareACode() {
        ShopFixture.Shop shop = fixtures.seed();
        users.create(shop.tenantId(), UUID.randomUUID(), "DUP", "First", Role.CASHIER, "1111");

        assertThatThrownBy(
                        () ->
                                users.create(
                                        shop.tenantId(), UUID.randomUUID(), "dup", "Second", Role.CASHIER, "2222"))
                .hasMessageContaining("already exists");
    }

    /** A PIN is typed on a numeric keypad. Anything else is a PIN nobody can enter at the till. */
    @Test
    void aPinMustBeFourDigitsOrMore() {
        ShopFixture.Shop shop = fixtures.seed();

        assertThatThrownBy(
                        () -> users.create(shop.tenantId(), UUID.randomUUID(), "SHORT", "Nope", Role.CASHIER, "12"))
                .hasMessageContaining("at least 4");
        assertThatThrownBy(
                        () ->
                                users.create(
                                        shop.tenantId(), UUID.randomUUID(), "ALPHA", "Nope", Role.CASHIER, "pass"))
                .hasMessageContaining("digits only");
    }

    /** The PIN is never stored, and what is stored is never the PIN. */
    @Test
    void thePinIsHashedNotKept() {
        ShopFixture.Shop shop = fixtures.seed();
        UserRow user =
                users.create(shop.tenantId(), UUID.randomUUID(), "HASH", "Hashy", Role.CASHIER, "8642");

        String stored =
                jdbc.queryForObject("SELECT pin_hash FROM users WHERE id = ?", String.class, user.id());

        assertThat(stored).doesNotContain("8642").startsWith("$2");
    }

    /**
     * A rename must not be able to reset a credential.
     *
     * <p>{@code update} and {@code setPin} are separate for this reason: an endpoint taking a whole
     * user object and re-hashing whatever sits in its {@code pin} field is one forgotten field away
     * from silently changing somebody's PIN during a rename.
     */
    @Test
    void renamingAUserLeavesTheirPinAlone() {
        ShopFixture.Shop shop = fixtures.seed();
        UserRow user =
                users.create(shop.tenantId(), UUID.randomUUID(), "RENAME", "Before", Role.CASHIER, "7531");

        users.update(shop.tenantId(), user.id(), "After", Role.SUPERVISOR);

        Operator operator = users.authenticate(shop.tenantId(), "RENAME", "7531");
        assertThat(operator.displayName()).isEqualTo("After");
        assertThat(operator.role()).isEqualTo(Role.SUPERVISOR);
    }

    @Test
    void settingAPinReplacesTheOldOne() {
        ShopFixture.Shop shop = fixtures.seed();
        UserRow user =
                users.create(shop.tenantId(), UUID.randomUUID(), "ROTATE", "Rotates", Role.CASHIER, "1234");

        users.setPin(shop.tenantId(), user.id(), "5678");

        assertThat(users.authenticate(shop.tenantId(), "ROTATE", "5678").id()).isEqualTo(user.id());
        assertThatThrownBy(() -> users.authenticate(shop.tenantId(), "ROTATE", "1234"))
                .hasMessageContaining("not recognised");
    }

    /**
     * The lockout this guard exists to prevent is total: MANAGE_USERS belongs to OWNER alone, so
     * the last owner leaving takes with them the ability to appoint a replacement. The only way
     * back is an edit to the shop's database by hand.
     */
    @Test
    void theLastOwnerCannotBeDeactivatedOrDemoted() {
        ShopFixture.Shop shop = fixtures.seed();
        UserRow owner =
                users.create(shop.tenantId(), UUID.randomUUID(), "SOLE", "Sole Owner", Role.OWNER, "4444");

        // Every test in this class shares the one tenant a desktop database may hold, so any
        // owner another test left behind has to stand down before "the last owner" means this
        // one. Done through the service rather than SQL, so the guard is what allows each step.
        for (UserRow other : users.list(shop.tenantId())) {
            if (other.role() == Role.OWNER && other.active() && other.id() != owner.id()) {
                users.setActive(shop.tenantId(), other.id(), false);
            }
        }

        assertThatThrownBy(() -> users.setActive(shop.tenantId(), owner.id(), false))
                .hasMessageContaining("only active owner");
        assertThatThrownBy(() -> users.update(shop.tenantId(), owner.id(), "Sole Owner", Role.MANAGER))
                .hasMessageContaining("only active owner");

        // With a second owner in place, the first may go.
        users.create(shop.tenantId(), UUID.randomUUID(), "SECOND", "Second Owner", Role.OWNER, "5555");
        assertThat(users.setActive(shop.tenantId(), owner.id(), false).active()).isFalse();
    }

    /**
     * There is no delete, and the reason is the audit trail: every {@code created_by} and {@code
     * authorised_by} column in the schema is a foreign key here. A user who has done anything
     * cannot be removed without either failing the constraint or loosening it — and loosening it
     * is how "who authorised that refund" stops having an answer for exactly the people most
     * likely to have left in a hurry.
     */
    @Test
    void aUserWhoHasActedCannotBeDeletedOutFromUnderTheirHistory() {
        ShopFixture.Shop shop = fixtures.seed();

        // One movement attributed to them is enough — that is the whole point of the constraint.
        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                VALUES (?, ?, ?, (SELECT id FROM products WHERE client_uuid = ?), -1, 'SALE', ?)
                """,
                UUID.randomUUID(),
                shop.tenantId(),
                shop.branchId(),
                shop.productUuid(),
                shop.managerId());

        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", shop.managerId()))
                .hasMessageContaining("violates foreign key constraint");
    }

    /**
     * The other half of the same constraint: an audit column cannot name somebody who does not
     * exist. Before V109 it could, and for eight migrations every one of them did.
     */
    @Test
    void anAuditColumnCannotNameAUserWhoDoesNotExist() {
        ShopFixture.Shop shop = fixtures.seed();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO stock_movements (
                                            client_uuid, tenant_id, branch_id, product_id,
                                            qty_delta, reason, created_by)
                                        VALUES (?, ?, ?, (SELECT id FROM products WHERE client_uuid = ?),
                                                -1, 'SALE', ?)
                                        """,
                                        UUID.randomUUID(),
                                        shop.tenantId(),
                                        shop.branchId(),
                                        shop.productUuid(),
                                        -999L))
                .hasMessageContaining("violates foreign key constraint");
    }

    @Test
    void theBackOfficeListsLeaversLastButStillListsThem() {
        ShopFixture.Shop shop = fixtures.seed();
        UserRow leaver =
                users.create(shop.tenantId(), UUID.randomUUID(), "GONE", "Gone Away", Role.CASHIER, "6666");
        users.setActive(shop.tenantId(), leaver.id(), false);

        List<UserRow> listed = users.list(shop.tenantId());

        // Still listed — a leaver who vanished from the back office is a leaver whose refunds
        // nobody can explain.
        assertThat(listed).extracting(UserRow::id).contains(leaver.id());

        // And listed after everyone still working. Asserted as an invariant over the whole list
        // rather than as a position, because every test here shares one tenant and the list is
        // not this test's alone.
        int lastActive = -1;
        int firstInactive = listed.size();
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).active()) {
                lastActive = i;
            } else if (firstInactive == listed.size()) {
                firstInactive = i;
            }
        }
        assertThat(lastActive).isLessThan(firstInactive);
    }
}
