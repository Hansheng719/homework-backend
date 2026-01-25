# Error Handling (錯誤處理)

本文件包含 3 個錯誤處理的測試案例。

---

## IT-009: 使用者不存在返回 404

**Test ID**: IT-009
**Category**: Error Handling
**Test Name**: `getBalance_UserNotFound_Returns404`

### Description
驗證當查詢不存在的使用者時，API 正確返回 404 NOT_FOUND。

### Preconditions
- 資料庫清空 (無任何使用者)

### Test Steps

#### 1. 查詢不存在的使用者餘額
**Request**:
```http
GET /users/nonExistentUser/balance
```

**Expected Response**:
```http
HTTP/1.1 404 NOT_FOUND

{
  "status": 404,
  "error": "User Not Found",
  "message": "User not found: nonExistentUser",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/users/nonExistentUser/balance"
}
```

#### 2. 驗證資料庫無此使用者
**Verify DB**:
```sql
SELECT COUNT(*) FROM user_balances WHERE user_id = 'nonExistentUser';
-- 預期: 0
```

### Expected Result
- ✅ API 返回 404 NOT_FOUND
- ✅ 錯誤訊息清晰說明使用者不存在
- ✅ 包含具體的 userId 資訊

### Verification SQL
```sql
-- 確認使用者不存在
SELECT COUNT(*) FROM user_balances WHERE user_id = 'nonExistentUser';
-- 預期: 0
```

---

## IT-010: 無效請求格式返回 400

**Test ID**: IT-010
**Category**: Error Handling
**Test Name**: `createUser_InvalidRequest_Returns400`

### Description
驗證當請求格式不符合規格時，API 返回 400 BAD_REQUEST 並提供清晰的驗證錯誤訊息。

### Preconditions
- 資料庫清空

### Test Steps

#### 1. 發送無效請求 - userId 為空
**Request**:
```http
POST /users

{
  "userId": "",
  "initialBalance": 1000.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "userId: must not be blank",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/users"
}
```

#### 2. 發送無效請求 - initialBalance 為負數
**Request**:
```http
POST /users

{
  "userId": "testUser",
  "initialBalance": -100.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "initialBalance: must be greater than or equal to 0",
  "timestamp": "2026-01-23T10:00:01",
  "path": "/users"
}
```

#### 3. 發送無效請求 - amount 為 0
**Request**:
```http
POST /transfers

{
  "fromUserId": "userA",
  "toUserId": "userB",
  "amount": 0.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "amount: must be greater than 0",
  "timestamp": "2026-01-23T10:00:02",
  "path": "/transfers"
}
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息具體說明哪個欄位驗證失敗
- ✅ 錯誤訊息包含驗證規則
- ✅ 無資料寫入資料庫

### Verification SQL
```sql
-- 驗證無資料被建立
SELECT COUNT(*) FROM user_balances WHERE user_id = '' OR user_id = 'testUser';
-- 預期: 0

SELECT COUNT(*) FROM transfers;
-- 預期: 0
```

---

## IT-011: 轉帳不存在返回 404

**Test ID**: IT-011
**Category**: Error Handling
**Test Name**: `cancelTransfer_TransferNotFound_Returns404`

### Description
驗證當嘗試取消不存在的轉帳時，API 返回 404 NOT_FOUND。

### Preconditions
- 資料庫清空 (無任何轉帳記錄)

### Test Steps

#### 1. 嘗試取消不存在的轉帳
**Request**:
```http
POST /transfers/999/cancel
```

**Expected Response**:
```http
HTTP/1.1 404 NOT_FOUND

{
  "status": 404,
  "error": "Transfer Not Found",
  "message": "Transfer not found: 999",
  "timestamp": "2026-01-23T10:00:00",
  "path": "/transfers/999/cancel"
}
```

#### 2. 驗證資料庫無此轉帳
**Verify DB**:
```sql
SELECT COUNT(*) FROM transfers WHERE id = 999;
-- 預期: 0
```

### Expected Result
- ✅ API 返回 404 NOT_FOUND
- ✅ 錯誤訊息清晰說明轉帳不存在
- ✅ 包含具體的 transferId 資訊

### Verification SQL
```sql
-- 確認轉帳不存在
SELECT COUNT(*) FROM transfers WHERE id = 999;
-- 預期: 0
```

---

**導航**:
- [上一個: Business Logic](02-Business-Logic.md)
- [下一個: State Transition](04-State-Transition.md)
- [返回總覽](../00-README.md)
