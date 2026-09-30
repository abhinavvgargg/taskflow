package com.abhinav.taskflow.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.annotation.*;

@NotBlank
@Size(min = 12, message = "must be at least 12 characters")
@MaxUtf8Bytes(72)
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Constraint(validatedBy = {})
public @interface ValidPassword {

    String message() default "password must be at least 12 characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
