package com.example.demo.entity;

/**
 * 帳變類型枚舉
 *
 * 用途：標記帳變的業務類型，配合 external_id 確保冪等性
 *
 * 類型說明：
 * - TRANSFER_OUT: 轉帳扣款（付款方的帳變記錄）
 * - TRANSFER_IN: 轉帳加帳（收款方的帳變記錄）
 * - REFUND: 退款（可選，用於未來擴展）
 *
 * 冪等性保證：
 * 同一個 external_id（轉帳 ID）可以有兩筆帳變記錄：
 * 1. TRANSFER_OUT - 付款方扣款
 * 2. TRANSFER_IN - 收款方加帳
 * 但同一個 (external_id, type) 組合只能存在一筆記錄
 */
public enum BalanceChangeType {

    /**
     * 轉帳扣款
     * - 用途：從付款方帳戶扣除金額
     * - 對應事件：Balance Change Event (type: transfer_out)
     * - 金額符號：負數（例如：-150.00）
     */
    TRANSFER_OUT,

    /**
     * 轉帳加帳
     * - 用途：向收款方帳戶增加金額
     * - 對應事件：Balance Change Event (type: transfer_in)
     * - 金額符號：正數（例如：150.00）
     */
    TRANSFER_IN,

    /**
     * 退款
     * - 用途：退回金額到使用者帳戶
     * - 使用場景：轉帳失敗或取消後的退款
     * - 狀態：可選（預留給未來擴展）
     */
    REFUND
}
