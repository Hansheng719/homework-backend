package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * UserBalance 實體（使用者餘額表）
 *
 * 功能：記錄每個使用者的帳戶餘額
 * 並發控制：使用樂觀鎖（@Version）防止並發更新衝突
 * 實作 Persistable 介面以正確處理 assigned ID 的新增/更新判斷
 */
@Entity
@Table(name = "user_balances")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBalance implements Persistable<String> {

    /**
     * 使用者 ID（主鍵）
     */
    @Id
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /**
     * 帳戶餘額（DECIMAL(15,2)）
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    /**
     * 建立時間（不可更新）
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 樂觀鎖版本號
     * 每次更新會自動遞增，用於防止並發更新衝突
     */
    @Version
    private Long version;

    /**
     * 實作 Persistable 介面 - 回傳實體 ID
     */
    @Override
    public String getId() {
        return userId;
    }

    /**
     * 實作 Persistable 介面 - 判斷是否為新實體
     * 使用 version 欄位判斷：null 表示未持久化的新實體
     */
    @Override
    @Transient
    public boolean isNew() {
        return version == null;
    }
}
