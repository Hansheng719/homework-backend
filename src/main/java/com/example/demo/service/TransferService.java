package com.example.demo.service;

import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * TransferService 介面
 *
 * 功能：管理轉帳記錄的狀態和生命週期
 *
 * 職責：
 * 1. 建立 PENDING 狀態的轉帳記錄
 * 2. 狀態更新（含悲觀鎖）
 * 3. 查詢待處理轉帳
 * 4. 標記轉帳失敗
 * 5. 完成轉帳
 *
 * 設計原則：
 * - 每個方法都是原子性操作
 * - 包含完整的事務邊界（@Transactional）
 * - 單一職責，不編排業務流程
 *
 * 參考：SPEC.md Section 5.5 - Service 層設計原則
 */
public interface TransferService {

    /**
     * 建立 PENDING 狀態的轉帳記錄
     *
     * 執行步驟：
     * 1. 建立 Transfer 實體
     * 2. 設定 status = PENDING
     * 3. 設定 createdAt = 當前時間
     * 4. 儲存到資料庫
     * 5. 回傳 Transfer 物件（含自增 ID）
     *
     * @param fromUserId 轉出方使用者 ID
     * @param toUserId 收款方使用者 ID
     * @param amount 轉帳金額
     * @return Transfer 建立的轉帳記錄
     */
    Transfer createPendingTransfer(String fromUserId, String toUserId, BigDecimal amount);

    /**
     * 取消轉帳（用戶操作）
     *
     * 狀態轉換：PENDING → CANCELLED
     *
     * @param transferId 轉帳 ID
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 當前狀態不是 PENDING
     */
    void cancelTransfer(Long transferId);

    /**
     * 標記扣款處理中
     *
     * 狀態轉換：PROCESSING → DEBIT_PROCESSING
     *
     * @param transferId 轉帳 ID
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 當前狀態不是 PROCESSING
     */
    void markDebitProcessing(Long transferId);

    /**
     * 處理扣款成功
     *
     * 狀態轉換：DEBIT_PROCESSING → DEBIT_COMPLETED
     *
     * @param transferId 轉帳 ID
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 當前狀態不是 DEBIT_PROCESSING
     */
    void handleDebitSuccess(Long transferId);

    /**
     * 處理扣款失敗
     *
     * 狀態轉換：DEBIT_PROCESSING → DEBIT_FAILED
     *
     * @param transferId 轉帳 ID
     * @param reason 失敗原因（會截斷至 255 字元）
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 當前狀態不是 DEBIT_PROCESSING
     */
    void handleDebitFailure(Long transferId, String reason);

    /**
     * 標記加帳處理中
     *
     * 狀態轉換：DEBIT_COMPLETED → CREDIT_PROCESSING
     *
     * @param transferId 轉帳 ID
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 當前狀態不是 DEBIT_COMPLETED
     */
    void markCreditProcessing(Long transferId);

    /**
     * 查詢待處理的 PENDING 轉帳
     *
     * 執行步驟：
     * 1. 查詢 status = PENDING AND createdAt <= cutoffTime
     * 2. 按 createdAt ASC 排序
     * 3. 限制結果數量為 batchSize
     *
     * 查詢條件：
     * - status = PENDING
     * - createdAt <= cutoffTime（已超過等待時間）
     * - 排序：createdAt ASC（先處理最舊的）
     * - 分頁：PageRequest.of(0, batchSize)
     *
     * @param cutoffTime 截止時間（通常是 NOW() - 10 分鐘）
     * @param batchSize 批次大小（建議 100）
     * @return List<Transfer> PENDING 狀態的轉帳清單
     */
    List<Transfer> findPendingTransfers(LocalDateTime cutoffTime, int batchSize);

    /**
     * 完成轉帳（收到加帳成功 MQ 後調用）
     *
     * 狀態轉換：CREDIT_PROCESSING → COMPLETED
     *
     * 執行步驟：
     * 1. 使用悲觀鎖查詢 Transfer
     * 2. 驗證狀態是否為 CREDIT_PROCESSING
     * 3. 設定 status = COMPLETED
     * 4. 設定 completedAt = 當前時間
     * 5. 儲存
     * 6. 發布 TransferStatusChangedEvent
     *
     * @param transferId 轉帳 ID
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 狀態不是 CREDIT_PROCESSING
     */
    void completeTransfer(Long transferId);

    /**
     * 根據 ID 查詢轉帳
     *
     * @param transferId 轉帳 ID
     * @return Transfer 轉帳記錄
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     */
    Transfer findById(Long transferId);

    /**
     * 取得使用者的轉帳歷史（分頁）
     *
     * 查詢條件：fromUserId = userId OR toUserId = userId
     * 排序：按 createdAt 降序（最新的在前）
     *
     * @param userId 使用者 ID
     * @param page 頁碼（從 0 開始）
     * @param size 每頁大小
     * @return Page<Transfer> 分頁結果
     */
    Page<Transfer> getTransferHistory(String userId, int page, int size);
}
