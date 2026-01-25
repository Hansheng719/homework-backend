package com.example.demo.event;

import com.example.demo.mq.producer.BalanceChangeProducer;
import com.example.demo.service.BalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 餘額操作完成監聽器
 *
 * 監聽 BalanceOperationCompletedEvent 事件，在事務提交後執行：
 * 1. 快取失效（僅成功的操作）
 * 2. 發送 RocketMQ 結果通知（成功和失敗都發送）
 *
 * 關鍵設定：
 * - @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
 * - 確保只有在事務成功提交後才執行
 * - 若事務 rollback，此監聽器不會執行
 *
 * 快取失效邏輯：
 * - 只有 event.isSuccess() == true 時才清除快取
 * - 失敗的操作不會清除快取（因為餘額未變更）
 * - 快取清除在 MQ 發送之前執行，確保快取在通知外部系統前已更新
 *
 * 關注點分離：
 * - BalanceService: 業務邏輯、資料庫操作
 * - 此監聽器: 快取管理、MQ 整合、結果通知
 * - Consumer: 消息消費、服務調用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceOperationCompletedListener {

    private final BalanceChangeProducer producer;
    private final BalanceService balanceService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBalanceOperationCompleted(BalanceOperationCompletedEvent event) {
        // 1. 快取失效（僅成功的操作）
        try {
            if (event.isSuccess()) {
                balanceService.evictBalanceCache(event.getBalanceChange().getUserId());
                log.debug("Evicted balance cache for userId: {}",
                    event.getBalanceChange().getUserId());
            }
        } catch (Exception e) {
            // 快取清除失敗不應影響 MQ 通知
            log.error("Failed to evict balance cache: userId={}, externalId={}",
                event.getBalanceChange().getUserId(),
                event.getBalanceChange().getExternalId(), e);
        }

        // 2. 發送 MQ 結果通知（成功和失敗都發送）
        try {
            producer.sendResult(
                event.getBalanceChange(),
                event.isSuccess(),
                event.getFailureReason()
            );
            log.info("Sent balance change result: externalId={}, type={}, success={}",
                event.getBalanceChange().getExternalId(),
                event.getBalanceChange().getType(),
                event.isSuccess());
        } catch (Exception e) {
            // MQ 發送失敗是基礎設施問題
            // 事務已提交，無法回滾
            // 記錄錯誤供監控和告警使用
            log.error("Failed to send balance change result to MQ: externalId={}, type={}, success={}",
                event.getBalanceChange().getExternalId(),
                event.getBalanceChange().getType(),
                event.isSuccess(), e);
        }
    }
}
