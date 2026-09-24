package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Unit test: no Spring, no database. The repository is a Mockito fake and time is a fixed Clock,
 * so every rule that depends on "now" can be tested at an exact instant.
 */
@ExtendWith(MockitoExtension.class)
class TaskflowUserDetailsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final String EMAIL = "alice@example.com";

    @Mock
    UserAccountRepository userAccountRepository;

    TaskflowUserDetailsService service;

    @BeforeEach
    void setUp() {
        // Built by hand rather than with @InjectMocks: the Clock is a real (fixed) Clock, not a mock.
        service = new TaskflowUserDetailsService(userAccountRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void loadUser_normalisesTheEmailBeforeTheLookup() {
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(verifiedUser()));

        TaskflowPrincipal principal = (TaskflowPrincipal) service.loadUserByUsername("  Alice@Example.COM ");

        assertThat(principal.getUsername()).isEqualTo(EMAIL);
    }

    @Test
    void loadUser_unknownEmail_throwsWithoutLeakingTheEmail() {
        given(userAccountRepository.findByEmail("nobody@example.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nobody@example.com");
    }

    @Test
    void principal_carriesIdAndAppUsername_separateFromTheLoginEmail() {
        UserAccount user = verifiedUser();
        ReflectionTestUtils.setField(user, "id", 42L);
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));

        TaskflowPrincipal principal = (TaskflowPrincipal) service.loadUserByUsername(EMAIL);

        assertThat(principal.getId()).isEqualTo(42L);
        assertThat(principal.getAppUsername()).isEqualTo("alice");   // what the auditor writes
        assertThat(principal.getUsername()).isEqualTo(EMAIL);        // what Spring Security logs in with
    }

    @Test
    void verifiedUser_isEnabled() {
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(verifiedUser()));

        assertThat(service.loadUserByUsername(EMAIL).isEnabled()).isTrue();
    }

    @Test
    void unverifiedUser_isDisabled() {
        UserAccount unverified = UserAccount.createUserAccount(EMAIL, "alice", "Alice", "{bcrypt}hash", NOW);
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(unverified));

        assertThat(service.loadUserByUsername(EMAIL).isEnabled()).isFalse();
    }

    @Test
    void userWithNoLock_isNotLocked() {
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(verifiedUser()));

        assertThat(service.loadUserByUsername(EMAIL).isAccountNonLocked()).isTrue();
    }

    /**
     * The lock boundary, tested at exact instants around locked_until.
     * The rule: locked strictly before locked_until, unlocked from locked_until onward.
     * (This is the test that would have caught the isAfter/isBefore inversion.)
     */
    @ParameterizedTest(name = "locked_until = now {0}s  ->  accountNonLocked = {1}")
    @CsvSource({
            "+600, false",   // locked for another 10 minutes
            "+1,   false",   // one second left
            "0,    true",    // exactly at locked_until: the lock has ended
            "-1,   true",    // expired a second ago
    })
    void lockBoundary(long lockedUntilOffsetSeconds, boolean expectedNonLocked) {
        UserAccount user = verifiedUser();
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.plus(Duration.ofSeconds(lockedUntilOffsetSeconds)));
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));

        assertThat(service.loadUserByUsername(EMAIL).isAccountNonLocked()).isEqualTo(expectedNonLocked);
    }

    @ParameterizedTest
    @CsvSource({"USER, ROLE_USER", "ADMIN, ROLE_ADMIN"})
    void role_becomesAnAuthorityWithTheRolePrefix(UserRole role, String expectedAuthority) {
        UserAccount user = verifiedUser();
        ReflectionTestUtils.setField(user, "role", role);
        given(userAccountRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));

        assertThat(service.loadUserByUsername(EMAIL).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(expectedAuthority);
    }

    private static UserAccount verifiedUser() {
        UserAccount user = UserAccount.createUserAccount(EMAIL, "alice", "Alice", "{bcrypt}hash", NOW);
        user.markEmailVerified(NOW);
        return user;
    }
}
