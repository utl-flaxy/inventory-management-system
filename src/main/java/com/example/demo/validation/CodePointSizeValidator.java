package com.example.demo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {
    private int max;

    @Override
    public void initialize(CodePointSize constraint) { max = constraint.max(); }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Required/blank checks remain with NotBlank. Supplementary scalars count once.
        if (value == null) return true;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if ((codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                    || ++count > max) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
