package com.example.demo.service;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeStatus;
import com.example.demo.entity.BalanceChangeType;
import com.example.demo.entity.UserBalance;
import com.example.demo.event.BalanceOperationCompletedEvent;
import com.example.demo.exception.UserAlreadyExistsException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.repository.BalanceChangeRepository;
import com.example.demo.repository.UserBalanceRepository;
import com.example.demo.service.impl.BalanceServiceImpl;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BalanceService 單元測試
 *
 * 測試策略：
 * 1. 使用 Mockito mock 所有依賴（Repositories）
 * 2. 純單元測試，不啟動 Spring Context
 * 3. 專注於業務邏輯測試，快取行為由整合測試驗證
 * 4. 使用 AssertJ 進行斷言
 *
 * 測試範圍：
 * - createUser: 建立使用者並設定初始餘額
 * - getBalance: 查詢使用者餘額
 * - validateUserExists: 驗證使用者存在
 * - checkSufficientBalance: 檢查餘額是否足夠
 * - debitBalance: 扣款（含冪等性）
 * - creditBalance: 加帳（含冪等性）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BalanceService Unit Tests")
class BalanceServiceTest {

    @Mock
    private UserBalanceRepository userBalanceRepository;

    @Mock
    private BalanceChangeRepository balanceChangeRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private BalanceServiceImpl balanceService;

    // ==================== Happy Path Tests ====================

