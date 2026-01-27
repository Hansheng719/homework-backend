package com.example.demo.repository;

import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * TransferRepository
 *
 * 功能：提供 Transfer 實體的資料存取方法
 *
 * 自訂查詢方法：
 * 1. findByIdForUpdate - 悲觀鎖定查詢（用於並發控制）
 * 2. findPendingTransfers - 查詢待處理的 PENDING 轉帳
 * 3. findByFromUserIdOrToUserId - 查詢使用者相關的所有轉帳（分頁）
 */
@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /**
     * 使用悲觀鎖定查詢轉帳記錄（FOR UPDATE）
     *
     * 用途：防止並發更新，確保同一時間只有一個事務可以處理該轉帳
     * 使用場景：Scheduler 處理 PENDING 轉帳時，更新狀態為 PROCESSING
     *
     * @param id 轉帳 ID
     * @return Optional<Transfer> 轉帳記錄（帶鎖）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transfer t WHERE t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

    /**
     * 查詢待處理的 PENDING 轉帳
     *
     * 條件：
     * 1. 狀態為 PENDING
     * 2. 建立時間 <= cutoffTime（例如：10 分鐘前）
     *
     * 排序：按 createdAt 升序（最早的優先處理）
     *
     * 使用場景：Scheduler 每 5 分鐘查詢需要處理的轉帳
     *
     * @param status      轉帳狀態（通常為 PENDING）
     * @param cutoffTime  截止時間（例如：NOW() - 10 分鐘）
     * @param pageable    分頁參數（例如：PageRequest.of(0, 100)）
     * @return List<Transfer> 待處理的轉帳清單
     */
    @Query("SELECT t FROM Transfer t WHERE t.status = :status " +
           "AND t.createdAt <= :cutoffTime " +
           "ORDER BY t.createdAt ASC")
    List<Transfer> findPendingTransfers(
            @Param("status") TransferStatus status,
            @Param("cutoffTime") LocalDateTime cutoffTime,
            Pageable pageable
    );

    /**
     * 查詢使用者的轉帳歷史（作為付款方或收款方）
     *
     * 條件：fromUserId = userId OR toUserId = userId
     * 排序：按 createdAt 降序（最新的在前）
     *
     * 使用場景：查詢轉帳歷史 API
     *
     * @param fromUserId 付款方使用者 ID
     * @param toUserId   收款方使用者 ID（與 fromUserId 相同）
     * @param pageable   分頁參數
     * @return Page<Transfer> 分頁結果
     */
    @Query("SELECT t FROM Transfer t " +
           "WHERE t.fromUserId = :fromUserId OR t.toUserId = :toUserId " +
           "ORDER BY t.createdAt DESC")
    Page<Transfer> findByFromUserIdOrToUserId(
            @Param("fromUserId") String fromUserId,
            @Param("toUserId") String toUserId,
            Pageable pageable
    );

    /**
     * 查詢指定狀態且 updatedAt 在截止時間之前的轉帳記錄
     *
     * 用途：重試排程器查詢停滯在 DEBIT_PROCESSING 或 CREDIT_PROCESSING 狀態的轉帳
     *
     * 條件：
     * 1. 狀態為指定狀態（例如 DEBIT_PROCESSING 或 CREDIT_PROCESSING）
     * 2. updatedAt <= cutoffTime（例如：NOW() - 10 分鐘）
     *
     * 排序：按 updatedAt ASC（最舊的優先重試）
     *
     * 使用場景：
     * - 扣款重試排程器（處理 DEBIT_PROCESSING 超過 10 分鐘）
     * - 加帳重試排程器（處理 CREDIT_PROCESSING 超過 10 分鐘）
     *
     * @param status      轉帳狀態
     * @param cutoffTime  截止時間（例如：NOW() - 10 分鐘）
     * @param pageable    分頁參數（例如：PageRequest.of(0, 100)）
     * @return List<Transfer> 需要重試的轉帳清單
     */
    @Query("SELECT t FROM Transfer t WHERE t.status = :status " +
           "AND t.updatedAt <= :cutoffTime " +
           "ORDER BY t.updatedAt ASC")
    List<Transfer> findTransfersByStatusAndUpdatedAtBefore(
            @Param("status") TransferStatus status,
            @Param("cutoffTime") LocalDateTime cutoffTime,
            Pageable pageable
    );
}
