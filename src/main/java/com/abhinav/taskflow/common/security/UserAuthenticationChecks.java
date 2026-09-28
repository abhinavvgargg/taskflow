package com.abhinav.taskflow.common.security;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;

public class UserAuthenticationChecks {

    protected static class UserPreAuthenticationChecks implements UserDetailsChecker {

        @Override
        public void check(UserDetails toCheck) {
            if (!toCheck.isAccountNonLocked()) {
                throw new LockedException("User account is locked");
            }
        }
    }

    protected static class UserPostAuthenticationChecks implements UserDetailsChecker {

        @Override
        public void check(UserDetails toCheck) {
            if (!toCheck.isEnabled()) {
                throw new DisabledException("User account is disabled");
            }
        }
    }
}
