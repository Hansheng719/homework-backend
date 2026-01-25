package com.example.demo.entity;

/**
 * 轉帳狀態枚舉
 *
 * 狀態轉換流程（Happy Path）：
 * PENDING → DEBIT_PROCESSING → CREDIT_PROCESSING → COMPLETED
 *
 * 失敗狀態：
 * - DEBIT_FAILED: 扣款失敗（餘額不足或系統錯誤）
 * - CANCELLED: 已取消（僅限 PENDING 狀態且在 10 分鐘內）
 */
public enum TransferStatus {

    /**
     * 已建立，等待排程器處理（約 10 分鐘）
     * - 可轉換為：DEBIT_PROCESSING, DEBIT_FAILED, CANCELLED
     */
    PENDING,

    /**
     * 將發送扣款事件，交由Balance Service 處理
     * - 可轉換為：CREDIT_PROCESSING, DEBIT_FAILED
     */
    DEBIT_PROCESSING,

    /**
     * 扣款成功後，將發送加帳事件，交由Balance Service 處理
     * - 可轉換為：COMPLETED
     */
    CREDIT_PROCESSING,

    /**
     * 轉帳完成（扣款和加帳都成功）
     * - 終態 ✓
     */
    COMPLETED,

    /**
     * 扣款失敗（餘額不足或系統錯誤）
     * - 終態 ✗
     */
    DEBIT_FAILED,

    /**
     * 已取消（PENDING 狀態 10 分鐘內可取消）
     * - 終態 ✗
     */
    CANCELLED
}
