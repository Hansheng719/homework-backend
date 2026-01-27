# Business Logic Validation (業務邏輯驗證)

本文件包含 5 個業務規則驗證的測試案例。

---

## IT-004: 餘額不足時轉帳失敗

**Test ID**: IT-004
**Category**: Business Logic
**Test Name**: `createTransfer_InsufficientBalance_ReturnsError`

### Description
驗證當使用者餘額不足時，轉帳請求應該被拒絕，並返回適當的錯誤訊息。

### Preconditions
- 資料庫清空
- 兩個使用者已建立:
  - userH: balance = 100.00
  - userI: balance = 500.00

### Test Steps

#### 1. 嘗試建立超過餘額的轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userH",
  "toUserId": "userI",
  "amount": 200.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Insufficient Balance",
  "message": "Insufficient balance: current=100.00, required=200.00",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/transfers"
}
```

#### 2. 驗證使用者餘額未變動
**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userH', 'userI');
-- 預期:
-- userH | 100.00
-- userI | 500.00
```

#### 3. 驗證無轉帳記錄建立
**Verify DB**:
```sql
SELECT COUNT(*) FROM transfers
WHERE from_user_id = 'userH' AND to_user_id = 'userI';
-- 預期: 0
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息清晰說明餘額不足
- ✅ 使用者餘額保持不變
- ✅ 無轉帳記錄建立
- ✅ 無 balance_changes 記錄建立

### Verification SQL
```sql
-- 驗證餘額未變動
SELECT user_id, balance FROM user_balances WHERE user_id = 'userH';
-- 預期: balance = 100.00

-- 驗證無轉帳記錄
SELECT COUNT(*) FROM transfers WHERE from_user_id = 'userH';
-- 預期: 0

-- 驗證無餘額變動記錄
SELECT COUNT(*) FROM balance_changes WHERE user_id = 'userH';
-- 預期: 0
```

---

## IT-005: 重複 userId 建立使用者失敗

**Test ID**: IT-005
**Category**: Business Logic
**Test Name**: `createUser_DuplicateUserId_ReturnsConflict409`

### Description
驗證唯一性約束，當嘗試建立已存在的 userId 時，應返回 409 CONFLICT 錯誤。

### Preconditions
- 資料庫清空

### Test Steps

#### 1. 建立第一個使用者
**Request**:
```http
POST /users

{
  "userId": "duplicateUser",
  "initialBalance": 1000.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "userId": "duplicateUser",
  "balance": 1000.00,
  "createdAt": "2026-01-23T10:00:00",
  "version": 0
}
```

#### 2. 嘗試建立相同 userId 的使用者
**Request**:
```http
POST /users

{
  "userId": "duplicateUser",
  "initialBalance": 500.00
}
```

**Expected Response**:
```http
HTTP/1.1 409 CONFLICT

{
  "status": 409,
  "error": "User Already Exists",
  "message": "User with userId 'duplicateUser' already exists",
  "timestamp": "2026-01-23T10:00:01",
  "path": "/users"
}
```

#### 3. 驗證只有一筆使用者記錄
**Verify DB**:
```sql
SELECT COUNT(*), user_id, balance
FROM user_balances
WHERE user_id = 'duplicateUser'
GROUP BY user_id, balance;
-- 預期: count=1, user_id='duplicateUser', balance=1000.00
```

### Expected Result
- ✅ 第一次建立成功返回 201
- ✅ 第二次建立失敗返回 409
- ✅ 資料庫只有一筆記錄
- ✅ 原始使用者的餘額未受影響

### Verification SQL
```sql
-- 驗證唯一記錄
SELECT COUNT(*) FROM user_balances WHERE user_id = 'duplicateUser';
-- 預期: 1

