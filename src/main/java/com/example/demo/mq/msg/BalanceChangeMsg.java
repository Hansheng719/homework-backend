package com.example.demo.mq.msg;

import com.example.demo.entity.BalanceChangeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 餘額變更事件
 *
 * 用於在 RocketMQ 中傳遞餘額變更請求（扣款或加帳）
 *
 * Topic: balance-change-requests
 * 消息流向：TransferService → BalanceService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceChangeMsg implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 外部 ID（Transfer ID）
     * 用於冪等性控制，對應 BalanceChange.externalId
     */
    private Long externalId;

    /**
     * 變更類型
     * - TRANSFER_OUT: 轉出（扣款）
     * - TRANSFER_IN: 轉入（加帳）
     */
    private BalanceChangeType type;

    /**
     * 用戶 ID
     * 要執行餘額變更的用戶
     */
    private String userId;

    /**
     * 金額
     * - 扣款時為負數
     * - 加帳時為正數
     */
    private BigDecimal amount;

    /**
     * 關聯的轉帳 ID
     * 用於追蹤和審計
     */
    private Long relatedId;

    /**
     * 時間戳（Unix epoch milliseconds）
     */
    private long timestamp;
}
