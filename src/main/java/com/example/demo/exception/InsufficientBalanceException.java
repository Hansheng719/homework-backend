package com.example.demo.exception;

import java.math.BigDecimal;

/**
 * 餘額不足異常
 *
 * 使用場景：
 * 1. checkSufficientBalance() 檢查餘額不足
 * 2. debitBalance() 扣款時餘額不足
 * 3. 轉帳時付款方餘額不足
 */
public class InsufficientBalanceException extends RuntimeException {

    private final String userId;
    private final BigDecimal currentBalance;
    private final BigDecimal requiredAmount;

    public InsufficientBalanceException(String userId) {
        super(String.format("Insufficient balance for user: %s", userId));
        this.userId = userId;
        this.currentBalance = null;
        this.requiredAmount = null;
    }

    public InsufficientBalanceException(String userId, BigDecimal currentBalance, BigDecimal requiredAmount) {
        super(String.format("Insufficient balance for user: %s (current: %s, required: %s)",
            userId, currentBalance, requiredAmount));
        this.userId = userId;
        this.currentBalance = currentBalance;
        this.requiredAmount = requiredAmount;
    }

    public String getUserId() {
        return userId;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getRequiredAmount() {
        return requiredAmount;
    }
}
