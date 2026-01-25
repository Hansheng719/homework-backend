package com.example.demo.facade;

import com.example.demo.facade.dto.CancelTransferResponse;
import com.example.demo.facade.dto.CreateTransferRequest;
import com.example.demo.facade.dto.CreateTransferResponse;
import com.example.demo.facade.dto.TransferHistoryResponse;
import com.example.demo.mq.msg.BalanceChangeResultMsg;

/**
 * Transfer Facade
 *
 * 職責：
 * 1. 處理餘額變更結果（debit/credit）
 * 2. 協調 TransferService 呼叫
 * 3. 封裝業務邏輯細節
 *
 * 設計原則：
 * - Facade 模式：簡化複雜子系統的互動
 * - 單一入口點處理餘額變更結果
 * - 委派給 TransferService 進行狀態轉換
 * - 不直接呼叫 MQ producers（使用事件驅動）
 */
public interface TransferFacade {

    /**
     * 處理餘額變更結果
     *
     * 根據事件類型（TRANSFER_OUT/TRANSFER_IN）和成功/失敗狀態進行處理：
     *
     * TRANSFER_OUT (扣款):
     * - 成功: 委派給 TransferService.handleDebitSuccess()
     * - 失敗: 委派給 TransferService.handleDebitFailure()
     *
     * TRANSFER_IN (加帳):
     * - 成功: 委派給 TransferService.completeTransfer()
     * - 失敗: 拋出異常以觸發 MQ 重試
     *
     * @param event 餘額變更結果事件
     * @throws com.example.demo.exception.TransferNotFoundException 如果轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 如果狀態轉換無效
     * @throws IllegalArgumentException 如果事件類型不支援
     * @throws RuntimeException 如果加帳操作失敗（觸發重試）
     */
    void handleBalanceChangeResult(BalanceChangeResultMsg event);

    /**
     * 建立轉帳
     *
     * 此方法作為建立轉帳的單一入口點，提供完整的前置驗證、服務協調和統一錯誤處理。
     *
     * 職責：
     * 1. 驗證輸入參數（userId 格式、amount 範圍、fromUserId != toUserId）
     * 2. 驗證使用者存在性（fromUserId 和 toUserId 都必須存在）
     * 3. 驗證付款方餘額充足
     * 4. 委派給 TransferService.createPendingTransfer()
     * 5. 將 Transfer 實體轉換為 CreateTransferResponse DTO
     * 6. 統一處理異常
     * 7. 記錄轉帳建立操作日誌
     *
     * 驗證規則：
     * - fromUserId: 非空、去除前後空白、長度 3-50 字元
     * - toUserId: 非空、去除前後空白、長度 3-50 字元
     * - fromUserId != toUserId（不可自己轉帳給自己）
     * - amount: 非空、必須 > 0
     *
     * 前置檢查（在建立轉帳前）：
     * 1. 檢查 fromUserId 和 toUserId 是否存在（使用 BalanceService.getBalance()）
     * 2. 檢查 fromUserId 餘額是否充足（currentBalance >= amount）
     *
     * 錯誤處理策略：
     * - IllegalArgumentException: 參數驗證失敗
     * - UserNotFoundException: 使用者不存在（由 BalanceService.getBalance() 拋出）
     * - InsufficientBalanceException: 餘額不足（由 facade 拋出）
     *
     * @param request 建立轉帳請求
     * @return CreateTransferResponse 建立的轉帳資訊
     * @throws IllegalArgumentException 參數驗證失敗
     * @throws com.example.demo.exception.UserNotFoundException 使用者不存在
     * @throws com.example.demo.exception.InsufficientBalanceException 餘額不足
     */
    CreateTransferResponse createTransfer(CreateTransferRequest request);

    /**
     * 取消轉帳
     *
     * 此方法作為取消轉帳的單一入口點。
     * 只有 PENDING 狀態的轉帳可以被取消。
     *
     * 職責：
     * 1. 驗證 transferId
     * 2. 委派給 TransferService.cancelTransfer()
     * 3. 取得取消後的轉帳資訊
     * 4. 將 Transfer 實體轉換為 CancelTransferResponse DTO
     * 5. 處理例外
     *
     * @param transferId 轉帳 ID
     * @return CancelTransferResponse 取消後的轉帳資訊
     * @throws IllegalArgumentException transferId 驗證失敗
     * @throws com.example.demo.exception.TransferNotFoundException 轉帳不存在
     * @throws com.example.demo.exception.InvalidTransferStateException 轉帳不在 PENDING 狀態
     */
    CancelTransferResponse cancelTransfer(Long transferId);

    /**
     * 取得轉帳歷史
     *
     * 此方法作為查詢轉帳歷史的單一入口點。
     * 查詢指定使用者作為付款方或收款方的所有轉帳。
     *
     * 職責：
     * 1. 驗證 userId 和分頁參數
     * 2. 檢查使用者是否存在
     * 3. 委派給 TransferService.getTransferHistory()
     * 4. 將 Page<Transfer> 轉換為 TransferHistoryResponse
     * 5. 包含分頁元資料
     *
     * 驗證規則：
     * - userId: 非空、去除前後空白、長度 3-50 字元
     * - page: >= 0
     * - size: 1-100
     *
     * @param userId 使用者 ID
     * @param page 頁碼（從 0 開始）
     * @param size 每頁大小
     * @return TransferHistoryResponse 轉帳歷史與分頁資訊
     * @throws IllegalArgumentException 參數驗證失敗
     * @throws com.example.demo.exception.UserNotFoundException 使用者不存在
     */
    TransferHistoryResponse getTransferHistory(String userId, int page, int size);

    /**
     * 處理待處理的轉帳
     *
     * 此方法作為定時任務處理待處理轉帳的單一入口點。
     * 查詢超過指定時間的 PENDING 狀態轉帳，並將其轉換為 DEBIT_PROCESSING 狀態。
     *
     * 職責：
     * 1. 計算截止時間（當前時間 - delaySeconds）
     * 2. 查詢 PENDING 狀態且早於截止時間的轉帳
     * 3. 逐一處理每筆轉帳：呼叫 TransferService.markDebitProcessing()
     * 4. 錯誤處理：單筆轉帳失敗不影響其他轉帳處理
     * 5. 記錄處理日誌與統計資訊
     *
     * 處理流程：
     * - 狀態轉換：PENDING → DEBIT_PROCESSING
     * - 觸發事件：TransferStatusChangedEvent
     * - 發送 MQ：由 TransferStatusChangedListener 發送 debit request
     *
     * 錯誤處理策略：
     * - 單筆轉帳失敗：記錄錯誤日誌，繼續處理下一筆
     * - 整體查詢失敗：拋出 RuntimeException
     *
     * @param delaySeconds 延遲秒數，只處理創建時間早於（當前時間 - delaySeconds）的轉帳
     * @param batchSize 批次大小，單次處理的最大轉帳數量
     * @return 成功處理的轉帳數量
     * @throws RuntimeException 查詢或處理失敗
     */
    int processPendingTransfers(int delaySeconds, int batchSize);
}
