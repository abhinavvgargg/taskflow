package com.abhinav.taskflow;

import com.abhinav.taskflow.common.mail.CapturingEmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }

    /**
     * The test inbox. Registered here, in the configuration every database-backed test already imports, so it
     * adds no new application context (and no second Postgres container).
     *
     * <p>{@code @Primary} so it wins even if a test ever runs with the {@code dev} profile, where
     * {@code LoggingEmailSender} is also a bean; otherwise injecting {@code EmailSender} would find two.
     */
    @Bean
    @Primary
    CapturingEmailSender capturingEmailSender() {
        return new CapturingEmailSender();
    }

    /**
     * The clock every Clock-injecting bean gets in tests (the issuer, the JWT decoder's expiry check, sessions,
     * lockout, tokens). {@code @Primary} so it wins over TimeConfig's {@code Clock.systemUTC()}. Declared here, in the
     * shared configuration, for the same reason as the inbox above: a {@code @MockitoBean Clock} or a per-class
     * {@code @TestConfiguration} would start another application context and another Postgres container.
     */
    @Bean
    @Primary
    AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }
}
