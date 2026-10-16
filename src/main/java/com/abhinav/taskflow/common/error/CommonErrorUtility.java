package com.abhinav.taskflow.common.error;

import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

public final class CommonErrorUtility {

    // Hibernate's own PostgreSQL rules (SQLState → "violates ... constraint \"name\""), for errors Hibernate didn't wrap
    private static final ViolatedConstraintNameExtractor POSTGRES_NAMES = new PostgreSQLDialect().getViolatedConstraintNameExtractor();

    /**
     * The violated constraint's name, or null. A statement-time violation arrives as Hibernate's
     * ConstraintViolationException, which already carries the name. A violation found at COMMIT (a deferred constraint,
     * like V7's "has an owner" trigger) doesn't: Hibernate reports "Unable to commit" around the driver's raw
     * SQLException, so the name is read from that exception the same way Hibernate would have.
     */
    public static String constraintNameOf(DataIntegrityViolationException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return constraintViolation.getConstraintName();
            }
            if (cause instanceof SQLException sqlException) {
                String name = POSTGRES_NAMES.extractConstraintName(sqlException);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }
}
