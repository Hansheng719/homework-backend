package com.example.demo.service.impl;

import com.example.demo.entity.TransferStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 轉帳狀態轉換規則管理器
 *
 * 設計原則：
 * - 單一真相來源（Single Source of Truth）
 * - 集中管理所有合法的狀態轉換
 * - 使用 EnumMap 提供 O(1) 查找性能
 * - 不可變設計，線程安全
 *
 * 狀態轉換規則：
 * PENDING → DEBIT_PROCESSING, DEBIT_FAILED, CANCELLED
 * DEBIT_PROCESSING → CREDIT_PROCESSING, DEBIT_FAILED
 * CREDIT_PROCESSING → COMPLETED
 * 終態（COMPLETED, DEBIT_FAILED, CANCELLED）→ 不允許轉換
 */
public class TransferStateTransitionValidator {

    /**
     * 狀態轉換映射表
     * Key: 當前狀態
     * Value: 允許轉換到的狀態集合
     */
    private static final Map<TransferStatus, Set<TransferStatus>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(TransferStatus.class);

        // PENDING 可轉換為: PROCESSING, CANCELLED
        ALLOWED_TRANSITIONS.put(TransferStatus.PENDING,
            EnumSet.of(TransferStatus.DEBIT_PROCESSING, TransferStatus.DEBIT_FAILED, TransferStatus.CANCELLED));

        // DEBIT_PROCESSING 可轉換為: DEBIT_COMPLETED, DEBIT_FAILED
        ALLOWED_TRANSITIONS.put(TransferStatus.DEBIT_PROCESSING,
            EnumSet.of(TransferStatus.CREDIT_PROCESSING, TransferStatus.DEBIT_FAILED));

        // CREDIT_PROCESSING 可轉換為: COMPLETED
        ALLOWED_TRANSITIONS.put(TransferStatus.CREDIT_PROCESSING,
            EnumSet.of(TransferStatus.COMPLETED));

        // 終態不允許轉換
        ALLOWED_TRANSITIONS.put(TransferStatus.COMPLETED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED_TRANSITIONS.put(TransferStatus.DEBIT_FAILED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED_TRANSITIONS.put(TransferStatus.CANCELLED, EnumSet.noneOf(TransferStatus.class));
    }

    /**
     * 驗證狀態轉換是否合法
     *
     * @param currentStatus 當前狀態
     * @param targetStatus 目標狀態
     * @return true if transition is allowed, false otherwise
     */
    public static boolean isTransitionAllowed(TransferStatus currentStatus, TransferStatus targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        Set<TransferStatus> allowedTargets = ALLOWED_TRANSITIONS.get(currentStatus);
        return allowedTargets != null && allowedTargets.contains(targetStatus);
    }

    /**
     * 獲取當前狀態允許轉換的目標狀態集合
     *
     * @param currentStatus 當前狀態
     * @return 允許的目標狀態集合（不可變）
     */
    public static Set<TransferStatus> getAllowedTransitions(TransferStatus currentStatus) {
        if (currentStatus == null) {
            return EnumSet.noneOf(TransferStatus.class);
        }
        Set<TransferStatus> transitions = ALLOWED_TRANSITIONS.get(currentStatus);
        return transitions != null ? transitions : EnumSet.noneOf(TransferStatus.class);
    }

    /**
     * 判斷狀態是否為終態
     *
     * @param status 狀態
     * @return true if terminal state
     */
    public static boolean isTerminalState(TransferStatus status) {
        if (status == null) {
            return false;
        }
        return status == TransferStatus.COMPLETED
            || status == TransferStatus.DEBIT_FAILED
            || status == TransferStatus.CANCELLED;
    }
}
