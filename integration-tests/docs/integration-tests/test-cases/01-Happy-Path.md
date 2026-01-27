# Happy Path Scenarios (快樂路徑情境)

本文件包含 3 個核心功能的正常流程測試案例。

---

## IT-001: 完整轉帳流程

**Test ID**: IT-001
**Category**: Happy Path
**Test Name**: `completeTransferFlow_TwoUsers_BalancesUpdatedCorrectly`

### Description
驗證完整的轉帳流程，從建立使用者到執行轉帳，確保餘額正確更新且快取正確失效。

### Preconditions
- 資料庫清空 (user_balances, transfers, balance_changes 皆為空)
- Redis 快取清空

### Test Steps

#### 1. 建立使用者 A
**Request**:
```http
POST /users
Content-Type: application/json

{
  "userId": "userA",
  "initialBalance": 1000.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED
Content-Type: application/json

{
  "userId": "userA",
  "balance": 1000.00,
  "createdAt": "2026-01-23T10:00:00",
  "version": 0
}
```

**Verify DB**:
```sql
SELECT user_id, balance, version FROM user_balances WHERE user_id = 'userA';
-- 預期: user_id='userA', balance=1000.00, version=0
```

#### 2. 建立使用者 B
**Request**:
```http
POST /users
Content-Type: application/json

{
  "userId": "userB",
  "initialBalance": 500.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "userId": "userB",
  "balance": 500.00,
  "createdAt": "2026-01-23T10:00:01",
  "version": 0
}
```

**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances WHERE user_id = 'userB';
-- 預期: user_id='userB', balance=500.00
```

#### 3. 查詢使用者 A 餘額 (建立快取)
**Request**:
```http
GET /users/userA/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userA",
  "balance": 1000.00
}
```

**Verify Redis**:
```bash
# 檢查快取 key 是否存在
redis-cli -p 6380 EXISTS "balance::userA"
# 預期: (integer) 1

# 檢查快取值
redis-cli -p 6380 GET "balance::userA"
# 預期: "1000.00" 或 JSON 格式的快取物件
```

#### 4. 執行轉帳
**Request**:
```http
POST /transfers
Content-Type: application/json

{
  "fromUserId": "userA",
  "toUserId": "userB",
  "amount": 300.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userA",
  "toUserId": "userB",
  "amount": 300.00,
  "status": "PENDING",  // 或 "COMPLETED"，取決於實作
  "createdAt": "2026-01-23T10:00:02"
}
```

**Verify DB**:
```sql
SELECT id, from_user_id, to_user_id, amount, status
FROM transfers WHERE id = 1;
-- 預期: 轉帳記錄已建立
```

#### 5. 查詢使用者 A 更新後的餘額
**Request**:
```http
GET /users/userA/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userA",
  "balance": 700.00
}
```

**Verify DB**:
```sql
SELECT balance FROM user_balances WHERE user_id = 'userA';
-- 預期: balance=700.00
```

**Verify Redis** (快取失效):
```bash
# 方案 1: 快取已清除
redis-cli -p 6380 EXISTS "balance::userA"
# 預期: (integer) 0

# 方案 2: 快取已更新為新值
redis-cli -p 6380 GET "balance::userA"
# 預期: "700.00"
```

#### 6. 查詢使用者 B 更新後的餘額
**Request**:
```http
GET /users/userB/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userB",
  "balance": 800.00
}
```

**Verify DB**:
```sql
SELECT balance FROM user_balances WHERE user_id = 'userB';
-- 預期: balance=800.00
```

### Expected Result
- ✅ 兩個使用者成功建立
- ✅ 轉帳成功執行
- ✅ userA 餘額從 1000.00 更新為 700.00
- ✅ userB 餘額從 500.00 更新為 800.00
- ✅ 轉帳記錄正確儲存在資料庫
- ✅ 快取在餘額更新後失效或更新

### Verification SQL
```sql
-- 驗證最終餘額
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userA', 'userB')
ORDER BY user_id;
-- 預期:
-- userA | 700.00
-- userB | 800.00

-- 驗證轉帳記錄
SELECT from_user_id, to_user_id, amount, status
FROM transfers WHERE id = 1;
-- 預期: userA -> userB, 300.00, COMPLETED 或 PENDING
```

---

## IT-002: 取消 PENDING 轉帳

**Test ID**: IT-002
**Category**: Happy Path
**Test Name**: `cancelTransfer_PendingStatus_StatusChangedToCancelled`

### Description
驗證在時間窗口內可以成功取消 PENDING 狀態的轉帳，且使用者餘額不受影響。

### Preconditions
- 資料庫清空
- 兩個使用者已建立:
  - userC: balance = 2000.00
  - userD: balance = 1000.00

### Test Steps

#### 1. 建立轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userC",
  "toUserId": "userD",
  "amount": 500.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userC",
  "toUserId": "userD",
  "amount": 500.00,
  "status": "PENDING",
  "createdAt": "2026-01-23T10:00:00"
}
```

