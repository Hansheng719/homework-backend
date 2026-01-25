package com.example.demo.mq.msg;

import com.example.demo.entity.BalanceChangeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 餘額變更結果事件
 *
 * 用於在 RocketMQ 中傳遞餘額變更操作的結果
 *
 * Topic: balance-change-results
 * 消息流向：BalanceService → TransferService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceChangeResultMsg implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 外部 ID（Transfer ID）
     * 對應原始請求的 externalId
     */
    private Long externalId;

    /**
     * 變更類型
     * - TRANSFER_OUT: 轉出（扣款）
     * - TRANSFER_IN: 轉入（加帳）
     */
    private BalanceChangeType type;

    /**
     * 操作是否成功
     */
    private Boolean success;

    /**
     * 用戶 ID
     */
    private String userId;

    /**
     * 變更前餘額（操作成功時）
     */
    private BigDecimal oldBalance;

    /**
     * 變更後餘額（操作成功時）
     */
    private BigDecimal newBalance;

    /**
     * 失敗原因（操作失敗時）
     */
    private String failureReason;

    /**
     * 時間戳（Unix epoch milliseconds）
     */
    private long timestamp;
}