-- 驗證餘額未被覆蓋
SELECT balance FROM user_balances WHERE user_id = 'duplicateUser';
-- 預期: 1000.00 (不是 500.00)
```

---

## IT-006: 無法取消非 PENDING 狀態轉帳

**Test ID**: IT-006
**Category**: Business Logic
**Test Name**: `cancelTransfer_CompletedStatus_ReturnsError400`

### Description
驗證只有 PENDING 狀態的轉帳可以取消，其他狀態的轉帳無法取消。

### Preconditions
- 資料庫清空
- 兩個使用者已建立
- 一筆 COMPLETED 狀態的轉帳已存在

### Test Steps

#### 1. 建立並完成轉帳 (直接設定為 COMPLETED)
**Setup**:
```sql
-- 假設轉帳已完成，直接插入 COMPLETED 狀態
INSERT INTO transfers (id, from_user_id, to_user_id, amount, status, created_at, completed_at)
VALUES (1, 'userK', 'userL', 300.00, 'COMPLETED', NOW(), NOW());
```

或通過完整流程建立並完成轉帳。

#### 2. 嘗試取消已完成的轉帳
**Request**:
```http
POST /transfers/1/cancel
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Invalid Transfer State",
  "message": "Cannot cancel transfer: current status=COMPLETED. Only PENDING transfers can be cancelled.",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/transfers/1/cancel"
}
```

#### 3. 驗證轉帳狀態未變動
**Verify DB**:
```sql
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='COMPLETED', cancelled_at=NULL
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息說明無法取消非 PENDING 狀態轉帳
- ✅ 轉帳狀態保持 COMPLETED
- ✅ cancelledAt 仍為 NULL

### Verification SQL
```sql
-- 驗證狀態未變動
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='COMPLETED', cancelled_at IS NULL
```

---

## IT-007: 超過 10 分鐘無法取消轉帳

**Test ID**: IT-007
**Category**: Business Logic
**Test Name**: `cancelTransfer_AfterTimeWindow_ReturnsError400`

### Description
驗證 10 分鐘取消窗口的業務規則，超過 10 分鐘的 PENDING 轉帳無法取消。

### Preconditions
- 資料庫清空
- 兩個使用者已建立
- 一筆 PENDING 轉帳建立於 11 分鐘前

### Test Steps

#### 1. 建立轉帳 (created_at 設為 11 分鐘前)
**Setup**:
```sql
-- 插入 11 分鐘前建立的轉帳
INSERT INTO transfers (id, from_user_id, to_user_id, amount, status, created_at)
VALUES (1, 'userM', 'userN', 500.00, 'PENDING', NOW() - INTERVAL 11 MINUTE);
```

#### 2. 嘗試取消超過時間窗口的轉帳
**Request**:
```http
POST /transfers/1/cancel
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Invalid Transfer State",
  "message": "Cannot cancel transfer: cancellation window expired (>10 minutes)",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/transfers/1/cancel"
}
```

#### 3. 驗證轉帳狀態未變動
**Verify DB**:
```sql
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='PENDING', cancelled_at=NULL
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息說明超過取消時間窗口
- ✅ 轉帳狀態仍為 PENDING
- ✅ cancelledAt 仍為 NULL

### Verification SQL
```sql
-- 驗證狀態未變動
SELECT status, cancelled_at,
       TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS minutes_ago
FROM transfers WHERE id = 1;
-- 預期: status='PENDING', cancelled_at=NULL, minutes_ago > 10
```

---

## IT-008: 自我轉帳被拒絕

**Test ID**: IT-008
**Category**: Business Logic
**Test Name**: `createTransfer_SelfTransfer_ReturnsError400`

### Description
驗證業務規則：不允許使用者轉帳給自己。

### Preconditions
- 資料庫清空
- 一個使用者已建立: userO (balance = 2000.00)

### Test Steps

#### 1. 嘗試建立自我轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userO",
  "toUserId": "userO",
  "amount": 100.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "Cannot transfer to the same user",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/transfers"
}
```

#### 2. 驗證使用者餘額未變動
**Verify DB**:
```sql
SELECT balance FROM user_balances WHERE user_id = 'userO';
-- 預期: balance = 2000.00
```

#### 3. 驗證無轉帳記錄建立
**Verify DB**:
```sql
SELECT COUNT(*) FROM transfers
WHERE from_user_id = 'userO' AND to_user_id = 'userO';
-- 預期: 0
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息說明不允許自我轉帳
- ✅ 使用者餘額未變動
- ✅ 無轉帳記錄建立

### Verification SQL
```sql
-- 驗證餘額未變動
SELECT balance FROM user_balances WHERE user_id = 'userO';
-- 預期: 2000.00

-- 驗證無自我轉帳記錄
SELECT COUNT(*) FROM transfers
WHERE from_user_id = to_user_id;
-- 預期: 0
```

---

**導航**:
- [上一個: Happy Path](01-Happy-Path.md)
- [下一個: Error Handling](03-Error-Handling.md)
- [返回總覽](../00-README.md)
