package com.example.demo.entity;

/**
 * 帳變狀態枚舉
 *
 * 狀態流程（正常流程）：
 * PROCESSING → COMPLETED
 *
 * 失敗狀態：
 * PROCESSING → FAILED
 *
 * 狀態說明：
 * - PROCESSING: 帳變處理中（初始狀態）
 * - COMPLETED: 帳變完成（終態 ✓）
 * - FAILED: 帳變失敗（終態 ✗）
 */
public enum BalanceChangeStatus {

    /**
     * 處理中
     * - 初始狀態：建立帳變記錄時的狀態
     * - 用途：標記帳變正在處理中
     * - 可轉換為：COMPLETED, FAILED
     * - 業務邏輯：
     *   1. BalanceService.debitBalance() 開始時建立 PROCESSING 記錄
     *   2. 鎖定使用者、檢查餘額
     *   3. 更新餘額後轉為 COMPLETED
     */
    PROCESSING,

    /**
     * 已完成
     * - 終態 ✓
     * - 用途：帳變成功完成
     * - 業務邏輯：
     *   1. 餘額已更新成功
     *   2. balanceAfter 已記錄
     *   3. completedAt 已設定
     * - 不可再轉換為其他狀態
     */
    COMPLETED,

    /**
     * 失敗
     * - 終態 ✗
     * - 用途：帳變處理失敗
     * - 失敗原因：
     *   1. 餘額不足（InsufficientBalanceException）
     *   2. 使用者不存在（UserNotFoundException）
     *   3. 系統錯誤
     * - 業務邏輯：
     *   1. failureReason 已記錄失敗原因
     *   2. 發送失敗結果事件給 Transfer Service
     * - 不可再轉換為其他狀態
     */
    FAILED
}
