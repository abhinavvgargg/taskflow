package com.abhinav.taskflow.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 §2.1 / decision D2: without a configured signing key, only {@code dev} and {@code test} may start (with a
 * throwaway key); every other profile must refuse to start.
 *
 * <p>Why {@link ApplicationContextRunner} and not a {@code prod} run of the whole app: the runner starts ONLY what it's
 * given (the key configuration, its properties and a clock), so nothing else can fail first. A manual {@code prod} run
 * also lacks an EmailSender, and that failure can hide the one this test is about. No Spring Boot test context, no
 * container: this is a plain, fast unit test.
 */
class JwtKeyConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfig {
    }

    // No key-id / private-key / public-key: the situation a forgotten secret creates in a real deploy
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtPropertiesConfig.class, JwtKeyConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues(
                    "taskflow.security.jwt.issuer=taskflow",
                    "taskflow.security.jwt.audience=taskflow-api",
                    "taskflow.security.jwt.access-token-ttl=15m",
                    "taskflow.security.jwt.clock-skew=5s");

    @Test
    void prod_withoutAKey_refusesToStart_andSaysWhatToSet() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("No JWT signing key configured for profiles [prod]")
                            .hasMessageContaining("TASKFLOW_SECURITY_JWT_PRIVATE_KEY");
                });
    }

    @Test
    void anUnknownProfile_withoutAKey_refusesToStart_too() {
        // The throwaway key is allowed for a LIST of profiles (dev, test), not denied for one (prod): a new or misspelt
        // profile ("staging", "prd") must fail like prod, not quietly sign with a key that changes on every restart
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("staging"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void dev_withoutAKey_startsWithAnEphemeralKey() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JwtSigningKey.class);
                    assertThat(context.getBean(JwtSigningKey.class).keyId()).startsWith("ephemeral-");
                });
    }
}
