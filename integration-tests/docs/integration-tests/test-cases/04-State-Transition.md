# State Transition (狀態轉換)

本文件包含 3 個轉帳狀態轉換的測試案例。

---

## IT-012: 轉帳狀態 PENDING → COMPLETED

**Test ID**: IT-012
**Category**: State Transition
**Test Name**: `transferLifecycle_PendingToCompleted_StateUpdatedCorrectly`

### Description
驗證轉帳從 PENDING 狀態正確轉換到 COMPLETED 狀態，並更新 completedAt 時間戳。

### Preconditions
- 資料庫清空
- 兩個使用者已建立:
  - userP: balance = 1000.00
  - userQ: balance = 500.00

### Test Steps

#### 1. 建立轉帳 (初始狀態 PENDING)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userP",
  "toUserId": "userQ",
  "amount": 200.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userP",
  "toUserId": "userQ",
  "amount": 200.00,
  "status": "PENDING",
  "createdAt": "2026-01-23T10:00:00"
}
```

**Verify DB - 初始狀態**:
```sql
SELECT status, completed_at FROM transfers WHERE id = 1;
-- 預期: status='PENDING', completed_at=NULL
```

#### 2. 等待系統處理轉帳 (系統會自動處理)
**說明**:
- 在實際整合測試中，可能需要等待背景處理完成
- 或者通過查詢 API 確認狀態變更

#### 3. 查詢轉帳狀態 (應已完成)
**假設系統已處理完成，查詢最新狀態**:

**Verify DB - 最終狀態**:
```sql
SELECT status, completed_at FROM transfers WHERE id = 1;
-- 預期: status='COMPLETED', completed_at IS NOT NULL
```

#### 4. 驗證餘額已更新
**Request**:
```http
GET /users/userP/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userP",
  "balance": 800.00
}
```

**Request**:
```http
GET /users/userQ/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userQ",
  "balance": 700.00
}
```

### Expected Result
- ✅ 轉帳初始狀態為 PENDING
- ✅ 轉帳最終狀態為 COMPLETED
- ✅ completedAt 時間戳已設定
- ✅ 使用者餘額正確更新
- ✅ 無錯誤訊息

### Verification SQL
```sql
-- 驗證轉帳狀態轉換
SELECT id, status, created_at, completed_at
FROM transfers WHERE id = 1;
-- 預期: status='COMPLETED', completed_at > created_at

-- 驗證餘額更新
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userP', 'userQ');
-- 預期: userP=800.00, userQ=700.00
```

---

## IT-013: 轉帳狀態 PENDING → CANCELLED

**Test ID**: IT-013
**Category**: State Transition
**Test Name**: `transferLifecycle_PendingToCancelled_StateUpdatedCorrectly`

### Description
驗證轉帳從 PENDING 狀態正確轉換到 CANCELLED 狀態，並更新 cancelledAt 時間戳。

### Preconditions
- 資料庫清空
- 兩個使用者已建立:
  - userR: balance = 1500.00
  - userS: balance = 800.00

### Test Steps

#### 1. 建立轉帳 (初始狀態 PENDING)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userR",
  "toUserId": "userS",
  "amount": 300.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userR",
  "toUserId": "userS",
  "amount": 300.00,
  "status": "PENDING",
  "createdAt": "2026-01-23T10:00:00"
}
```

**Verify DB - 初始狀態**:
```sql
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='PENDING', cancelled_at=NULL
```

#### 2. 取消轉帳
**Request**:
```http
POST /transfers/1/cancel
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "id": 1,
  "fromUserId": "userR",
  "toUserId": "userS",
  "amount": 300.00,
  "status": "CANCELLED",
  "cancelledAt": "2026-01-23T10:05:00"
}
```

**Verify DB - 最終狀態**:
```sql
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='CANCELLED', cancelled_at IS NOT NULL
```

#### 3. 驗證餘額未變動
**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userR', 'userS');
-- 預期: userR=1500.00, userS=800.00 (保持不變)
```

### Expected Result
- ✅ 轉帳從 PENDING 轉換為 CANCELLED
- ✅ cancelledAt 時間戳已設定
- ✅ 使用者餘額保持不變
- ✅ 無 balance_changes 記錄

### Verification SQL
```sql
-- 驗證狀態轉換
SELECT status, cancelled_at FROM transfers WHERE id = 1;
-- 預期: status='CANCELLED', cancelled_at IS NOT NULL

-- 驗證無餘額變動
SELECT user_id, balance FROM user_balances WHERE user_id IN ('userR', 'userS');
-- 預期: userR=1500.00, userS=800.00

-- 驗證無餘額變動記錄
SELECT COUNT(*) FROM balance_changes WHERE external_id = 1;
-- 預期: 0
```

---

## IT-014: 轉帳失敗狀態 (餘額不足)

**Test ID**: IT-014
**Category**: State Transition
**Test Name**: `transferLifecycle_InsufficientBalance_StatusFailed`

### Description
驗證當餘額不足時，轉帳應標記為失敗狀態 (如果系統設計包含此狀態)。

**註**: 此測試案例假設系統有 FAILED 或類似的失敗狀態。如果系統直接在建立時拒絕，可能不適用。

### Preconditions
- 資料庫清空
- 兩個使用者已建立:
  - userT: balance = 50.00
  - userU: balance = 1000.00

### Test Steps

#### 1. 嘗試建立超過餘額的轉帳
**Request**:
```http
POST /transfers

{
  "fromUserId": "userT",
  "toUserId": "userU",
  "amount": 100.00
}
```

**預期場景 A - 立即拒絕**:
```http
HTTP/1.1 400 BAD_REQUEST

{
  "status": 400,
  "error": "Insufficient Balance",
  "message": "Insufficient balance: current=50.00, required=100.00"
}
```

**驗證**: 無轉帳記錄建立

**預期場景 B - 建立但標記為失敗** (如果系統採用此設計):
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userT",
  "toUserId": "userU",
  "amount": 100.00,
  "status": "FAILED",  // 或 "DEBIT_FAILED"
  "failureReason": "Insufficient balance: current=50.00, required=100.00"
}
```

**Verify DB**:
```sql
SELECT status, failure_reason FROM transfers WHERE id = 1;
-- 預期: status='FAILED', failure_reason 包含 "Insufficient balance"
```

#### 2. 驗證餘額未變動
**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances WHERE user_id IN ('userT', 'userU');
-- 預期: userT=50.00, userU=1000.00 (保持不變)
```

### Expected Result
- ✅ 轉帳被拒絕或標記為失敗
- ✅ 失敗原因記錄清晰
- ✅ 使用者餘額保持不變
- ✅ 無實際的餘額變動記錄

### Verification SQL
```sql
-- 場景 A: 驗證無轉帳記錄
SELECT COUNT(*) FROM transfers WHERE from_user_id = 'userT';
-- 預期: 0

-- 場景 B: 驗證失敗狀態
SELECT status, failure_reason FROM transfers WHERE from_user_id = 'userT';
-- 預期: status='FAILED', failure_reason IS NOT NULL

-- 驗證餘額未變動
SELECT balance FROM user_balances WHERE user_id = 'userT';
-- 預期: 50.00
```

---

**導航**:
- [上一個: Error Handling](03-Error-Handling.md)
- [下一個: Data Integrity](05-Data-Integrity.md)
- [返回總覽](../00-README.md)
