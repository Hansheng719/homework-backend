package com.example.demo.exception;

/**
 * 使用者不存在異常
 *
 * 使用場景：
 * 1. 查詢使用者時，userId 不存在於資料庫
 * 2. validateUserExists() 驗證失敗
 * 3. getBalance() 查詢不到使用者
 * 4. debitBalance() / creditBalance() 鎖定使用者失敗
 */
public class UserNotFoundException extends RuntimeException {

    private final String userId;

    public UserNotFoundException(String userId) {
        super(String.format("User not found: %s", userId));
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
