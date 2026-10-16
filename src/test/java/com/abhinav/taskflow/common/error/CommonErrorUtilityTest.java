package com.abhinav.taskflow.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class CommonErrorUtilityTest {

    @Test
    void aStatementTimeViolation_isNamedByHibernatesException() {
        SQLException sql = new SQLException("ERROR: duplicate key value violates unique constraint \"uk_organizations_slug\"", "23505");
        var ex = new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException("insert failed", sql, "uk_organizations_slug"));

        assertThat(CommonErrorUtility.constraintNameOf(ex)).isEqualTo("uk_organizations_slug");
    }

    // What a deferred violation looks like (observed in §3.1's deliberate failure 1): Spring translates Hibernate's
    // "Unable to commit" by SQLState, and the only cause left is the driver's SQLException
    @Test
    void aCommitTimeViolation_isNamedFromTheRawSqlException() {
        SQLException sql = new SQLException("ERROR: organization 152 violates check constraint \"ck_organizations_has_owner\"", "23514");
        var ex = new DataIntegrityViolationException("Hibernate transaction: Unable to commit against JDBC Connection", sql);

        assertThat(CommonErrorUtility.constraintNameOf(ex)).isEqualTo("ck_organizations_has_owner");
    }

    @Test
    void noRecognisableConstraint_isNull() {
        var ex = new DataIntegrityViolationException("something", new SQLException("ERROR: something else", "23000"));

        assertThat(CommonErrorUtility.constraintNameOf(ex)).isNull();
    }
}
