package com.example.demo.exception;

/**
 * 轉帳狀態無效異常
 *
 * 當轉帳狀態不符合操作要求時拋出此異常
 *
 * 使用場景：
 * - completeTransfer() 嘗試完成狀態不是 CREDIT_PROCESSING 的轉帳
 * - 任何需要驗證轉帳狀態的業務邏輯
 *
 * 設計原則：
 * - 確保轉帳狀態轉換的正確性
 * - 防止非法狀態轉換
 * - 提供清楚的錯誤訊息幫助除錯
 */
public class InvalidTransferStateException extends RuntimeException {

    /**
     * 建立轉帳狀態無效異常
     *
     * @param message 錯誤訊息
     */
    public InvalidTransferStateException(String message) {
        super(message);
    }

    /**
     * 建立轉帳狀態無效異常（含原因）
     *
     * @param message 錯誤訊息
     * @param cause 異常原因
     */
    public InvalidTransferStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
