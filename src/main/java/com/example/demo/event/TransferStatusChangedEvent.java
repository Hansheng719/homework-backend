package com.example.demo.event;

import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import com.example.demo.service.impl.TransferStateTransitionValidator;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * 轉帳狀態變更事件
 *
 * 用途：
 * 當轉帳狀態變更後，發布此事件通知其他組件處理後續邏輯
 *
 * 事件流程：
 * 1. Service 方法內更新狀態並發布事件（在事務中）
 * 2. 事務提交
 * 3. @TransactionalEventListener 接收事件（在事務提交後）
 * 4. 執行後續處理（例如：發送 MQ 消息、更新統計等）
 *
 * 這確保了事件監聽器只在資料庫成功更新後執行，避免數據不一致。
 */
@Getter
public class TransferStatusChangedEvent extends ApplicationEvent {

    /**
     * 轉帳 ID
     */
    private final Long transferId;

    /**
     * 轉出方使用者 ID
     */
    private final String fromUserId;

    /**
     * 收款方使用者 ID
     */
    private final String toUserId;

    /**
     * 轉帳金額
     */
    private final BigDecimal amount;

    /**
     * 舊狀態（轉換前）
     * null 表示新建轉帳
     */
    private final TransferStatus oldStatus;

    /**
     * 新狀態（轉換後）
     */
    private final TransferStatus newStatus;

    /**
     * 失敗原因（可選，僅在失敗時有值）
     */
    private final String failureReason;

    /**
     * 建立轉帳狀態變更事件
     *
     * @param source 事件來源（通常是 TransferService）
     * @param transfer 轉帳實體（包含最新狀態）
     * @param oldStatus 舊狀態（null 表示新建）
     */
    public TransferStatusChangedEvent(Object source, Transfer transfer, TransferStatus oldStatus) {
        super(source);
        this.transferId = transfer.getId();
        this.fromUserId = transfer.getFromUserId();
        this.toUserId = transfer.getToUserId();
        this.amount = transfer.getAmount();
        this.oldStatus = oldStatus;
        this.newStatus = transfer.getStatus();
        this.failureReason = transfer.getFailureReason();
    }

    /**
     * 判斷是否為失敗事件
     *
     * @return true if status is DEBIT_FAILED or CANCELLED
     */
    public boolean isFailed() {
        return newStatus == TransferStatus.DEBIT_FAILED
            || newStatus == TransferStatus.CANCELLED;
    }

    /**
     * 判斷是否為完成事件
     *
     * @return true if status is COMPLETED
     */
    public boolean isCompleted() {
        return newStatus == TransferStatus.COMPLETED;
    }

    /**
     * 判斷是否為終態
     *
     * @return true if new status is a terminal state
     */
    public boolean isTerminalState() {
        return TransferStateTransitionValidator.isTerminalState(newStatus);
    }
}
