package com.example.demo.exception;

/**
 * 轉帳不存在異常
 *
 * 當嘗試操作不存在的轉帳記錄時拋出此異常
 *
 * 使用場景：
 * - findById() 找不到指定的轉帳
 * - updateStatus() 嘗試更新不存在的轉帳
 * - markAsFailed() 嘗試標記不存在的轉帳為失敗
 * - completeTransfer() 嘗試完成不存在的轉帳
 */
public class TransferNotFoundException extends RuntimeException {

    /**
     * 建立轉帳不存在異常
     *
     * @param transferId 找不到的轉帳 ID
     */
    public TransferNotFoundException(Long transferId) {
        super("Transfer not found: " + transferId);
    }

    /**
     * 建立轉帳不存在異常（自訂訊息）
     *
     * @param message 錯誤訊息
     */
    public TransferNotFoundException(String message) {
        super(message);
    }
}
