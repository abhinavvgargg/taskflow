package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.config.AuditingConfig;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.user.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V7's owner invariant and the gate's lookup query, against the real schema.
 *
 * <p>Same annotations as the other JPA slices, so the cached context (and its container) is reused.
 * Every test here ROLLS BACK, so V7's deferred "has an owner" trigger would never run on its own: the tests that mean
 * to prove it flush, then say {@code SET CONSTRAINTS ALL IMMEDIATE}, which runs the pending deferred checks right away.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, AuditingConfig.class})
class OrganizationMembershipRepositoryTest {

    @Autowired
    OrganizationMembershipRepository membershipRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;   // joins the test's transaction (JpaTransactionManager exposes its connection)

    private UserAccount alice;
    private UserAccount bob;

    @BeforeEach
    void createUsers() {
        alice = entityManager.persist(user("alice"));
        bob = entityManager.persist(user("bob"));
    }

    @Test
    void anOrganizationWithoutAnOwner_failsTheDeferredCheck() {
        organizationRepository.saveAndFlush(Organization.create("Ownerless", "ownerless"));   // the INSERT itself succeeds

        assertThatThrownBy(this::runDeferredChecksNow)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organizations_has_owner");
    }

    @Test
    void anOrganizationWithItsOwner_passesTheDeferredCheck() {
        Organization acme = organizationRepository.saveAndFlush(Organization.create("Acme", "acme"));
        membershipRepository.saveAndFlush(OrganizationMembership.of(acme.getId(), alice.getId(), OrganizationRole.OWNER));

        runDeferredChecksNow();   // no exception
    }

    @Test
    void aSecondOwner_isRefusedImmediately_byThePartialUniqueIndex() {
        Organization acme = organizationWithOwner("acme", alice);

        // No SET CONSTRAINTS here: the one-owner index is checked at the statement, not at commit
        assertThatThrownBy(() -> membershipRepository.saveAndFlush(
                OrganizationMembership.of(acme.getId(), bob.getId(), OrganizationRole.OWNER)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_organization_memberships_one_owner");
    }

    @Test
    void aSecondMembershipForTheSameUser_isRefused() {
        Organization acme = organizationWithOwner("acme", alice);

        assertThatThrownBy(() -> membershipRepository.saveAndFlush(
                OrganizationMembership.of(acme.getId(), alice.getId(), OrganizationRole.MEMBER)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_organization_memberships_org_user");
    }

    @Test
    void findRole_returnsTheRoleInThatOrganization_andNothingForAnotherOrganizationsMember() {
        Organization acme = organizationWithOwner("acme", alice);
        Organization globex = organizationWithOwner("globex", bob);
        entityManager.clear();

        assertThat(membershipRepository.findRole(acme.getId(), alice.getId())).contains(OrganizationRole.OWNER);
        // bob is an owner, but of ANOTHER organization: the gate must see "not a member" here
        assertThat(membershipRepository.findRole(acme.getId(), bob.getId())).isEmpty();
        assertThat(membershipRepository.findRole(globex.getId(), alice.getId())).isEmpty();
        assertThat(membershipRepository.findRole(999_999_999L, alice.getId())).isEmpty();
    }

    private Organization organizationWithOwner(String slug, UserAccount owner) {
        Organization organization = organizationRepository.saveAndFlush(Organization.create(slug, slug));
        membershipRepository.saveAndFlush(OrganizationMembership.of(organization.getId(), owner.getId(), OrganizationRole.OWNER));
        return organization;
    }

    private void runDeferredChecksNow() {
        jdbcTemplate.execute("set constraints all immediate");
    }

    private static UserAccount user(String username) {
        UserAccount user = UserAccount.createUserAccount(username + "@example.com", username, "Test " + username, "{noop}x", Instant.now());
        user.markEmailVerified(Instant.now());
        return user;
    }
}
