package com.abhinav.taskflow.common.persistence;

import jakarta.persistence.*;
import lombok.Getter;

@MappedSuperclass
@Getter
public abstract class IdentifiedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "global_gen")
    @SequenceGenerator(
            name = "global_gen",
            sequenceName = "global_id_seq",
            allocationSize = 50,
            initialValue = 1
    )
    private Long id;
}
