package com.example.demo.facade.dto;

import com.example.demo.entity.TransferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferItemDto {
    private Long id;
    private String fromUserId;
    private String toUserId;
    private BigDecimal amount;
    private TransferStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private String failureReason;
}
