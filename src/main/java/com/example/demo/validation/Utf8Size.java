package com.example.demo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = Utf8SizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8Size {
    String message() default "UTF-8のbyte数が上限を超えています";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    int max();
}