    @Test
    @DisplayName("createUser - With valid userId and initial balance - Creates user successfully")
    void createUser_WithValidUserIdAndInitialBalance_CreatesUserSuccessfully() {
        // Given
        String userId = "user_001";
        BigDecimal initialBalance = new BigDecimal("1000.00");

        // Mock: 使用者不存在
        when(userBalanceRepository.existsById(userId)).thenReturn(false);

        // Mock: 儲存成功
        when(userBalanceRepository.save(any(UserBalance.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        UserBalance result = balanceService.createUser(userId, initialBalance);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getBalance()).isEqualByComparingTo(initialBalance);
        assertThat(result.getCreatedAt()).isNotNull();

        // 驗證互動
        verify(userBalanceRepository, times(1)).existsById(userId);
        verify(userBalanceRepository, times(1)).save(any(UserBalance.class));
        // 新建立的使用者無快取，不需要發布快取失效事件
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("createUser - With zero initial balance - Creates user with zero balance")
    void createUser_WithZeroInitialBalance_CreatesUserWithZeroBalance() {
        // Given
        String userId = "user_002";
        BigDecimal initialBalance = BigDecimal.ZERO;

        when(userBalanceRepository.existsById(userId)).thenReturn(false);
        when(userBalanceRepository.save(any(UserBalance.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // When
        UserBalance result = balanceService.createUser(userId, initialBalance);

        // Then
        assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(userBalanceRepository, times(1)).save(any(UserBalance.class));
    }

    @Test
    @DisplayName("createUser - With existing userId - Throws UserAlreadyExistsException")
    void createUser_WithExistingUserId_ThrowsUserAlreadyExistsException() {
        // Given
        String userId = "existing_user";
        BigDecimal initialBalance = new BigDecimal("500.00");

        // Mock: 使用者已存在
        when(userBalanceRepository.existsById(userId)).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> balanceService.createUser(userId, initialBalance))
            .isInstanceOf(UserAlreadyExistsException.class)
            .hasMessageContaining(userId);

        // 驗證不會呼叫 save 和發布事件
        verify(userBalanceRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("getBalance - With existing user - Returns balance")
    void getBalance_WithExistingUser_ReturnsBalance() {
        // Given
        String userId = "user_003";
        BigDecimal dbBalance = new BigDecimal("2000.00");
        UserBalance userBalance = createUserBalance(userId, dbBalance);

        // Mock: 資料庫查詢成功
        when(userBalanceRepository.findById(userId)).thenReturn(Optional.of(userBalance));

        // When
        BigDecimal result = balanceService.getBalance(userId);

        // Then
        assertThat(result).isEqualByComparingTo(dbBalance);
        verify(userBalanceRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("getBalance - With non-existing user - Throws UserNotFoundException")
    void getBalance_WithNonExistingUser_ThrowsUserNotFoundException() {
        // Given
        String userId = "non_existing_user";

        // Mock: 資料庫查詢失敗
        when(userBalanceRepository.findById(userId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> balanceService.getBalance(userId))
            .isInstanceOf(UserNotFoundException.class)
            .hasMessageContaining(userId);
    }

    @Test
    @DisplayName("debitBalance - With valid request - Creates debit balance change successfully")
    void debitBalance_WithValidRequest_CreatesDebitBalanceChangeSuccessfully() {
        // Given
        Long externalId = 12345L;
        String userId = "user_008";
        BigDecimal amount = new BigDecimal("150.00");
        BigDecimal currentBalance = new BigDecimal("1000.00");

        UserBalance userBalance = createUserBalance(userId, currentBalance);

        // Mock: 冪等性檢查 - 不存在
        when(balanceChangeRepository.findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT))
            .thenReturn(Optional.empty());

        // Mock: 鎖定使用者
        when(userBalanceRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(userBalance));

        // Mock: 儲存帳變記錄
        when(balanceChangeRepository.save(any(BalanceChange.class)))
            .thenAnswer(invocation -> {
                BalanceChange change = invocation.getArgument(0);
                change.setId(1L);
                return change;
            });

        // Mock: 儲存使用者餘額
        when(userBalanceRepository.save(any(UserBalance.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // When
        BalanceChange result = balanceService.debitBalance(externalId, userId, amount);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExternalId()).isEqualTo(externalId);
        assertThat(result.getType()).isEqualTo(BalanceChangeType.TRANSFER_OUT);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getAmount()).isEqualByComparingTo(amount.negate()); // 負數
        assertThat(result.getBalanceBefore()).isEqualByComparingTo(currentBalance);
        assertThat(result.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("850.00"));
        assertThat(result.getStatus()).isEqualTo(BalanceChangeStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();

        // 驗證互動
        verify(balanceChangeRepository, times(1))
            .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT);
        verify(userBalanceRepository, times(1)).findByIdForUpdate(userId);
        verify(balanceChangeRepository, times(2)).save(any(BalanceChange.class)); // PROCESSING + COMPLETED
        verify(userBalanceRepository, times(1)).save(any(UserBalance.class));
        verify(eventPublisher, times(1)).publishEvent(any(BalanceOperationCompletedEvent.class));
    }

    @Test
    @DisplayName("debitBalance - With existing balance change (idempotent) - Returns existing record")
    void debitBalance_WithExistingBalanceChange_ReturnsExistingRecord() {
        // Given
        Long externalId = 12346L;
        String userId = "user_009";
        BigDecimal amount = new BigDecimal("200.00");

        BalanceChange existingChange = BalanceChange.builder()
            .id(1L)
            .externalId(externalId)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId(userId)
            .amount(amount.negate())
            .status(BalanceChangeStatus.COMPLETED)
            .build();

        // Mock: 冪等性檢查 - 已存在
        when(balanceChangeRepository.findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT))
            .thenReturn(Optional.of(existingChange));

        // When
        BalanceChange result = balanceService.debitBalance(externalId, userId, amount);

        // Then
        assertThat(result).isEqualTo(existingChange);
        verify(eventPublisher, times(1)).publishEvent(any());

        // 驗證不會執行後續操作
        verify(balanceChangeRepository, times(1))
            .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT);
        verify(userBalanceRepository, never()).findByIdForUpdate(anyString());
        verify(balanceChangeRepository, times(0)).save(any());
    }

    @Test
    @DisplayName("debitBalance - With insufficient balance - Returns FAILED balance change")
    void debitBalance_WithInsufficientBalance_ReturnsFailedBalanceChange() {
        // Given
        Long externalId = 12347L;
        String userId = "user_010";
        BigDecimal amount = new BigDecimal("1500.00");
        BigDecimal currentBalance = new BigDecimal("1000.00");

        UserBalance userBalance = createUserBalance(userId, currentBalance);

        // Mock: 冪等性檢查 - 不存在
        when(balanceChangeRepository.findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT))
            .thenReturn(Optional.empty());

        // Mock: 鎖定使用者
        when(userBalanceRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(userBalance));

        // Mock: 儲存失敗的帳變記錄
        when(balanceChangeRepository.save(any(BalanceChange.class)))
            .thenAnswer(invocation -> {
                BalanceChange change = invocation.getArgument(0);
                change.setId(99L);
                return change;
            });

        // When
        BalanceChange result = balanceService.debitBalance(externalId, userId, amount);

        // Then: 應返回 FAILED 狀態的記錄
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(BalanceChangeStatus.FAILED);
        assertThat(result.getExternalId()).isEqualTo(externalId);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getFailureReason()).contains("Insufficient balance");

        // 驗證不會更新使用者餘額（只儲存失敗記錄）
        verify(userBalanceRepository, never()).save(any());
        verify(balanceChangeRepository, times(1)).save(any(BalanceChange.class));
        // 驗證發布 BalanceOperationCompletedEvent（用於 MQ 通知）
        verify(eventPublisher, times(1)).publishEvent(any());
    }

    @Test
    @DisplayName("creditBalance - With valid request - Creates credit balance change successfully")
    void creditBalance_WithValidRequest_CreatesCreditBalanceChangeSuccessfully() {
        // Given
        Long externalId = 12348L;
        String userId = "user_011";
        BigDecimal amount = new BigDecimal("300.00");
        BigDecimal currentBalance = new BigDecimal("500.00");

        UserBalance userBalance = createUserBalance(userId, currentBalance);

        // Mock: 冪等性檢查 - 不存在
        when(balanceChangeRepository.findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN))
            .thenReturn(Optional.empty());

        // Mock: 鎖定使用者
        when(userBalanceRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(userBalance));

        // Mock: 儲存帳變記錄
        when(balanceChangeRepository.save(any(BalanceChange.class)))
            .thenAnswer(invocation -> {
                BalanceChange change = invocation.getArgument(0);
                change.setId(2L);
                return change;
            });

        // Mock: 儲存使用者餘額
        when(userBalanceRepository.save(any(UserBalance.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // When
        BalanceChange result = balanceService.creditBalance(externalId, userId, amount);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExternalId()).isEqualTo(externalId);
        assertThat(result.getType()).isEqualTo(BalanceChangeType.TRANSFER_IN);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getAmount()).isEqualByComparingTo(amount); // 正數
        assertThat(result.getBalanceBefore()).isEqualByComparingTo(currentBalance);
        assertThat(result.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(result.getStatus()).isEqualTo(BalanceChangeStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();

        // 驗證互動
        verify(balanceChangeRepository, times(1))
            .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN);
        verify(userBalanceRepository, times(1)).findByIdForUpdate(userId);
        verify(balanceChangeRepository, times(2)).save(any(BalanceChange.class));
        verify(userBalanceRepository, times(1)).save(any(UserBalance.class));
        verify(eventPublisher, times(1)).publishEvent(any(BalanceOperationCompletedEvent.class));
    }

    @Test
    @DisplayName("creditBalance - With existing balance change (idempotent) - Returns existing record")
    void creditBalance_WithExistingBalanceChange_ReturnsExistingRecord() {
        // Given
        Long externalId = 12349L;
        String userId = "user_012";
        BigDecimal amount = new BigDecimal("400.00");

        BalanceChange existingChange = BalanceChange.builder()
            .id(3L)
            .externalId(externalId)
            .type(BalanceChangeType.TRANSFER_IN)
            .userId(userId)
            .amount(amount)
            .status(BalanceChangeStatus.COMPLETED)
            .build();

        // Mock: 冪等性檢查 - 已存在
        when(balanceChangeRepository.findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN))
            .thenReturn(Optional.of(existingChange));

        // When
        BalanceChange result = balanceService.creditBalance(externalId, userId, amount);

        // Then
        assertThat(result).isEqualTo(existingChange);
        verify(eventPublisher, times(1)).publishEvent(any());

        // 驗證不會執行後續操作
        verify(balanceChangeRepository, times(1))
            .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN);
        verify(userBalanceRepository, never()).findByIdForUpdate(anyString());
    }

    @Test
    @DisplayName("evictBalanceCache - With valid userId - Method completes without error")
    void evictBalanceCache_WithValidUserId_CompletesWithoutError() {
        // Given
        String userId = "user_001";

        // When & Then - 不應拋出異常
        assertThatCode(() -> balanceService.evictBalanceCache(userId))
            .doesNotThrowAnyException();
    }

    // ==================== Helper Methods ====================

    /**
     * 建立測試用的 UserBalance 物件
     */
    private UserBalance createUserBalance(String userId, BigDecimal balance) {
        return UserBalance.builder()
            .userId(userId)
            .balance(balance)
            .createdAt(LocalDateTime.now())
            .build();
    }
}