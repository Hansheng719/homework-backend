package com.example.demo.service.impl;

import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import com.example.demo.event.TransferStatusChangedEvent;
import com.example.demo.exception.InvalidTransferStateException;
import com.example.demo.exception.TransferNotFoundException;
import com.example.demo.repository.TransferRepository;
import com.example.demo.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * TransferService 實作類別

 * 功能：管理轉帳記錄的 CRUD 操作和狀態管理

 * 實作重點：
 * 1. 使用 @Transactional 確保資料一致性
 * 2. 使用悲觀鎖（findByIdForUpdate）防止並發問題
 * 3. 單一職責原則：每個方法完成一個原子性操作
 * 4. 狀態轉換驗證：使用 TransferStateTransitionValidator 驗證所有狀態轉換
 * 5. 事件發布：所有狀態變更後發布 TransferStatusChangedEvent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private final TransferRepository transferRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Transfer createPendingTransfer(String fromUserId, String toUserId, BigDecimal amount) {
        Transfer transfer = new Transfer();
        transfer.setFromUserId(fromUserId);
        transfer.setToUserId(toUserId);
        transfer.setAmount(amount);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setCreatedAt(LocalDateTime.now());

        Transfer savedTransfer = transferRepository.save(transfer);

        // 發布狀態變更事件（oldStatus = null 表示新建）
        eventPublisher.publishEvent(new TransferStatusChangedEvent(this, savedTransfer, null));

        log.info("Created pending transfer: id={}, from={}, to={}, amount={}",
            savedTransfer.getId(), fromUserId, toUserId, amount);

        return savedTransfer;
    }

    /**
     * 內部輔助方法：使用悲觀鎖更新狀態（含完整驗證和事件發布）
     *
     * 所有 public 狀態更新方法都調用此方法
     *
     * @param transferId 轉帳 ID
     * @param newStatus 新狀態
     * @return Optional<Transfer> 成功更新則返回 Transfer，若轉帳不存在或狀態轉換不合法則返回 Optional.empty()
     */
    private Optional<Transfer> transitionStatusWithLock(Long transferId, TransferStatus newStatus) {
        Optional<Transfer> transferOpt = transferRepository.findByIdForUpdate(transferId);

        if (transferOpt.isEmpty()) {
            log.warn("Transfer not found for update: id={}", transferId);
            return Optional.empty();
        }

        Transfer transfer = transferOpt.get();
        TransferStatus oldStatus = transfer.getStatus();

        // 使用 TransferStateTransitionValidator 驗證狀態流轉
        if (!TransferStateTransitionValidator.isTransitionAllowed(oldStatus, newStatus)) {
            log.warn("Invalid state transition: id={}, from={}, to={}", transferId, oldStatus, newStatus);
            return Optional.empty();
        }

        transfer.setStatus(newStatus);
        Transfer savedTransfer = transferRepository.save(transfer);

        // 發布狀態變更事件
        eventPublisher.publishEvent(new TransferStatusChangedEvent(this, savedTransfer, oldStatus));

        log.info("Updated transfer status: id={}, {} -> {}", transferId, oldStatus, newStatus);

        return Optional.of(savedTransfer);
    }

    @Override
    @Transactional
    public void cancelTransfer(Long transferId) {
        Optional<Transfer> transferOpt = transitionStatusWithLock(transferId, TransferStatus.CANCELLED);

        if (transferOpt.isEmpty()) {
            Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
            throw new InvalidTransferStateException(
                String.format("Cannot cancel transfer: id=%d, current status=%s. Only PENDING transfers can be cancelled.",
                    transferId, transfer.getStatus())
            );
        }

        // 檢查10分鐘取消窗口
        Transfer transfer = transferOpt.get();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowExpiry = transfer.getCreatedAt().plusMinutes(10);

        if (now.isAfter(windowExpiry)) {
            throw new InvalidTransferStateException(
                String.format("Cannot cancel transfer: id=%d. The 10 minute cancellation window has expired.",
                    transferId)
            );
        }

        // 設置取消時間
        transfer.setCancelledAt(LocalDateTime.now());
        transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public void markDebitProcessing(Long transferId) {
        Optional<Transfer> transferOpt = transitionStatusWithLock(transferId, TransferStatus.DEBIT_PROCESSING);

        if (transferOpt.isEmpty()) {
            Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
            throw new InvalidTransferStateException(
                String.format("Cannot mark debit processing: id=%d, current status=%s. Must be in PROCESSING state.",
                    transferId, transfer.getStatus())
            );
        }
    }

    @Override
    @Transactional
    public void handleDebitSuccess(Long transferId) {
        Optional<Transfer> transferOpt = transitionStatusWithLock(transferId, TransferStatus.CREDIT_PROCESSING);

        if (transferOpt.isEmpty()) {
            Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
            throw new InvalidTransferStateException(
                String.format("Cannot mark debit completed: id=%d, current status=%s. Must be in DEBIT_PROCESSING state.",
                    transferId, transfer.getStatus())
            );
        }
    }

    @Override
    @Transactional
    public void handleDebitFailure(Long transferId, String reason) {
        Optional<Transfer> transferOpt = transitionStatusWithLock(transferId, TransferStatus.DEBIT_FAILED);

        if (transferOpt.isEmpty()) {
            Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
            throw new InvalidTransferStateException(
                String.format("Cannot mark debit failed: id=%d, current status=%s. Must be in DEBIT_PROCESSING state.",
                    transferId, transfer.getStatus())
            );
        }

        // 設置失敗原因和取消時間
        Transfer transfer = transferOpt.get();
        if (reason != null && reason.length() > 255) {
            transfer.setFailureReason(reason.substring(0, 255));
        } else {
            transfer.setFailureReason(reason);
        }
        transfer.setCancelledAt(LocalDateTime.now());
        transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public void markCreditProcessing(Long transferId) {
        Optional<Transfer> transferOpt = transitionStatusWithLock(transferId, TransferStatus.CREDIT_PROCESSING);

        if (transferOpt.isEmpty()) {
            Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
            throw new InvalidTransferStateException(
                String.format("Cannot mark credit processing: id=%d, current status=%s. Must be in DEBIT_COMPLETED state.",
                    transferId, transfer.getStatus())
            );
        }
    }

    @Override
    public List<Transfer> findPendingTransfers(LocalDateTime cutoffTime, int batchSize) {
        return transferRepository.findPendingTransfers(
            TransferStatus.PENDING,
            cutoffTime,
            PageRequest.of(0, batchSize)
        );
    }

    @Override
    @Transactional
    public void completeTransfer(Long transferId) {
        Optional<Transfer> transferOpt = transitionStatusWithLock(transferId, TransferStatus.COMPLETED);

        if (transferOpt.isEmpty()) {
            Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
            throw new InvalidTransferStateException(
                String.format("Cannot complete transfer: id=%d, current status=%s. Must be in CREDIT_PROCESSING state.",
                    transferId, transfer.getStatus())
            );
        }

        // 設置完成時間
        Transfer transfer = transferOpt.get();
        transfer.setCompletedAt(LocalDateTime.now());
        transferRepository.save(transfer);
    }

    @Override
    public Transfer findById(Long transferId) {
        return transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    @Override
    public Page<Transfer> getTransferHistory(String userId, int page, int size) {
        log.info("Fetching transfer history: userId={}, page={}, size={}", userId, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Transfer> transfers = transferRepository.findByFromUserIdOrToUserId(userId, userId, pageable);

        log.debug("Transfer history fetched: userId={}, totalElements={}, totalPages={}",
                userId, transfers.getTotalElements(), transfers.getTotalPages());

        return transfers;
    }
}
