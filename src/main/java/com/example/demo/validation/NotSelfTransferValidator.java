package com.example.demo.validation;

import com.example.demo.facade.dto.CreateTransferRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NotSelfTransferValidator implements ConstraintValidator<NotSelfTransfer, CreateTransferRequest> {

    @Override
    public void initialize(NotSelfTransfer constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(CreateTransferRequest request, ConstraintValidatorContext context) {
        // Null values are handled by @NotNull/@NotBlank annotations
        if (request == null) {
            return true;
        }

        String fromUserId = request.getFromUserId();
        String toUserId = request.getToUserId();

        // If either is null, let @NotBlank handle it
        if (fromUserId == null || toUserId == null) {
            return true;
        }

        // Validate trimmed values are not equal
        return !fromUserId.trim().equals(toUserId.trim());
    }
}
