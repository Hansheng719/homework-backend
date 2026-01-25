package com.example.demo.event;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 餘額操作完成事件
 *
 * 用途：
 * 當餘額變更操作完成（成功或失敗）後，發布此事件進行：
 * 1. 快取失效（僅成功時）
 * 2. 發送 MQ 結果通知（成功和失敗都發送）
 * 事件監聽器會在事務提交後執行。
 *
 * 事件流程：
 * 1. BalanceService 完成操作（在事務中）
 * 2. 事務提交
 * 3. @TransactionalEventListener 接收事件（在事務提交後）
 * 4. 清除快取（僅成功時）
 * 5. 發送 RocketMQ 結果消息
 *
 * 這確保了快取清除和 MQ 消息只在資料庫成功提交後執行，避免資料不一致問題。
 */
@Getter
public class BalanceOperationCompletedEvent extends ApplicationEvent {

    private final BalanceChange balanceChange;
    private final boolean success;
    private final String failureReason;

    public BalanceOperationCompletedEvent(Object source, BalanceChange balanceChange) {
        super(source);
        this.balanceChange = balanceChange;
        this.success = balanceChange.getStatus() == BalanceChangeStatus.COMPLETED;
        this.failureReason = balanceChange.getFailureReason();
    }
}
