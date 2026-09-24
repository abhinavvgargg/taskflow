package com.abhinav.taskflow.user;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.config.AuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA slice against real Postgres (Testcontainers), with Flyway's V2 applied. Proves the mapping and the
 * database constraints. Same imports as OrganizationRepositoryTest, so both share one context.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, AuditingConfig.class})
class UserAccountRepositoryTest {

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void findByEmail_findsThePersistedRow() {
        entityManager.persistAndFlush(user("alice@example.com", "alice"));
        entityManager.clear();   // forget the in-memory copy, so the read below really hits Postgres

        assertThat(userAccountRepository.findByEmail("alice@example.com"))
                .get()
                .extracting(UserAccount::getUsername)
                .isEqualTo("alice");
        assertThat(userAccountRepository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void factoryDefaults_areActuallyStored() {
        UserAccount reloaded = entityManager.persistFlushFind(user("bob@example.com", "bob"));

        assertThat(reloaded.getRole()).isEqualTo(UserRole.USER);
        assertThat(reloaded.getTimezone()).isEqualTo("UTC");
        assertThat(reloaded.getFailedLoginAttempts()).isZero();
        assertThat(reloaded.getEmailVerifiedAt()).isNull();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void role_isStoredAsText_notAsAnOrdinal() {
        UserAccount saved = entityManager.persistAndFlush(user("carol@example.com", "carol"));

        // Read the raw column with SQL: through JPA, ORDINAL and STRING would both look like UserRole.USER.
        Object rawRole = entityManager.getEntityManager()
                .createNativeQuery("select role from user_accounts where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(rawRole).isEqualTo("USER");
    }

    @Test
    void duplicateEmail_violatesTheUniqueConstraint() {
        userAccountRepository.saveAndFlush(user("dave@example.com", "dave"));

        // saveAndFlush, not save: without the flush the INSERT never reaches Postgres (testing guide §6.3)
        assertThatThrownBy(() -> userAccountRepository.saveAndFlush(user("dave@example.com", "dave2")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_user_accounts_email");
    }

    @Test
    void uppercaseEmail_isRejectedByTheDatabase() {
        // The final guard for any code path that forgets to normalise.
        assertThatThrownBy(() -> userAccountRepository.saveAndFlush(user("Erin@Example.com", "erin")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_accounts_email_lowercase");
    }

    @Test
    void usernameContainingAt_isRejectedByTheDatabase() {
        // Keeps usernames and emails in separate namespaces (decision 3).
        assertThatThrownBy(() -> userAccountRepository.saveAndFlush(user("frank@example.com", "frank@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_accounts_username_format");
    }

    private static UserAccount user(String email, String username) {
        // The DB only checks the hash has an {id} prefix, so no real bcrypt is needed here.
        return UserAccount.createUserAccount(email, username, "Test User", "{bcrypt}not-a-real-hash", Instant.now());
    }
}
