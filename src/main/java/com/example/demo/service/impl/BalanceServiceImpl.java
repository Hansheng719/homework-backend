package com.example.demo.service.impl;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeStatus;
import com.example.demo.entity.BalanceChangeType;
import com.example.demo.entity.UserBalance;
import com.example.demo.event.BalanceOperationCompletedEvent;
import com.example.demo.exception.InsufficientBalanceException;
import com.example.demo.exception.UserAlreadyExistsException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.repository.BalanceChangeRepository;
import com.example.demo.repository.UserBalanceRepository;
import com.example.demo.service.BalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * BalanceService 實作類別
 *
 * 功能：管理使用者餘額和帳變操作
 *
 * 實作重點：
 * 1. 使用 @Transactional 確保資料一致性
 * 2. 冪等性保證（debit/credit 透過 external_id + type 檢查）
 * 3. 快取策略（優先讀取快取，更新後同步快取）
 * 4. 悲觀鎖（findByIdForUpdate）避免並發問題
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceServiceImpl implements BalanceService {

    private final UserBalanceRepository userBalanceRepository;
    private final BalanceChangeRepository balanceChangeRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 建立使用者並設定初始餘額
     */
    @Override
    @Transactional
    public UserBalance createUser(String userId, BigDecimal initialBalance) {
        // 檢查使用者是否已存在
        if (userBalanceRepository.existsById(userId)) {
            throw new UserAlreadyExistsException(userId);
        }

        // 建立使用者餘額記錄
        UserBalance userBalance = UserBalance.builder()
            .userId(userId)
            .balance(initialBalance)
            .createdAt(LocalDateTime.now())
            .build();

        // 儲存到資料庫
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // 新建立的使用者無快取資料，不需要發布快取失效事件

        return savedUserBalance;
    }

    /**
     * 查詢使用者餘額（使用 Spring Cache）
     */
    @Override
    @Cacheable(value = "balance", key = "#userId")
    public BigDecimal getBalance(String userId) {
        return userBalanceRepository.findById(userId)
            .map(UserBalance::getBalance)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Override
    @CacheEvict(value = "balance", key = "#userId")
    public void evictBalanceCache(String userId) {
        log.debug("evict balance cache, userId: {}", userId);
    }

    /**
     * 扣減使用者餘額（含冪等性保證）
     *
     * 行為說明：
     * - 用戶不存在：抛出 UserNotFoundException（不創建 BalanceChange 記錄）
     * - 餘額不足：創建 FAILED 狀態的 BalanceChange 記錄並返回（不抛出異常）
     * - 操作成功：創建 COMPLETED 狀態的 BalanceChange 記錄並返回
     */
    @Override
    @Transactional
    public BalanceChange debitBalance(Long externalId, String userId, BigDecimal amount) {
        // 1. 冪等性檢查
        Optional<BalanceChange> existingChange = balanceChangeRepository
            .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT);

        if (existingChange.isPresent()) {
            eventPublisher.publishEvent(new BalanceOperationCompletedEvent(this, existingChange.get()));
            return existingChange.get(); // 返回 COMPLETED 或 FAILED 記錄
        }

        // 2. 鎖定使用者（悲觀鎖）
        Optional<UserBalance> userBalanceOpt = userBalanceRepository.findByIdForUpdate(userId);

        // 3. 如果用戶不存在，抛出異常（不創建 BalanceChange）
        if (userBalanceOpt.isEmpty()) {
            throw new UserNotFoundException(userId);
        }

        UserBalance userBalance = userBalanceOpt.get();

        // 4. 檢查餘額是否充足
        if (userBalance.getBalance().compareTo(amount) < 0) {
            // 餘額不足：創建 FAILED 記錄並返回，不抛出異常
            BalanceChange failedChange = createFailedBalanceChange(
                externalId,
                BalanceChangeType.TRANSFER_OUT,
                userId,
                amount,
                String.format("Insufficient balance: current=%s, required=%s",
                    userBalance.getBalance(), amount)
            );

            // 發布操作完成事件（不清除快取因為餘額未變更，但會發送 MQ 通知失敗）
            eventPublisher.publishEvent(new BalanceOperationCompletedEvent(this, failedChange));

            log.warn("Debit failed - insufficient balance: externalId={}, userId={}, current={}, required={}",
                externalId, userId, userBalance.getBalance(), amount);
            return failedChange;
        }

        // 5. 餘額充足，執行扣款操作
        // 建立帳變記錄（PROCESSING 狀態）
        BalanceChange balanceChange = BalanceChange.builder()
            .externalId(externalId)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId(userId)
            .amount(amount.negate()) // 扣款為負數
            .balanceBefore(userBalance.getBalance())
            .status(BalanceChangeStatus.PROCESSING)
            .createdAt(LocalDateTime.now())
            .build();

        balanceChangeRepository.save(balanceChange);

        // 更新使用者餘額
        BigDecimal newBalance = userBalance.getBalance().subtract(amount);
        userBalance.setBalance(newBalance);
        userBalanceRepository.save(userBalance);

        // 完成帳變記錄（COMPLETED 狀態）
        balanceChange.setBalanceAfter(newBalance);
        balanceChange.setStatus(BalanceChangeStatus.COMPLETED);
        balanceChange.setCompletedAt(LocalDateTime.now());
        balanceChangeRepository.save(balanceChange);

        // 發布操作完成事件（事務提交後會清除快取並發送 MQ）
        eventPublisher.publishEvent(new BalanceOperationCompletedEvent(this, balanceChange));

        return balanceChange;
    }

    /**
     * 增加使用者餘額（含冪等性保證）
     *
     * 行為說明：
     * - 用戶不存在：抛出 UserNotFoundException（不創建 BalanceChange 記錄）
     * - 操作成功：創建 COMPLETED 狀態的 BalanceChange 記錄並返回
     */
    @Override
    @Transactional
    public BalanceChange creditBalance(Long externalId, String userId, BigDecimal amount) {
        // 1. 冪等性檢查
        Optional<BalanceChange> existingChange = balanceChangeRepository
            .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN);

        if (existingChange.isPresent()) {
            eventPublisher.publishEvent(new BalanceOperationCompletedEvent(this, existingChange.get()));
            return existingChange.get(); // 返回 COMPLETED 或 FAILED 記錄
        }

        // 2. 鎖定使用者（悲觀鎖）
        Optional<UserBalance> userBalanceOpt = userBalanceRepository.findByIdForUpdate(userId);

        // 3. 如果用戶不存在，抛出異常（不創建 BalanceChange）
        if (userBalanceOpt.isEmpty()) {
            throw new UserNotFoundException(userId);
        }

        UserBalance userBalance = userBalanceOpt.get();

        // 4. 執行加帳操作
        // 建立帳變記錄（PROCESSING 狀態）
        BalanceChange balanceChange = BalanceChange.builder()
            .externalId(externalId)
            .type(BalanceChangeType.TRANSFER_IN)
            .userId(userId)
            .amount(amount) // 加帳為正數
            .balanceBefore(userBalance.getBalance())
            .status(BalanceChangeStatus.PROCESSING)
            .createdAt(LocalDateTime.now())
            .build();

        balanceChangeRepository.save(balanceChange);

        // 更新使用者餘額
        BigDecimal newBalance = userBalance.getBalance().add(amount);
        userBalance.setBalance(newBalance);
        userBalanceRepository.save(userBalance);

        // 完成帳變記錄（COMPLETED 狀態）
        balanceChange.setBalanceAfter(newBalance);
        balanceChange.setStatus(BalanceChangeStatus.COMPLETED);
        balanceChange.setCompletedAt(LocalDateTime.now());
        balanceChangeRepository.save(balanceChange);

        // 發布操作完成事件（事務提交後會清除快取並發送 MQ）
        eventPublisher.publishEvent(new BalanceOperationCompletedEvent(this, balanceChange));

        return balanceChange;
    }

    /**
     * 建立失敗的餘額變更記錄
     *
     * @param externalId 外部冪等 ID
     * @param type 帳變類型
     * @param userId 使用者 ID
     * @param amount 金額（正數）
     * @param failureReason 失敗原因
     * @return 失敗的餘額變更記錄
     */
    private BalanceChange createFailedBalanceChange(
        Long externalId,
        BalanceChangeType type,
        String userId,
        BigDecimal amount,
        String failureReason
    ) {
        BalanceChange failedChange = BalanceChange.builder()
            .externalId(externalId)
            .type(type)
            .userId(userId)
            .amount(type == BalanceChangeType.TRANSFER_OUT ? amount.negate() : amount)
            .status(BalanceChangeStatus.FAILED)
            .failureReason(failureReason)
            .createdAt(LocalDateTime.now())
            .build();

        return balanceChangeRepository.save(failedChange);
    }
}
