package com.example.demo.repository;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * BalanceChangeRepository
 *
 * 功能：提供 BalanceChange 實體的資料存取方法
 *
 * 自訂查詢方法：
 * 1. findByExternalIdAndType - 冪等性檢查查詢
 *
 * 冪等性保證：
 * - 透過 findByExternalIdAndType 查詢已存在的帳變記錄
 * - BalanceService 在處理帳變前先檢查，避免重複處理
 * - 配合資料庫唯一約束 (external_id, type) 確保冪等性
 */
@Repository
public interface BalanceChangeRepository extends JpaRepository<BalanceChange, Long> {

    /**
     * 根據外部 ID 和類型查詢帳變記錄（冪等性檢查）
     *
     * 用途：防止重複處理同一個轉帳的帳變操作
     *
     * 使用場景：
     * 1. BalanceService.debitBalance(externalId, ...) 開始時檢查
     *    - 如果已存在 (externalId, TRANSFER_OUT) 記錄，直接返回該記錄
     *    - 避免重複扣款
     *
     * 2. BalanceService.creditBalance(externalId, ...) 開始時檢查
     *    - 如果已存在 (externalId, TRANSFER_IN) 記錄，直接返回該記錄
     *    - 避免重複加帳
     *
     * 查詢條件：
     * - external_id = :externalId (轉帳 ID)
     * - type = :type (TRANSFER_OUT / TRANSFER_IN)
     *
     * 範例：
     * <pre>
     * // 檢查轉帳 12345 的扣款記錄是否已存在
     * Optional<BalanceChange> existing = repository.findByExternalIdAndType(
     *     12345L,
     *     BalanceChangeType.TRANSFER_OUT
     * );
     *
     * if (existing.isPresent()) {
     *     // 已處理過，直接返回
     *     return existing.get();
     * }
     *
     * // 首次處理，建立新記錄
     * BalanceChange newChange = new BalanceChange();
     * // ... 設定欄位
     * repository.save(newChange);
     * </pre>
     *
     * @param externalId 外部 ID（transfer 表的主鍵）
     * @param type 帳變類型（TRANSFER_OUT / TRANSFER_IN）
     * @return Optional<BalanceChange> 已存在的帳變記錄（如果有）
     */
    Optional<BalanceChange> findByExternalIdAndType(Long externalId, BalanceChangeType type);
}
