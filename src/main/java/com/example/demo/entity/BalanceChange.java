package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BalanceChange 實體（帳變表）
 *
 * 功能：記錄使用者帳戶餘額變動歷史
 * 用途：
 * 1. 冪等性保證：透過 (external_id, type) 唯一約束避免重複處理
 * 2. 審計追蹤：記錄每次餘額變動的完整資訊
 * 3. 對帳支援：記錄變動前後餘額，便於對帳
 *
 * 狀態流程：PROCESSING → COMPLETED / FAILED
 *
 * 冪等性設計：
 * - external_id: 對應 transfer 表的主鍵（轉帳 ID）
 * - type: 帳變類型（TRANSFER_OUT / TRANSFER_IN）
 * - 唯一約束：同一個 (external_id, type) 只能有一筆記錄
 * - 範例：轉帳 ID 12345 會產生兩筆帳變記錄
 *   1. (12345, TRANSFER_OUT) - 付款方扣款
 *   2. (12345, TRANSFER_IN) - 收款方加帳
 */
@Entity
@Table(name = "balance_changes",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_external_id_type",
               columnNames = {"external_id", "type"}
       ))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceChange {

    /**
     * 帳變 ID（主鍵，自增）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 外部冪等 ID
     * - 對應 transfer 表的主鍵
     * - 用途：冪等性檢查，防止重複處理
     * - 配合 type 組成唯一約束
     */
    @Column(name = "external_id", nullable = false)
    private Long externalId;

    /**
     * 帳變類型
     * - TRANSFER_OUT: 轉帳扣款
     * - TRANSFER_IN: 轉帳加帳
     * - REFUND: 退款（預留）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BalanceChangeType type;

    /**
     * 使用者 ID
     * - 帳變影響的使用者
     * - 扣款：付款方的 userId
     * - 加帳：收款方的 userId
     */
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /**
     * 帳變金額（DECIMAL(15,2)）
     * - 正數：加帳（例如：150.00）
     * - 負數：扣款（例如：-150.00）
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * 關聯 ID
     * - 關聯的業務 ID（例如：轉帳 ID 的字串形式）
     * - 用途：追蹤和查詢
     * - 可為 null
     */
    @Column(name = "related_id", length = 50)
    private String relatedId;

    /**
     * 帳變狀態
     * - PROCESSING: 處理中
     * - COMPLETED: 已完成
     * - FAILED: 失敗
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BalanceChangeStatus status;

    /**
     * 變更前餘額
     * - 記錄帳變發生前的餘額
     * - 用途：審計、對帳
     * - 可為 null（初始狀態）
     */
    @Column(name = "balance_before", precision = 15, scale = 2)
    private BigDecimal balanceBefore;

    /**
     * 變更後餘額
     * - 記錄帳變完成後的餘額
     * - 用途：審計、對帳、驗證
     * - 可為 null（處理中或失敗狀態）
     */
    @Column(name = "balance_after", precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    /**
     * 建立時間（不可更新）
     * - 帳變記錄的建立時間
     * - 自動設定，不可修改
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 完成時間
     * - 帳變完成的時間（status = COMPLETED）
     * - 可為 null（處理中或失敗狀態）
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 失敗原因
     * - 帳變失敗時記錄失敗原因（status = FAILED）
     * - 範例：
     *   "Insufficient balance: required 150.00, available 100.00"
     *   "User not found: user_001"
     * - 可為 null（成功或處理中狀態）
     */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}
