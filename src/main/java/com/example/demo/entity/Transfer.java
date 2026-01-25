package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transfer 實體（轉帳表）
 *
 * 功能：記錄使用者之間的轉帳交易
 * 狀態流程：PENDING → PROCESSING → DEBIT_PROCESSING → DEBIT_COMPLETED →
 *          CREDIT_PROCESSING → COMPLETED
 * 失敗狀態：DEBIT_FAILED, CANCELLED
 */
@Entity
@Table(name = "transfers", indexes = {
        // Critical: Composite index for Scheduler queries (findPendingTransfers)
        // Covers: WHERE status = ? AND created_at <= ? ORDER BY created_at ASC
        // Performance: Eliminates filesort, 10-50x faster for large datasets
        @Index(name = "idx_status_created_at", columnList = "status, created_at"),

        // Recommended: Composite indexes for user history queries (findByFromUserIdOrToUserId)
        // Covers: WHERE from_user_id = ? ORDER BY created_at DESC
        // Performance: Eliminates filesort for user history API
        @Index(name = "idx_from_user_created_at", columnList = "from_user_id, created_at DESC"),

        // Covers: WHERE to_user_id = ? ORDER BY created_at DESC
        // Performance: Eliminates filesort for user history API
        @Index(name = "idx_to_user_created_at", columnList = "to_user_id, created_at DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transfer {

    /**
     * 轉帳 ID（主鍵，自增）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 付款方使用者 ID
     */
    @Column(name = "from_user_id", nullable = false, length = 50)
    private String fromUserId;

    /**
     * 收款方使用者 ID
     */
    @Column(name = "to_user_id", nullable = false, length = 50)
    private String toUserId;

    /**
     * 轉帳金額（DECIMAL(15,2)）
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * 轉帳狀態
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    /**
     * 建立時間（不可更新）
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 完成時間（可為 null）
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 取消時間（可為 null）
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * 失敗原因（可為 null）
     */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;
}
