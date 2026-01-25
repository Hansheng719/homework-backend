package com.example.demo.facade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Create User Request DTO
 *
 * 用於接收建立使用者的請求參數
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    /**
     * 使用者 ID
     * 要求：3-50 字元，非空
     */
    @NotBlank(message = "UserId cannot be null or blank")
    @Size(min = 3, max = 50, message = "UserId length must be between 3 and 50 characters")
    private String userId;

    /**
     * 初始餘額
     * 要求：>= 0，非空
     */
    @NotNull(message = "InitialBalance cannot be null")
    @DecimalMin(value = "0.0", inclusive = true, message = "InitialBalance must be >= 0")
    private BigDecimal initialBalance;
}
