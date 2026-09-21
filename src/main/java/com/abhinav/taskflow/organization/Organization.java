package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "organizations")
@NoArgsConstructor
@Getter
@Setter
public class Organization extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 63, unique = true)
    private String slug;
}
