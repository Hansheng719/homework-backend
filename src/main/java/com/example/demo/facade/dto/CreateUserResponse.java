package com.example.demo.facade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Create User Response DTO
 *
 * 回傳建立使用者的結果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserResponse {

    /**
     * 使用者 ID
     */
    private String userId;

    /**
     * 當前餘額
     */
    private BigDecimal balance;

    /**
     * 建立時間
     */
    private LocalDateTime createdAt;

    /**
     * 版本號（用於樂觀鎖）
     */
    private Long version;
}
