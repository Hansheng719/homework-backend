package com.example.demo.schedule;

import com.example.demo.config.SchedulerProperties;
import com.example.demo.facade.TransferFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * 觸發時機：根據 scheduler.transfer-processor.cron 配置
     */
    @Scheduled(cron = "${scheduler.transfer-processor.cron}")
    public void processPendingTransfers() {
        log.info("Starting scheduled job to process PENDING transfers");

        try {
            int processedCount = transferFacade.processPendingTransfers(
                    schedulerProperties.getDelaySeconds(),
                    schedulerProperties.getBatchSize()
            );

            log.info("Scheduled job completed: processed {} transfers", processedCount);
        } catch (Exception e) {
            log.error("Scheduled job failed: {}", e.getMessage(), e);
        }
    }
}
