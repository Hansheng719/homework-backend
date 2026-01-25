package com.example.demo.event;

import com.example.demo.mq.producer.BalanceChangeProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 轉帳狀態變更事件監聽器
 *
 * 監聽 TransferStatusChangedEvent 事件，在事務提交後執行後續處理
 *
 * 使用場景：
 * - 發送 MQ 消息通知下游系統
 * - 更新統計數據
 * - 發送通知給使用者
 * - 記錄審計日誌
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferStatusChangedListener {

    private final BalanceChangeProducer eventProducer;

    /**
     * 在事務提交後處理轉帳狀態變更事件
     *
     * 執行時機：
     * - 只在事務成功提交後執行（TransactionPhase.AFTER_COMMIT）
     * - 如果事務 rollback，此方法不會被呼叫
     *
     * @param event 轉帳狀態變更事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTransferStatusChanged(TransferStatusChangedEvent event) {
        log.info("Transfer status changed: id={}, {} -> {}, terminal={}",
            event.getTransferId(),
            event.getOldStatus(),
            event.getNewStatus(),
            event.isTerminalState());

        // 範例：根據不同的狀態轉換執行不同的後續處理
        switch (event.getNewStatus()) {
            case DEBIT_PROCESSING:
                log.debug("Transfer is now DEBIT_PROCESSING, sending balance change: id={}", event.getTransferId());
                eventProducer.sendDebitRequest(
                    event.getTransferId(),
                    event.getFromUserId(),
                    event.getAmount()
                );
                break;

            case CREDIT_PROCESSING:
                log.debug("Transfer is now CREDIT_PROCESSING, sending balance change: id={}", event.getTransferId());
                eventProducer.sendCreditRequest(
                    event.getTransferId(),
                    event.getToUserId(),
                    event.getAmount()
                );
                break;

            default:
                // 其他狀態的通用處理
                log.debug("Transfer status updated: id={}, status={}",
                    event.getTransferId(), event.getNewStatus());
                break;
        }
    }
}
