package com.example.demo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotSelfTransferValidator.class)
@Documented
public @interface NotSelfTransfer {

    String message() default "Cannot transfer to yourself";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
