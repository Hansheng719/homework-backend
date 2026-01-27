package com.example.demo.facade.impl;

import com.example.demo.entity.BalanceChangeType;
import com.example.demo.entity.Transfer;
import com.example.demo.exception.InsufficientBalanceException;
import com.example.demo.exception.InvalidTransferStateException;
import com.example.demo.exception.TransferNotFoundException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.facade.TransferFacade;
import com.example.demo.facade.dto.*;
import com.example.demo.mq.msg.BalanceChangeResultMsg;
import com.example.demo.service.BalanceService;
import com.example.demo.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * TransferFacade 實作
 *
 * 協調邏輯：
 * - 委派給 TransferService 進行狀態轉換
 * - 依賴事件驅動架構處理後續動作
 * - 不直接呼叫 MQ producer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferFacadeImpl implements TransferFacade {

    private final TransferService transferService;
    private final BalanceService balanceService;
    private final com.example.demo.mq.producer.BalanceChangeProducer balanceChangeProducer;

    @Override
    public void handleBalanceChangeResult(BalanceChangeResultMsg msg) {
        log.info("processing balance change result msg: {}", msg);
        if (msg.getType() == BalanceChangeType.TRANSFER_OUT) {
            // 處理扣款結果
            if (msg.getSuccess()) {
                transferService.handleDebitSuccess(msg.getExternalId());
                log.info("Debit success processed: transferId={}", msg.getExternalId());
            } else {
                transferService.handleDebitFailure(msg.getExternalId(), msg.getFailureReason());
                log.warn("Debit failed: transferId={}, reason={}",
                        msg.getExternalId(), msg.getFailureReason());
            }
        } else if (msg.getType() == BalanceChangeType.TRANSFER_IN) {
            // 處理加帳結果
            if (msg.getSuccess()) {
                transferService.completeTransfer(msg.getExternalId());
                log.info("Transfer completed: transferId={}", msg.getExternalId());
            } else {
                log.error("Credit failed: transferId={}, reason={}",
                        msg.getExternalId(), msg.getFailureReason());
                throw new RuntimeException("Credit operation failed: " + msg.getFailureReason());
            }
        } else {
            throw new IllegalArgumentException("Unsupported balance change type: " + msg.getType());
        }
    }

    @Override
    public CreateTransferResponse createTransfer(CreateTransferRequest request) {
        try {
            log.info("Processing create transfer request: fromUserId={}, toUserId={}, amount={}",
                    request.getFromUserId(), request.getToUserId(), request.getAmount());

            // 1. 清理並準備參數（基本驗證已在 controller 層完成）
            String fromUserId = request.getFromUserId().trim();
            String toUserId = request.getToUserId().trim();
            BigDecimal amount = request.getAmount();

            // 2. 業務驗證：檢查使用者存在性和餘額充足性
            validateTransferPreconditions(fromUserId, toUserId, amount);

            // 3. 委派給服務層建立 PENDING 轉帳
            Transfer transfer = transferService.createPendingTransfer(fromUserId, toUserId, amount);

            // 4. 轉換為 DTO
            CreateTransferResponse response = mapToCreateTransferResponse(transfer);

            // 5. 記錄成功日誌
            log.info("Transfer created successfully: transferId={}, fromUserId={}, toUserId={}, amount={}, status={}, createdAt={}",
                    response.getId(), response.getFromUserId(), response.getToUserId(),
                    response.getAmount(), response.getStatus(), response.getCreatedAt());

            return response;

        } catch (UserNotFoundException e) {
            // 使用者不存在 - 業務異常，記錄並重新拋出
            log.error("User not found when creating transfer: fromUserId={}, toUserId={}",
                    request.getFromUserId(), request.getToUserId(), e);
            throw e;

        } catch (InsufficientBalanceException e) {
            // 餘額不足 - 業務異常，記錄並重新拋出
            log.error("Insufficient balance when creating transfer: fromUserId={}, amount={}, currentBalance={}, requiredAmount={}",
                    request.getFromUserId(), request.getAmount(),
                    e.getCurrentBalance(), e.getRequiredAmount(), e);
            throw e;

        } catch (IllegalArgumentException e) {
            // 參數驗證失敗 - 記錄並重新拋出
            log.error("Invalid request parameters: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public CancelTransferResponse cancelTransfer(Long transferId) {
        try {
            log.info("Processing cancel transfer request: transferId={}", transferId);

            // 1. 取消轉帳（驗證已在 controller 層完成）
            transferService.cancelTransfer(transferId);

            // 2. 取得取消後的轉帳資訊
            Transfer transfer = transferService.findById(transferId);

            // 3. 轉換為 DTO
            CancelTransferResponse response = CancelTransferResponse.builder()
                    .id(transfer.getId())
                    .fromUserId(transfer.getFromUserId())
                    .toUserId(transfer.getToUserId())
                    .amount(transfer.getAmount())
                    .status(transfer.getStatus())
                    .cancelledAt(transfer.getCancelledAt())
                    .build();

            log.info("Transfer cancelled successfully: transferId={}, cancelledAt={}",
                    transferId, transfer.getCancelledAt());

            return response;

        } catch (TransferNotFoundException | InvalidTransferStateException e) {
            log.error("Failed to cancel transfer: transferId={}", transferId, e);
            throw e;
        }
    }

    @Override
    public TransferHistoryResponse getTransferHistory(String userId, int page, int size) {
        try {
            log.info("Processing get transfer history request: userId={}, page={}, size={}",
                    userId, page, size);

            // 1. 清理 userId（基本驗證已在 controller 層完成）
            String cleanUserId = userId.trim();

            // 2. 業務驗證：檢查使用者是否存在
            balanceService.getBalance(cleanUserId);

            // 3. 取得分頁轉帳資料
            Page<Transfer> transferPage = transferService.getTransferHistory(cleanUserId, page, size);

            // 4. 轉換為 DTO
            List<TransferItemDto> transfers = transferPage.getContent().stream()
                    .map(this::mapToTransferItemDto)
                    .toList();

            // 5. 建立分頁元資料
            PaginationMeta pagination = PaginationMeta.builder()
                    .currentPage(transferPage.getNumber())
                    .pageSize(transferPage.getSize())
                    .totalElements(transferPage.getTotalElements())
                    .totalPages(transferPage.getTotalPages())
                    .hasNext(transferPage.hasNext())
                    .hasPrevious(transferPage.hasPrevious())
                    .build();

            // 6. 建立回應
            TransferHistoryResponse response = TransferHistoryResponse.builder()
                    .transfers(transfers)
                    .pagination(pagination)
                    .build();

            log.info("Transfer history retrieved: userId={}, recordCount={}, totalElements={}",
                    cleanUserId, transfers.size(), pagination.getTotalElements());

            return response;

        } catch (UserNotFoundException e) {
            log.error("User not found when fetching transfer history: userId={}", userId, e);
            throw e;
        }
    }

    /**
     * 將 Transfer 實體轉換為 TransferItemDto
     *
     * @param transfer 轉帳實體
     * @return TransferItemDto DTO
     */
    private TransferItemDto mapToTransferItemDto(Transfer transfer) {
        return TransferItemDto.builder()
                .id(transfer.getId())
                .fromUserId(transfer.getFromUserId())
                .toUserId(transfer.getToUserId())
                .amount(transfer.getAmount())
                .status(transfer.getStatus())
                .createdAt(transfer.getCreatedAt())
                .completedAt(transfer.getCompletedAt())
                .cancelledAt(transfer.getCancelledAt())
                .failureReason(transfer.getFailureReason())
                .build();
    }

    /**
     * 驗證轉帳前置條件（使用者存在性和餘額充足性）
     *
     * 這是業務邏輯驗證，不是輸入驗證
     *
     * @param fromUserId 付款方使用者 ID
     * @param toUserId 收款方使用者 ID
     * @param amount 轉帳金額
     * @throws UserNotFoundException 使用者不存在
     * @throws InsufficientBalanceException 餘額不足
     */
    private void validateTransferPreconditions(String fromUserId, String toUserId, BigDecimal amount) {
        // 1. 檢查付款方使用者存在性並獲取餘額
        // getBalance() 會在使用者不存在時拋出 UserNotFoundException
        BigDecimal fromUserBalance = balanceService.getBalance(fromUserId);
        log.debug("FromUser balance check: userId={}, balance={}", fromUserId, fromUserBalance);

        // 2. 檢查收款方使用者存在性
        // getBalance() 會在使用者不存在時拋出 UserNotFoundException
        balanceService.getBalance(toUserId);
        log.debug("ToUser existence check: userId={}", toUserId);

        // 3. 檢查付款方餘額是否充足
        if (fromUserBalance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(fromUserId, fromUserBalance, amount);
        }
        log.debug("Balance check passed: userId={}, balance={}, requiredAmount={}",
                fromUserId, fromUserBalance, amount);
    }

    /**
     * 將 Transfer 實體轉換為 CreateTransferResponse DTO
     *
     * @param transfer 轉帳實體
     * @return CreateTransferResponse DTO
     */
    private CreateTransferResponse mapToCreateTransferResponse(Transfer transfer) {
        return CreateTransferResponse.builder()
                .id(transfer.getId())
                .fromUserId(transfer.getFromUserId())
                .toUserId(transfer.getToUserId())
                .amount(transfer.getAmount())
                .status(transfer.getStatus())
                .createdAt(transfer.getCreatedAt())
                .build();
    }

    @Override
    public int processPendingTransfers(int delaySeconds, int batchSize) {
        log.info("Processing PENDING transfers: delaySeconds={}, batchSize={}",
                delaySeconds, batchSize);

        LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(delaySeconds);

        try {
            List<Transfer> pendingTransfers = transferService.findPendingTransfers(
                    cutoffTime,
                    batchSize
            );

            int processedCount = 0;
            int totalFound = pendingTransfers.size();

            log.info("Found {} PENDING transfers to process", totalFound);

            for (Transfer transfer : pendingTransfers) {
                try {
                    // Transition to DEBIT_PROCESSING
                    // This triggers TransferStatusChangedListener
                    // which sends RocketMQ message
                    transferService.markDebitProcessing(transfer.getId());
                    processedCount++;

                    log.debug("Marked transfer {} for debit processing", transfer.getId());
                } catch (Exception e) {
                    log.error("Failed to process transfer {}: {}",
                            transfer.getId(), e.getMessage(), e);
                    // Continue processing other transfers
                }
            }

            log.info("Processed {} of {} PENDING transfers", processedCount, totalFound);
            return processedCount;

        } catch (Exception e) {
            log.error("Failed to process pending transfers: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process pending transfers", e);
        }
    }

    @Override
    public int processDebitProcessingTransfers(int delaySeconds, int batchSize) {
        log.info("Processing DEBIT_PROCESSING transfers for retry: delaySeconds={}, batchSize={}",
                delaySeconds, batchSize);

        LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(delaySeconds);

        try {
            List<Transfer> debitProcessingTransfers = transferService.findDebitProcessingTransfers(
                    cutoffTime,
                    batchSize
            );

            int processedCount = 0;
            int totalFound = debitProcessingTransfers.size();

            log.info("Found {} DEBIT_PROCESSING transfers to retry", totalFound);

            for (Transfer transfer : debitProcessingTransfers) {
                try {
                    // Re-send debit request to MQ (idempotent operation)
                    balanceChangeProducer.sendDebitRequest(
                            transfer.getId(),
                            transfer.getFromUserId(),
                            transfer.getAmount()
                    );

                    // Update timestamp to avoid immediate retry
                    transferService.updateTransferTimestamp(transfer.getId());

                    processedCount++;

                    log.debug("Retried debit request for transfer {}", transfer.getId());
                } catch (Exception e) {
                    log.error("Failed to retry debit request for transfer {}: {}",
                            transfer.getId(), e.getMessage(), e);
                    // Continue processing other transfers
                }
            }

            log.info("Retried {} of {} DEBIT_PROCESSING transfers", processedCount, totalFound);
            return processedCount;

        } catch (Exception e) {
            log.error("Failed to process DEBIT_PROCESSING transfers: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process DEBIT_PROCESSING transfers", e);
        }
    }

    @Override
    public int processCreditProcessingTransfers(int delaySeconds, int batchSize) {
        log.info("Processing CREDIT_PROCESSING transfers for retry: delaySeconds={}, batchSize={}",
                delaySeconds, batchSize);

        LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(delaySeconds);

        try {
            List<Transfer> creditProcessingTransfers = transferService.findCreditProcessingTransfers(
                    cutoffTime,
                    batchSize
            );

            int processedCount = 0;
            int totalFound = creditProcessingTransfers.size();

            log.info("Found {} CREDIT_PROCESSING transfers to retry", totalFound);

            for (Transfer transfer : creditProcessingTransfers) {
                try {
                    // Re-send credit request to MQ (idempotent operation)
                    balanceChangeProducer.sendCreditRequest(
                            transfer.getId(),
                            transfer.getToUserId(),
                            transfer.getAmount()
                    );

                    // Update timestamp to avoid immediate retry
                    transferService.updateTransferTimestamp(transfer.getId());

                    processedCount++;

                    log.debug("Retried credit request for transfer {}", transfer.getId());
                } catch (Exception e) {
                    log.error("Failed to retry credit request for transfer {}: {}",
                            transfer.getId(), e.getMessage(), e);
                    // Continue processing other transfers
                }
            }

            log.info("Retried {} of {} CREDIT_PROCESSING transfers", processedCount, totalFound);
            return processedCount;

        } catch (Exception e) {
            log.error("Failed to process CREDIT_PROCESSING transfers: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process CREDIT_PROCESSING transfers", e);
        }
    }
}
