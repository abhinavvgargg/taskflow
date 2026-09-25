package com.abhinav.taskflow.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes annotation) {
        if (annotation.value() < 0) {
            throw new IllegalArgumentException("@MaxUtf8Bytes value must be >= 0");
        }
        this.maxBytes = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;                     // presence is @NotBlank's job
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
