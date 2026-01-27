package com.example.demo.schedule;

import com.example.demo.config.SchedulerProperties;
import com.example.demo.facade.TransferFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Transfer Processor Scheduled Job
 *
 * 定時任務：自動處理待處理的轉帳
 *
 * 職責：
 * - 按照配置的 cron 表達式定時觸發
 * - 委派給 TransferFacade.processPendingTransfers() 執行業務邏輯
 * - 捕獲並記錄執行過程中的異常
 *
 * 配置：
 * - scheduler.transfer-processor.cron: 定時執行的 cron 表達式
 * - scheduler.transfer-processor.delay-seconds: 處理延遲秒數
 * - scheduler.transfer-processor.batch-size: 批次大小
 *
 * 環境配置：
 * - Local/Test: 每 5 秒執行一次，處理 3 秒前的轉帳
 * - Production: 每 5 分鐘執行一次，處理 10 分鐘前的轉帳
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferProcessorJob {

    private final TransferFacade transferFacade;
    private final SchedulerProperties schedulerProperties;

    /**
     * 定時處理待處理的轉帳
     *
     * 觸發時機：根據 scheduler.transfer-processor.pending-cron 配置
     */
    @Scheduled(cron = "${scheduler.transfer-processor.pending-cron}")
    @SchedulerLock(
        name = "processPendingTransfers",
        lockAtMostFor = "${scheduler.transfer-processor.pending-lock-at-most-seconds}s",
        lockAtLeastFor = "${scheduler.transfer-processor.pending-lock-at-least-seconds}s"
    )
    public void processPendingTransfers() {
        log.info("Starting scheduled job to process PENDING transfers (lock acquired)");

        try {
            int processedCount = transferFacade.processPendingTransfers(
                    schedulerProperties.getPendingDelaySeconds(),
                    schedulerProperties.getPendingBatchSize()
            );

            log.info("Scheduled job completed: processed {} PENDING transfers", processedCount);
        } catch (Exception e) {
            log.error("PENDING transfers scheduled job failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 定時重試停滯在 DEBIT_PROCESSING 的轉帳
     *
     * 觸發時機：根據 scheduler.transfer-processor.debit-processing-cron 配置
     *
     * 職責：
     * - 查詢 DEBIT_PROCESSING 狀態超過指定時間的轉帳
     * - 重新發送扣款 MQ 事件
     * - 利用 BalanceService 冪等性確保不重複處理
     */
    @Scheduled(cron = "${scheduler.transfer-processor.debit-processing-cron}")
    @SchedulerLock(
        name = "processDebitProcessingTransfers",
        lockAtMostFor = "${scheduler.transfer-processor.debit-processing-lock-at-most-seconds}s",
        lockAtLeastFor = "${scheduler.transfer-processor.debit-processing-lock-at-least-seconds}s"
    )
    public void processDebitProcessingTransfers() {
        log.info("Starting scheduled job to retry DEBIT_PROCESSING transfers (lock acquired)");

        try {
            int processedCount = transferFacade.processDebitProcessingTransfers(
                    schedulerProperties.getDebitProcessingDelaySeconds(),
                    schedulerProperties.getDebitProcessingBatchSize()
            );

            log.info("Scheduled job completed: retried {} DEBIT_PROCESSING transfers", processedCount);
        } catch (Exception e) {
            log.error("DEBIT_PROCESSING retry scheduled job failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 定時重試停滯在 CREDIT_PROCESSING 的轉帳
     *
     * 觸發時機：根據 scheduler.transfer-processor.credit-processing-cron 配置
     *
     * 職責：
     * - 查詢 CREDIT_PROCESSING 狀態超過指定時間的轉帳
     * - 重新發送加帳 MQ 事件
     * - 利用 BalanceService 冪等性確保不重複處理
     */
    @Scheduled(cron = "${scheduler.transfer-processor.credit-processing-cron}")
    @SchedulerLock(
        name = "processCreditProcessingTransfers",
        lockAtMostFor = "${scheduler.transfer-processor.credit-processing-lock-at-most-seconds}s",
        lockAtLeastFor = "${scheduler.transfer-processor.credit-processing-lock-at-least-seconds}s"
    )
    public void processCreditProcessingTransfers() {
        log.info("Starting scheduled job to retry CREDIT_PROCESSING transfers (lock acquired)");

        try {
            int processedCount = transferFacade.processCreditProcessingTransfers(
                    schedulerProperties.getCreditProcessingDelaySeconds(),
                    schedulerProperties.getCreditProcessingBatchSize()
            );

            log.info("Scheduled job completed: retried {} CREDIT_PROCESSING transfers", processedCount);
        } catch (Exception e) {
            log.error("CREDIT_PROCESSING retry scheduled job failed: {}", e.getMessage(), e);
        }
    }
}
