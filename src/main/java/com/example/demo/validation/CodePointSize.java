package com.example.demo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {
    String message() default "上限以内のUnicodeコードポイントで入力してください";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    int max();
}
