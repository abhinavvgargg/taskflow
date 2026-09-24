package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import com.abhinav.taskflow.common.util.Normalize;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskflowUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        //Here username will be email in userAccount
        String normalizedEmail = Normalize.normalizeEmail(username);

        UserAccount userAccount = userAccountRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        boolean isAccountEnabled = userAccount.getEmailVerifiedAt() != null;
        boolean isAccountNonLocked = userAccount.getLockedUntil() == null || !clock.instant().isBefore(userAccount.getLockedUntil());

        GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + userAccount.getRole().name());

        return new TaskflowPrincipal(userAccount.getId(), userAccount.getUsername(), userAccount.getEmail(), userAccount.getPasswordHash(), List.of(authority), isAccountEnabled, isAccountNonLocked);
    }
}
