package com.example.demo.facade.dto;

import com.example.demo.entity.TransferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Create Transfer Response DTO
 *
 * 回傳建立轉帳的結果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTransferResponse {

    /**
     * 轉帳 ID（自增主鍵）
     */
    private Long id;

    /**
     * 付款方使用者 ID
     */
    private String fromUserId;

    /**
     * 收款方使用者 ID
     */
    private String toUserId;

    /**
     * 轉帳金額
     */
    private BigDecimal amount;

    /**
     * 轉帳狀態（建立時應為 PENDING）
     */
    private TransferStatus status;

    /**
     * 建立時間
     */
    private LocalDateTime createdAt;
}
