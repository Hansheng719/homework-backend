# Edge Cases (邊界情況)

本文件包含 7 個邊界條件和特殊情況的測試案例。

---

## IT-021: 空結果集分頁

**Test ID**: IT-021
**Category**: Edge Cases
**Test Name**: `getTransferHistory_NoTransfers_ReturnsEmptyPage`

### Description
驗證當使用者無任何轉帳記錄時，分頁查詢返回空結果集且 pagination metadata 正確。

### Preconditions
- 資料庫清空
- 一個使用者已建立: userAH (balance = 1000.00)
- 無任何轉帳記錄

### Test Steps

#### 1. 查詢轉帳歷史
**Request**:
```http
GET /transfers?userId=userAH&page=0&size=20
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "transfers": [],
  "pagination": {
    "currentPage": 0,
    "pageSize": 20,
    "totalElements": 0,
    "totalPages": 0,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

### Expected Result
- ✅ API 返回 200 OK (不是 404)
- ✅ transfers 陣列為空
- ✅ totalElements = 0
- ✅ totalPages = 0
- ✅ hasNext = false, hasPrevious = false

### Verification SQL
```sql
-- 確認無轉帳記錄
SELECT COUNT(*) FROM transfers WHERE from_user_id = 'userAH' OR to_user_id = 'userAH';
-- 預期: 0
```

---

## IT-023: 邊界分頁大小

**Test ID**: IT-023
**Category**: Edge Cases
**Test Name**: `getTransferHistory_BoundaryPageSizes_HandlesCorrectly`

### Description
驗證最小和最大分頁大小的處理，以及超出範圍時的錯誤處理。

### Preconditions
- 資料庫清空
- 一個使用者已建立: userAI
- 50 筆轉帳記錄

### Test Steps

#### 1. 測試最小分頁大小 (size=1)
**Request**:
```http
GET /transfers?userId=userAI&page=0&size=1
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "transfers": [
    // 1 筆轉帳
  ],
  "pagination": {
    "currentPage": 0,
    "pageSize": 1,
    "totalElements": 50,
    "totalPages": 50,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

**Verify**:
- ✅ 返回 1 筆記錄
- ✅ totalPages = 50

#### 2. 測試最大分頁大小 (size=100)
**Request**:
```http
GET /transfers?userId=userAI&page=0&size=100
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "transfers": [
    // 50 筆轉帳 (所有記錄)
  ],
  "pagination": {
    "currentPage": 0,
    "pageSize": 100,
    "totalElements": 50,
    "totalPages": 1,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

**Verify**:
- ✅ 返回所有 50 筆記錄
- ✅ totalPages = 1

#### 3. 測試超出最小範圍 (size=0)
**Request**:
```http
GET /transfers?userId=userAI&page=0&size=0
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "size: must be greater than or equal to 1"
}
```

#### 4. 測試超出最大範圍 (size=101)
**Request**:
```http
GET /transfers?userId=userAI&page=0&size=101
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "size: must be less than or equal to 100"
}
```

### Expected Result
- ✅ size=1 和 size=100 正常運作
- ✅ size=0 返回 400 錯誤
- ✅ size=101 返回 400 錯誤
- ✅ 邊界值驗證正確

---

## IT-024: 恰好 10 分鐘取消邊界

**Test ID**: IT-024
**Category**: Edge Cases
**Test Name**: `cancelTransfer_ExactlyTenMinutes_BoundaryBehavior`

### Description
驗證恰好在 10 分鐘邊界的取消行為（測試時間計算的精確度）。

### Preconditions
- 資料庫清空
- 兩個使用者已建立
- 一筆轉帳建立於恰好 10 分鐘前

### Test Steps

#### 1. 建立轉帳 (created_at = 10 分鐘前)
**Setup**:
```sql
INSERT INTO transfers (id, from_user_id, to_user_id, amount, status, created_at)
VALUES (1, 'userAJ', 'userAK', 500.00, 'PENDING', NOW() - INTERVAL 10 MINUTE);
```

#### 2. 嘗試取消轉帳
**Request**:
```http
POST /transfers/1/cancel
```

**預期行為**:
系統應明確定義邊界行為：
- **選項 A**: 允許取消（<= 10 分鐘）
- **選項 B**: 拒絕取消（> 10 分鐘）

#### 3. 驗證一致性
**如果允許取消**:
```http
HTTP/1.1 200 OK

{
  "id": 1,
  "status": "CANCELLED",
  "cancelledAt": "..."
}
```

**如果拒絕取消**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Invalid Transfer State",
  "message": "Cannot cancel transfer: cancellation window expired"
}
```

### Expected Result
- ✅ 邊界行為一致（總是允許或總是拒絕）
- ✅ 時間比較邏輯明確
- ✅ 文件說明邊界定義

### Verification SQL
```sql
-- 驗證時間差
SELECT
    id,
    status,
    TIMESTAMPDIFF(SECOND, created_at, NOW()) AS seconds_ago
FROM transfers WHERE id = 1;
-- 應恰好約 600 秒（10 分鐘）
```

---

## IT-025: 負數金額驗證

**Test ID**: IT-025
**Category**: Edge Cases
**Test Name**: `createTransfer_NegativeAmount_RejectedByValidation`

