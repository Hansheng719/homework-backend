package com.example.demo.facade.impl;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeType;
import com.example.demo.entity.UserBalance;
import com.example.demo.exception.UserAlreadyExistsException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.facade.BalanceFacade;
import com.example.demo.facade.dto.CreateUserRequest;
import com.example.demo.facade.dto.CreateUserResponse;
import com.example.demo.facade.dto.GetBalanceResponse;
import com.example.demo.mq.msg.BalanceChangeMsg;
import com.example.demo.service.BalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * BalanceFacade 實作
 *
 * 協調邏輯：
 * - 驗證 MQ 訊息完整性與格式
 * - 委派給 BalanceService 進行餘額操作
 * - 統一錯誤處理與日誌記錄
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceFacadeImpl implements BalanceFacade {

    private final BalanceService balanceService;

    @Override
    public void handleBalanceChangeRequest(BalanceChangeMsg msg) {
        try {
            log.info("Processing balance change request: externalId={}, type={}, userId={}, amount={}",
                    msg.getExternalId(), msg.getType(), msg.getUserId(), msg.getAmount());

            // 路由至適當的服務方法
            BalanceChange result;
            if (msg.getType() == BalanceChangeType.TRANSFER_OUT) {
                // 扣款操作
                result = balanceService.debitBalance(
                        msg.getExternalId(),
                        msg.getUserId().trim(),
                        msg.getAmount().abs()
                );
            } else if (msg.getType() == BalanceChangeType.TRANSFER_IN) {
                // 加帳操作
                result = balanceService.creditBalance(
                        msg.getExternalId(),
                        msg.getUserId().trim(),
                        msg.getAmount().abs()
                );
            } else {
                throw new IllegalArgumentException("Unsupported balance change type: " + msg.getType());
            }

            // 記錄處理結果
            log.info("Balance change request processed: externalId={}, userId={}, status={}, type={}",
                    result.getExternalId(), result.getUserId(), result.getStatus(), result.getType());

        } catch (UserNotFoundException e) {
            // 用戶不存在 - 數據一致性問題
            log.error("User not found when processing balance change: externalId={}, userId={}, type={}",
                    msg.getExternalId(), msg.getUserId(), msg.getType(), e);
        }
    }

    @Override
    public CreateUserResponse createUser(CreateUserRequest request) {
        try {
            log.info("Processing create user request: userId={}, initialBalance={}",
                    request.getUserId(), request.getInitialBalance());

            // 1. 清理並準備參數（驗證已在 controller 層完成）
            String userId = request.getUserId().trim();
            BigDecimal initialBalance = request.getInitialBalance();

            // 2. 委派給服務層建立使用者
            UserBalance userBalance = balanceService.createUser(userId, initialBalance);

            // 3. 轉換為 DTO
            CreateUserResponse response = mapToCreateUserResponse(userBalance);

            // 4. 記錄成功日誌
            log.info("User created successfully: userId={}, balance={}, createdAt={}",
                    response.getUserId(), response.getBalance(), response.getCreatedAt());

            return response;

        } catch (UserAlreadyExistsException e) {
            // 使用者已存在 - 業務異常，記錄並重新拋出
            log.error("User already exists: userId={}", request.getUserId(), e);
            throw e;
        }
    }

    @Override
    public GetBalanceResponse getBalance(String userId) {
        try {
            log.info("Processing get balance request: userId={}", userId);

            // 1. 清理 userId（驗證已在 controller 層完成）
            String cleanUserId = userId.trim();

            // 2. 取得餘額
            BigDecimal balance = balanceService.getBalance(cleanUserId);

            // 3. 轉換為 DTO
            GetBalanceResponse response = GetBalanceResponse.builder()
                    .userId(cleanUserId)
                    .balance(balance)
                    .build();

            log.info("Balance retrieved successfully: userId={}, balance={}",
                    cleanUserId, balance);

            return response;

        } catch (UserNotFoundException e) {
            log.error("User not found: userId={}", userId, e);
            throw e;
        }
    }

    /**
     * 將 UserBalance 實體轉換為 CreateUserResponse DTO
     *
     * @param userBalance 使用者餘額實體
     * @return CreateUserResponse DTO
     */
    private CreateUserResponse mapToCreateUserResponse(UserBalance userBalance) {
        return CreateUserResponse.builder()
                .userId(userBalance.getUserId())
                .balance(userBalance.getBalance())
                .createdAt(userBalance.getCreatedAt())
                .version(userBalance.getVersion())
                .build();
    }
}
