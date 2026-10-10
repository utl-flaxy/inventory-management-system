package com.example.demo.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NonZero.Validator.class)
public @interface NonZero {
    String message() default "deltaは0以外を入力してください";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NonZero, Integer> {
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || value != 0; // nullは@NotNullが担当
        }
    }
}
