package com.example.demo.mq.constants;

/**
 * RocketMQ 常數定義
 *
 * 集中管理 Topic、Consumer Group 等常數
 * 避免在 Producer 和 Consumer 中重複定義
 */
public final class MQConstants {

    private MQConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== Topics ====================

    /**
     * 餘額變更請求 Topic
     * 用途：發送扣款/加帳請求
     */
    public static final String TOPIC_BALANCE_CHANGE_REQUESTS = "balance-change-requests";

    /**
     * 餘額變更結果 Topic
     * 用途：發送處理結果（成功/失敗）
     */
    public static final String TOPIC_BALANCE_CHANGE_RESULTS = "balance-change-results";

    // ==================== Consumer Groups ====================

    /**
     * Balance Service Consumer Group
     * 消費 balance-change-requests，執行扣款/加帳操作
     */
    public static final String GROUP_BALANCE_SERVICE = "balance-service-consumer-group";

    /**
     * Transfer Service Consumer Group
     * 消費 balance-change-results，更新轉帳狀態
     */
    public static final String GROUP_TRANSFER_SERVICE = "transfer-service-consumer-group";

    // ==================== Consumer Configuration ====================

    /**
     * Consumer 最小執行緒數
     */
    public static final int CONSUMER_THREAD_MIN = 10;

    /**
     * Consumer 最大執行緒數
     */
    public static final int CONSUMER_THREAD_MAX = 20;

    /**
     * 最大重試次數
     */
    public static final int MAX_RECONSUME_TIMES = 16;
}
