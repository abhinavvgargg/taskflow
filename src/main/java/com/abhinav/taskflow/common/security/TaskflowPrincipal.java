package com.abhinav.taskflow.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class TaskflowPrincipal implements UserDetails {

    @Getter
    private final Long id;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public TaskflowPrincipal(Long id, String username, String email, String passwordHash, List<GrantedAuthority> authorities,  boolean enabled, boolean accountNonLocked) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.authorities = authorities;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public String getAppUsername() {
        return username;
    }
}
