package com.example.demo.facade;

import com.example.demo.facade.dto.CreateUserRequest;
import com.example.demo.facade.dto.CreateUserResponse;
import com.example.demo.mq.msg.BalanceChangeMsg;

/**
 * Balance Facade
 *
 * 職責：
 * 1. 處理餘額變更請求（debit/credit）
 * 2. 協調 BalanceService 呼叫
 * 3. 封裝業務邏輯細節
 * 4. 訊息驗證與資料清理
 * 5. 統一錯誤處理與日誌記錄
 *
 * 設計原則：
 * - Facade 模式：簡化複雜子系統的互動
 * - 單一入口點處理餘額變更請求
 * - 委派給 BalanceService 進行餘額操作
 * - 提供訊息驗證與前置檢查
 * - 統一 MQ 觸發的餘額操作日誌
 */
public interface BalanceFacade {

    /**
     * 處理餘額變更請求
     *
     * 此方法作為所有 MQ 觸發的餘額變更操作的單一入口點。
     * 執行訊息驗證、路由至適當的 BalanceService 方法、並提供統一的錯誤處理。
     *
     * 職責：
     * 1. 驗證訊息完整性
     * 2. 根據類型路由至 debitBalance() 或 creditBalance()
     * 3. 統一處理 MQ 觸發操作的異常
     * 4. 集中記錄所有 MQ 觸發的餘額變更日誌
     *
     * 根據事件類型（TRANSFER_OUT/TRANSFER_IN）進行處理：
     *
     * TRANSFER_OUT (扣款):
     * - 驗證金額為正數
     * - 委派給 BalanceService.debitBalance()
     * - 返回 COMPLETED 或 FAILED BalanceChange
     *
     * TRANSFER_IN (加帳):
     * - 驗證金額為正數
     * - 委派給 BalanceService.creditBalance()
     * - 返回 COMPLETED 或 FAILED BalanceChange
     *
     * 錯誤處理策略：
     * - IllegalArgumentException: 訊息驗證失敗（無效的訊息資料，觸發 MQ 重試）
     * - RuntimeException: 系統錯誤（觸發 MQ 重試）
     *
     *
     * @param msg 來自 MQ 的餘額變更訊息
     * @throws IllegalArgumentException 訊息驗證失敗（無效的訊息資料，觸發 MQ 重試）
     * @throws RuntimeException 系統錯誤（觸發 MQ 重試）
     */
    void handleBalanceChangeRequest(BalanceChangeMsg msg);

    /**
     * 建立使用者
     *
     * 此方法作為建立使用者的單一入口點，提供參數驗證、服務協調和統一錯誤處理。
     *
     * 職責：
     * 1. 驗證輸入參數（userId 格式、initialBalance 範圍）
     * 2. 委派給 BalanceService.createUser()
     * 3. 將 UserBalance 實體轉換為 CreateUserResponse DTO
     * 4. 統一處理異常（UserAlreadyExistsException）
     * 5. 記錄使用者建立操作日誌
     *
     * 驗證規則：
     * - userId: 非空、去除前後空白、長度 3-50 字元
     * - initialBalance: 非空、必須 >= 0
     *
     * 錯誤處理策略：
     * - IllegalArgumentException: 參數驗證失敗
     * - UserAlreadyExistsException: 使用者已存在（由 service 層拋出）
     *
     * @param request 建立使用者請求
     * @return CreateUserResponse 建立的使用者資訊
     * @throws IllegalArgumentException 參數驗證失敗
     * @throws com.example.demo.exception.UserAlreadyExistsException 使用者已存在
     */
    CreateUserResponse createUser(CreateUserRequest request);

    /**
     * 取得使用者餘額
     *
     * 此方法作為取得使用者餘額的單一入口點。
     *
     * 職責：
     * 1. 驗證 userId 格式
     * 2. 委派給 BalanceService.getBalance()
     * 3. 將結果轉換為 GetBalanceResponse DTO
     * 4. 處理 UserNotFoundException
     *
     * 驗證規則：
     * - userId: 非空、去除前後空白、長度 3-50 字元
     *
     * @param userId 使用者 ID
     * @return GetBalanceResponse 使用者餘額資訊
     * @throws IllegalArgumentException userId 驗證失敗
     * @throws com.example.demo.exception.UserNotFoundException 使用者不存在
     */
    com.example.demo.facade.dto.GetBalanceResponse getBalance(String userId);
}
