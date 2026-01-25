package com.example.demo.facade.dto;

import com.example.demo.validation.NotSelfTransfer;
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
 * Create Transfer Request DTO
 *
 * 用於接收建立轉帳的請求參數
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@NotSelfTransfer(message = "Cannot transfer to yourself")
public class CreateTransferRequest {

    /**
     * 付款方使用者 ID
     * 要求：非空，3-50 字元
     */
    @NotBlank(message = "FromUserId cannot be null or blank")
    @Size(min = 3, max = 50, message = "FromUserId length must be between 3 and 50 characters")
    private String fromUserId;

    /**
     * 收款方使用者 ID
     * 要求：非空，3-50 字元
     */
    @NotBlank(message = "ToUserId cannot be null or blank")
    @Size(min = 3, max = 50, message = "ToUserId length must be between 3 and 50 characters")
    private String toUserId;

    /**
     * 轉帳金額
     * 要求：非空，必須 > 0
     */
    @NotNull(message = "Amount cannot be null")
    @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be greater than 0")
    private BigDecimal amount;
}
