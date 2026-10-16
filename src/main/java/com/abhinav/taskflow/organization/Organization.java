package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import org.springframework.util.Assert;

// Created through create(), never half-built through setters. Its owner is a separate row (OrganizationMembership),
// inserted in the same transaction: V7's deferred trigger refuses to commit an organization without one.
@Entity
@Table(name = "organizations")
@Getter
public class Organization extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 63, unique = true)
    private String slug;

    protected Organization() {}

    private Organization(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public static Organization create(String name, String slug) {
        Assert.hasText(name, "name must not be blank");
        Assert.hasText(slug, "slug must not be blank");
        return new Organization(name, slug);
    }
}
