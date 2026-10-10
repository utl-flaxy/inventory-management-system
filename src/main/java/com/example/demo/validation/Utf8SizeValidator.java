package com.example.demo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class Utf8SizeValidator implements ConstraintValidator<Utf8Size, String> {
    private int max;

    @Override
    public void initialize(Utf8Size constraint) { max = constraint.max(); }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null/blankと文字数はNotBlank/Sizeに委ね、BCryptのUTF-8 byte上限を検証する。
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
