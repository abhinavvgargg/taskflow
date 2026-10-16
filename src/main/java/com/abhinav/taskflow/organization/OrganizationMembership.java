package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.persistence.BaseEntity;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.util.Assert;

// An ACCESS table, not tenant-owned data: the gate reads it before any tenant is known, and "my organizations" reads it
// across organizations, so it never gets @TenantId. Every query on it names organization_id or user_account_id itself.
// Insert-only from Java: role changes (§3.2) are conditional @Modifying UPDATEs carrying the expected role in their
// WHERE. updatable = false keeps an accidental entity flush from writing a stale role back.
@Entity
@Table(name = "organization_memberships")
@Getter
public class OrganizationMembership extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long organizationId;

    @Column(nullable = false, updatable = false)
    private Long userAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private OrganizationRole role;

    protected OrganizationMembership() {}

    private OrganizationMembership(Long organizationId, Long userAccountId, OrganizationRole role) {
        this.organizationId = organizationId;
        this.userAccountId = userAccountId;
        this.role = role;
    }

    public static OrganizationMembership of(Long organizationId, Long userAccountId, OrganizationRole role) {
        Assert.notNull(organizationId, "organizationId must not be null");
        Assert.notNull(userAccountId, "userAccountId must not be null");
        Assert.notNull(role, "role must not be null");
        return new OrganizationMembership(organizationId, userAccountId, role);
    }

    @Override
    public String toString() {
        return "OrganizationMembership{id=" + getId()
                + ", organizationId=" + organizationId
                + ", userAccountId=" + userAccountId
                + ", role=" + role + '}';
    }
}
