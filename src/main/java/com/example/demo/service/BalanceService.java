package com.example.demo.service;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.UserBalance;
import org.springframework.cache.annotation.CacheEvict;

import java.math.BigDecimal;

/**
 * BalanceService 介面
 *
 * 功能：管理使用者餘額和帳變操作
 *
 * 職責：
 * 1. 使用者建立與初始餘額設定
 * 2. 餘額查詢（含快取）
 * 3. 餘額驗證（存在性、充足性）
 * 4. 餘額扣款（含冪等性、事務性）
 * 5. 餘額加帳（含冪等性、事務性）
 *
 * 設計原則：
 * - Service 方法為原子性操作
 * - 包含完整的事務邊界
 * - 處理單一業務操作
 * - 含冪等性保證（debit/credit）
 */
public interface BalanceService {

    /**
     * 建立使用者並設定初始餘額
     *
     * 業務規則：
     * 1. userId 必須唯一（不可重複建立）
     * 2. initialBalance 必須 >= 0
     * 3. 建立後寫入 Redis 快取（TTL 300秒）
     *
     * @param userId 使用者 ID（3-50 字元）
     * @param initialBalance 初始餘額（>= 0）
     * @return UserBalance 建立的使用者餘額記錄
     * @throws com.example.demo.exception.UserAlreadyExistsException userId 已存在
     */
    UserBalance createUser(String userId, BigDecimal initialBalance);

    /**
     * 查詢使用者餘額
     *
     * 查詢策略：
     * 1. 優先從 Redis 快取讀取
     * 2. 快取未命中時從資料庫查詢
     * 3. 資料庫查詢成功後更新快取
     *
     * @param userId 使用者 ID
     * @return BigDecimal 當前餘額
     * @throws com.example.demo.exception.UserNotFoundException 使用者不存在
     */
    BigDecimal getBalance(String userId);

    @CacheEvict(value = "balance", key = "#userId")
    void evictBalanceCache(String userId);

    /**
     * 扣減使用者餘額（含冪等性保證）
     *
     * 執行流程：
     * 1. 冪等性檢查：findByExternalIdAndType(externalId, TRANSFER_OUT)
     * 2. 若已存在，直接返回該記錄（可能是 COMPLETED 或 FAILED）
     * 3. 鎖定使用者：findByIdForUpdate(userId)
     * 4. 檢查餘額是否充足
     * 5. 建立帳變記錄（PROCESSING）
     * 6. 更新使用者餘額（扣減）
     * 7. 完成帳變記錄（COMPLETED）
     * 8. 發布事件（BalanceCacheEvictionEvent, BalanceChangeResultEvent）
     *
     * 錯誤處理：
     * - 若使用者不存在或餘額不足，儲存 FAILED 記錄並返回（不拋出異常）
     * - 失敗記錄也會發布 BalanceChangeResultEvent 通知下游系統
     *
     * @param externalId 外部冪等 ID（轉帳 ID）
     * @param userId 使用者 ID
     * @param amount 扣款金額（正數）
     * @return BalanceChange 帳變記錄（COMPLETED 或 FAILED 狀態）
     * @throws com.example.demo.exception.UserNotFoundException 使用者不存在
     */
    BalanceChange debitBalance(Long externalId, String userId, BigDecimal amount);

    /**
     * 增加使用者餘額（含冪等性保證）
     *
     * 執行流程：
     * 1. 冪等性檢查：findByExternalIdAndType(externalId, TRANSFER_IN)
     * 2. 若已存在，直接返回該記錄（可能是 COMPLETED 或 FAILED）
     * 3. 鎖定使用者：findByIdForUpdate(userId)
     * 4. 建立帳變記錄（PROCESSING）
     * 5. 更新使用者餘額（增加）
     * 6. 完成帳變記錄（COMPLETED）
     * 7. 發布事件（BalanceCacheEvictionEvent, BalanceChangeResultEvent）
     *
     * 錯誤處理：
     * - 若使用者不存在，儲存 FAILED 記錄並返回（不拋出異常）
     * - 失敗記錄也會發布 BalanceChangeResultEvent 通知下游系統
     *
     * @param externalId 外部冪等 ID（轉帳 ID）
     * @param userId 使用者 ID
     * @param amount 加帳金額（正數）
     * @return BalanceChange 帳變記錄（COMPLETED 或 FAILED 狀態）
     * @throws com.example.demo.exception.UserNotFoundException 使用者不存在
     */
    BalanceChange creditBalance(Long externalId, String userId, BigDecimal amount);
}
