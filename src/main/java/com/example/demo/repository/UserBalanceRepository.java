package com.example.demo.repository;

import com.example.demo.entity.UserBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * UserBalanceRepository
 *
 * 功能：提供 UserBalance 實體的資料存取方法
 *
 * 自訂查詢方法：
 * 1. findByIdForUpdate - 悲觀鎖定查詢（用於並發控制）
 */
@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, String> {

    /**
     * 使用悲觀鎖定查詢使用者餘額（FOR UPDATE）
     *
     * 用途：防止並發更新，確保同一時間只有一個事務可以修改該使用者的餘額
     * 使用場景：轉帳扣款/入帳時，鎖定使用者餘額記錄
     *
     * @param userId 使用者 ID
     * @return Optional<UserBalance> 使用者餘額記錄（帶鎖）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ub FROM UserBalance ub WHERE ub.userId = :userId")
    Optional<UserBalance> findByIdForUpdate(@Param("userId") String userId);
}