### Description
驗證負數金額被正確拒絕。

### Preconditions
- 資料庫清空
- 兩個使用者已建立

### Test Steps

#### 1. 嘗試建立負數金額轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAL",
  "toUserId": "userAM",
  "amount": -100.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "amount: must be greater than 0"
}
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息說明金額必須為正數
- ✅ 無轉帳記錄建立

### Verification SQL
```sql
-- 確認無負數金額轉帳
SELECT COUNT(*) FROM transfers WHERE amount < 0;
-- 預期: 0
```

---

## IT-026: 零金額轉帳

**Test ID**: IT-026
**Category**: Edge Cases
**Test Name**: `createTransfer_ZeroAmount_RejectedByValidation`

### Description
驗證零金額轉帳被正確拒絕。

### Preconditions
- 資料庫清空
- 兩個使用者已建立

### Test Steps

#### 1. 嘗試建立零金額轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAN",
  "toUserId": "userAO",
  "amount": 0.00
}
```

**Expected Response**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "amount: must be greater than 0"
}
```

### Expected Result
- ✅ API 返回 400 BAD_REQUEST
- ✅ 錯誤訊息說明金額必須大於 0
- ✅ 無轉帳記錄建立

### Verification SQL
```sql
-- 確認無零金額轉帳
SELECT COUNT(*) FROM transfers WHERE amount = 0;
-- 預期: 0
```

---

## IT-027: 極大金額轉帳

**Test ID**: IT-027
**Category**: Edge Cases
**Test Name**: `createTransfer_VeryLargeAmount_HandledCorrectly`

### Description
驗證極大金額（接近 DECIMAL(15,2) 上限）的轉帳處理正確。

### Preconditions
- 資料庫清空
- 兩個使用者已建立:
  - userAP: balance = 9999999999999.99 (接近最大值)
  - userAQ: balance = 0.00

### Test Steps

#### 1. 建立極大金額使用者
**Request**:
```http
POST /users

{
  "userId": "userAP",
  "initialBalance": 9999999999999.99
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "userId": "userAP",
  "balance": 9999999999999.99
}
```

#### 2. 執行極大金額轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAP",
  "toUserId": "userAQ",
  "amount": 5000000000000.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userAP",
  "toUserId": "userAQ",
  "amount": 5000000000000.00,
  "status": "PENDING"
}
```

#### 3. 驗證餘額精確度
**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userAP', 'userAQ');
-- 預期:
-- userAP | 4999999999999.99
-- userAQ | 5000000000000.00
```

#### 4. 驗證小數精度
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAP",
  "toUserId": "userAQ",
  "amount": 0.01
}
```

**Verify DB**:
```sql
SELECT balance FROM user_balances WHERE user_id = 'userAP';
-- 預期: 4999999999999.98 (精確到小數點後 2 位)
```

### Expected Result
- ✅ 極大金額正確處理
- ✅ 無溢位錯誤
- ✅ 小數精度保持在 2 位
- ✅ BigDecimal 運算正確

### Verification SQL
```sql
-- 驗證餘額精度
SELECT user_id, balance, LENGTH(balance) AS digits
FROM user_balances
WHERE user_id IN ('userAP', 'userAQ');

-- 驗證轉帳金額
SELECT amount FROM transfers WHERE id IN (1, 2);
-- 確認金額正確儲存
```

---

## IT-028: 特殊字元 userId

**Test ID**: IT-028
**Category**: Edge Cases
**Test Name**: `createUser_SpecialCharacters_HandledCorrectly`

### Description
驗證 userId 包含特殊字元時的處理（根據系統允許的字元範圍）。

### Preconditions
- 資料庫清空

### Test Steps

#### 1. 測試有效的特殊字元 (例如: 底線、連字號)
**Request**:
```http
POST /users

{
  "userId": "user_test-001",
  "initialBalance": 1000.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "userId": "user_test-001",
  "balance": 1000.00
}
```

#### 2. 測試無效的特殊字元 (例如: 空格、特殊符號)
**Request**:
```http
POST /users

{
  "userId": "user@test#001",
  "initialBalance": 1000.00
}
```

**Expected Response** (取決於系統規則):
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Validation Failed",
  "message": "userId: must match pattern [a-zA-Z0-9_-]+"
}
```

或允許建立（如果系統允許）。

#### 3. 測試 SQL 注入防護
**Request**:
```http
POST /users

{
  "userId": "user'; DROP TABLE users;--",
  "initialBalance": 1000.00
}
```

**Expected Response**:
應被拒絕或安全處理，不執行 SQL 注入。

### Expected Result
- ✅ 有效字元正確處理
- ✅ 無效字元被拒絕（如有規則）
- ✅ SQL 注入防護生效
- ✅ 特殊字元不造成系統錯誤

### Verification SQL
```sql
-- 驗證特殊字元正確儲存
SELECT user_id FROM user_balances WHERE user_id LIKE '%test%';

-- 確認資料表未被破壞
SHOW TABLES;
```

---

**導航**:
- [上一個: Cache Integration](06-Cache-Integration.md)
- [返回總覽](../00-README.md)
