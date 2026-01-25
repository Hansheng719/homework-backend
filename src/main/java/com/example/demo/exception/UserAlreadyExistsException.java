package com.example.demo.exception;

/**
 * 使用者已存在異常
 *
 * 使用場景：
 * 1. createUser() 時，userId 已存在於資料庫
 * 2. 違反 userId 的唯一性約束
 */
public class UserAlreadyExistsException extends RuntimeException {

    private final String userId;

    public UserAlreadyExistsException(String userId) {
        super(String.format("User already exists: %s", userId));
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
