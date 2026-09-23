package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.config.AuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import({TestcontainersConfiguration.class, AuditingConfig.class})
class OrganizationRepositoryTest {

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void existsBySlug_findsPersistedRow() {
        Organization org = new Organization();
        org.setName("Acme Corp");
        org.setSlug("acme-corp");
        entityManager.persistAndFlush(org);   // actually INSERT
        entityManager.clear();                // forget it, so reads hit the DB

        assertThat(organizationRepository.existsOrganizationBySlug("acme-corp")).isTrue();
        assertThat(organizationRepository.existsOrganizationBySlug("nope")).isFalse();
    }

    @Test
    void persist_populatesAuditFields() {
        Organization org = organization("Audit Co", "audit-co");

        Organization reloaded = entityManager.persistFlushFind(org);   // INSERT, detach, SELECT

        assertThat(reloaded.getCreatedBy()).isEqualTo("system");
        assertThat(reloaded.getUpdatedBy()).isEqualTo("system");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(reloaded.getCreatedAt());   // both read from the DB
        // in-memory Instant vs the value Postgres stored: never compare exactly (ns vs µs)
        assertThat(reloaded.getCreatedAt()).isCloseTo(org.getCreatedAt(), within(1, ChronoUnit.MILLIS));
    }

    @Test
    void save_duplicateSlug_violatesUniqueConstraint() {
        organizationRepository.saveAndFlush(organization("First", "dup-slug"));

        // saveAndFlush, not save: without the flush the INSERT never reaches Postgres
        assertThatThrownBy(() -> organizationRepository.saveAndFlush(organization("Second", "dup-slug")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_organizations_slug");
    }

    private static Organization organization(String name, String slug) {
        Organization org = new Organization();
        org.setName(name);
        org.setSlug(slug);
        return org;
    }
}
