package com.abhinav.taskflow.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Constraint(validatedBy = {MaxUtf8BytesValidator.class})
public @interface MaxUtf8Bytes {

    int value();

    String message() default "{com.abhinav.taskflow.common.validator.MaxUtf8Bytes.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