**Verify DB**:
```sql
SELECT id, status, created_at, cancelled_at
FROM transfers WHERE id = 1;
-- 預期: status='PENDING', cancelled_at=NULL
```

#### 2. 取消轉帳 (在 10 分鐘內)
**Request**:
```http
POST /transfers/1/cancel
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "id": 1,
  "fromUserId": "userC",
  "toUserId": "userD",
  "amount": 500.00,
  "status": "CANCELLED",
  "cancelledAt": "2026-01-23T10:05:00"
}
```

**Verify DB**:
```sql
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='CANCELLED', cancelled_at IS NOT NULL
```

#### 3. 驗證使用者餘額未變動
**Request**:
```http
GET /users/userC/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userC",
  "balance": 2000.00
}
```

**Request**:
```http
GET /users/userD/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userD",
  "balance": 1000.00
}
```

**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userC', 'userD');
-- 預期:
-- userC | 2000.00
-- userD | 1000.00
```

### Expected Result
- ✅ 轉帳狀態成功更新為 CANCELLED
- ✅ cancelledAt 時間戳已設定
- ✅ 使用者餘額保持不變
- ✅ 無 balance_changes 記錄建立

### Verification SQL
```sql
-- 驗證轉帳已取消
SELECT id, status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='CANCELLED', cancelled_at IS NOT NULL

-- 驗證無餘額變動記錄
SELECT COUNT(*) FROM balance_changes WHERE external_id = 1;
-- 預期: 0
```

---

## IT-003: 分頁查詢轉帳歷史

**Test ID**: IT-003
**Category**: Happy Path
**Test Name**: `getTransferHistory_MultipleTransfers_ReturnsPaginatedResults`

### Description
驗證轉帳歷史查詢的分頁功能，確保正確返回分頁資料和 pagination metadata。

### Preconditions
- 資料庫清空
- 三個使用者已建立: userE, userF, userG
- 25 筆已完成的轉帳記錄 (userE 涉及所有轉帳)

### Test Steps

#### 1. 準備測試資料
**Setup** (通過 API 或直接插入 DB):
```sql
-- 建立 25 筆轉帳記錄，userE 作為 fromUserId 或 toUserId
-- 確保 created_at 時間不同，用於測試排序
```

#### 2. 查詢第一頁 (page=0, size=10)
**Request**:
```http
GET /transfers?userId=userE&page=0&size=10
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "transfers": [
    {
      "id": 25,
      "fromUserId": "userE",
      "toUserId": "userF",
      "amount": 100.00,
      "status": "COMPLETED",
      "createdAt": "2026-01-23T10:24:00"
    },
    // ... 9 more transfers (最新的 10 筆)
  ],
  "pagination": {
    "currentPage": 0,
    "pageSize": 10,
    "totalElements": 25,
    "totalPages": 3,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

**Verify**:
- ✅ 返回 10 筆轉帳
- ✅ 按 createdAt DESC 排序 (最新的在前)
- ✅ 所有轉帳都涉及 userE (fromUserId 或 toUserId)
- ✅ pagination metadata 正確

#### 3. 查詢第二頁 (page=1, size=10)
**Request**:
```http
GET /transfers?userId=userE&page=1&size=10
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "transfers": [
    // 接下來的 10 筆轉帳
  ],
  "pagination": {
    "currentPage": 1,
    "pageSize": 10,
    "totalElements": 25,
    "totalPages": 3,
    "hasNext": true,
    "hasPrevious": true
  }
}
```

**Verify**:
- ✅ 返回 10 筆不同的轉帳 (與第一頁無重複)
- ✅ hasNext=true, hasPrevious=true

#### 4. 查詢第三頁 (page=2, size=10)
**Request**:
```http
GET /transfers?userId=userE&page=2&size=10
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "transfers": [
    // 最後 5 筆轉帳
  ],
  "pagination": {
    "currentPage": 2,
    "pageSize": 10,
    "totalElements": 25,
    "totalPages": 3,
    "hasNext": false,
    "hasPrevious": true
  }
}
```

**Verify**:
- ✅ 返回 5 筆轉帳 (剩餘的)
- ✅ hasNext=false, hasPrevious=true

### Expected Result
- ✅ 分頁功能正常運作
- ✅ pagination metadata 準確
- ✅ 跨頁面無重複或遺漏記錄
- ✅ 排序一致 (createdAt DESC)
- ✅ 只返回與指定使用者相關的轉帳

### Verification SQL
```sql
-- 驗證總數
SELECT COUNT(*) FROM transfers
WHERE from_user_id = 'userE' OR to_user_id = 'userE';
-- 預期: 25

-- 驗證排序
SELECT id, from_user_id, to_user_id, created_at
FROM transfers
WHERE from_user_id = 'userE' OR to_user_id = 'userE'
ORDER BY created_at DESC
LIMIT 10;
-- 應與 API 回應第一頁的順序一致
```

---

**導航**:
- [返回總覽](../00-README.md)
- [下一個: Business Logic](02-Business-Logic.md)
