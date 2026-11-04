package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.persistence.BaseEntity;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.util.Normalize;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.util.Assert;

import java.time.Instant;

// An ACCESS table read from two sides (the organization's admins, the invitee across organizations), so never @TenantId
// (D8). Insert-only from Java: accept, decline and revoke are conditional @Modifying UPDATEs. The outcome columns are
// mapped only so those statements can name them; updatable = false keeps an entity flush from writing a stale state back.
@Entity
@Table(name = "organization_invitations")
@Getter
public class OrganizationInvitation extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long organizationId;

    @Column(nullable = false, updatable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private OrganizationRole role;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(updatable = false)
    private Instant acceptedAt;

    @Column(updatable = false)
    private Instant declinedAt;

    @Column(updatable = false)
    private Instant revokedAt;

    protected OrganizationInvitation() {}

    private OrganizationInvitation(Long organizationId, String email, OrganizationRole role, Instant expiresAt) {
        this.organizationId = organizationId;
        this.email = email;
        this.role = role;
        this.expiresAt = expiresAt;
    }

    /** {@code email} must already be normalised and {@code expiresAt} computed from the same clock reading as the revoke. */
    public static OrganizationInvitation create(Long organizationId, String email, OrganizationRole role, Instant expiresAt) {
        Assert.notNull(organizationId, "organizationId must not be null");
        Assert.hasText(email, "email must not be blank");
        Assert.isTrue(email.equals(Normalize.normalizeEmail(email)), "email must be normalised");
        Assert.notNull(role, "role must not be null");
        Assert.isTrue(role != OrganizationRole.OWNER, "ownership only moves by transfer");
        Assert.notNull(expiresAt, "expiresAt must not be null");
        return new OrganizationInvitation(organizationId, email, role, expiresAt);
    }

    // No email: it's PII, and toString ends up in logs
    @Override
    public String toString() {
        return "OrganizationInvitation{id=" + getId()
                + ", organizationId=" + organizationId
                + ", role=" + role
                + ", expiresAt=" + expiresAt + '}';
    }
}
