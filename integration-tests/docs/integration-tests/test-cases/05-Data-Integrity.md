# Data Integrity (資料完整性)

本文件包含 3 個資料完整性驗證的測試案例。

---

## IT-015: 並發轉帳餘額一致性

**Test ID**: IT-015
**Category**: Data Integrity
**Test Name**: `concurrentTransfers_SameUser_BalanceConsistent`

### Description
驗證當同一使用者同時發起多筆轉帳時，最終餘額計算正確，無資料競爭問題。

### Preconditions
- 資料庫清空
- 三個使用者已建立:
  - userV: balance = 1000.00
  - userW: balance = 500.00
  - userX: balance = 500.00

### Test Steps

#### 1. 同時發起兩筆從 userV 出發的轉帳
**Setup**:
```
在測試程式碼中使用多執行緒或非同步請求，同時發送兩個轉帳請求
```

**Request 1**:
```http
POST /transfers

{
  "fromUserId": "userV",
  "toUserId": "userW",
  "amount": 300.00
}
```

**Request 2** (同時發送):
```http
POST /transfers

{
  "fromUserId": "userV",
  "toUserId": "userX",
  "amount": 400.00
}
```

#### 2. 驗證兩筆轉帳都成功或其中一筆失敗
**預期場景 A - 兩筆都成功**:
- 兩個請求都返回 201 CREATED
- userV 餘額 = 1000 - 300 - 400 = 300.00

**預期場景 B - 第二筆失敗 (餘額不足)**:
- 第一個請求返回 201 CREATED
- 第二個請求返回 400 BAD_REQUEST (餘額不足)
- userV 餘額 = 1000 - 300 = 700.00

#### 3. 驗證最終餘額一致
**Verify DB**:
```sql
SELECT user_id, balance FROM user_balances WHERE user_id = 'userV';
-- 預期: balance 等於 1000 減去所有成功轉帳的金額
```

#### 4. 驗證轉帳記錄數量
**Verify DB**:
```sql
SELECT COUNT(*), SUM(amount) FROM transfers
WHERE from_user_id = 'userV' AND status IN ('COMPLETED', 'PENDING');
-- 預期: 總金額 = 1000 - userV 最終餘額
```

### Expected Result
- ✅ 無論並發執行順序如何，最終餘額一致
- ✅ 不會出現負數餘額
- ✅ 轉帳總額 = 初始餘額 - 最終餘額
- ✅ 無資料競爭或遺失更新

### Verification SQL
```sql
-- 驗證餘額一致性
SELECT
    (SELECT SUM(initial_balance) FROM user_balances) AS total_before,
    (SELECT SUM(balance) FROM user_balances) AS total_after;
-- 預期: total_before = total_after (系統總餘額守恆)

-- 驗證 userV 餘額
SELECT balance FROM user_balances WHERE user_id = 'userV';
-- 預期: balance >= 0 且與轉帳記錄一致
```

---

## IT-016: 多筆轉帳餘額計算準確

**Test ID**: IT-016
**Category**: Data Integrity
**Test Name**: `multipleTransfers_SequentialExecution_BalancesAccurate`

### Description
驗證多筆順序轉帳後，所有使用者的餘額計算準確無誤。

### Preconditions
- 資料庫清空
- 三個使用者已建立:
  - userY: balance = 5000.00
  - userZ: balance = 1000.00
  - userAA: balance = 2000.00

### Test Steps

#### 1. 執行第一筆轉帳: userY → userZ (500.00)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userY",
  "toUserId": "userZ",
  "amount": 500.00
}
```

**驗證中間狀態**:
```sql
SELECT user_id, balance FROM user_balances WHERE user_id IN ('userY', 'userZ');
-- 預期: userY=4500.00, userZ=1500.00
```

#### 2. 執行第二筆轉帳: userZ → userAA (200.00)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userZ",
  "toUserId": "userAA",
  "amount": 200.00
}
```

**驗證中間狀態**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userZ', 'userAA');
-- 預期: userZ=1300.00, userAA=2200.00
```

#### 3. 執行第三筆轉帳: userY → userAA (1000.00)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userY",
  "toUserId": "userAA",
  "amount": 1000.00
}
```

**驗證中間狀態**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userY', 'userAA');
-- 預期: userY=3500.00, userAA=3200.00
```

#### 4. 執行第四筆轉帳: userAA → userY (300.00)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAA",
  "toUserId": "userY",
  "amount": 300.00
}
```

**驗證最終狀態**:
```sql
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userY', 'userZ', 'userAA')
ORDER BY user_id;
-- 預期: userY=3800.00, userZ=1300.00, userAA=2900.00
```

### Expected Result
- ✅ 所有轉帳成功執行
- ✅ 最終餘額: userY=3800.00, userZ=1300.00, userAA=2900.00
- ✅ 每筆轉帳後餘額正確更新
- ✅ 無計算錯誤或遺失更新

### Verification SQL
```sql
-- 驗證最終餘額
SELECT user_id, balance FROM user_balances
WHERE user_id IN ('userY', 'userZ', 'userAA')
ORDER BY user_id;
-- 預期: userAA=2900.00, userY=3800.00, userZ=1300.00

-- 驗證轉帳記錄數量
SELECT COUNT(*) FROM transfers WHERE status IN ('COMPLETED', 'PENDING');
-- 預期: 4

-- 驗證系統總餘額守恆
SELECT SUM(balance) FROM user_balances
WHERE user_id IN ('userY', 'userZ', 'userAA');
-- 預期: 8000.00 (5000 + 1000 + 2000)
```

---

## IT-017: 系統總餘額守恆

**Test ID**: IT-017
**Category**: Data Integrity
**Test Name**: `systemWide_MultipleTransfers_TotalBalanceConserved`

### Description
驗證在任何轉帳操作後，系統總餘額保持守恆（轉帳只是餘額在使用者間轉移，不增加或減少總額）。

### Preconditions
- 資料庫清空
- 五個使用者已建立，總餘額 = 10000.00
  - user1: 3000.00
  - user2: 2500.00
  - user3: 2000.00
  - user4: 1500.00
  - user5: 1000.00

### Test Steps

#### 1. 記錄初始總餘額
**Verify DB**:
```sql
SELECT SUM(balance) AS total_balance FROM user_balances;
-- 預期: 10000.00
```

#### 2. 執行多筆隨機轉帳 (至少 10 筆)
**示例轉帳**:
- user1 → user2: 500.00
- user2 → user3: 300.00
- user3 → user4: 200.00
- user4 → user5: 100.00
- user5 → user1: 50.00
- user1 → user3: 800.00
- ... 繼續執行更多轉帳

#### 3. 驗證每筆轉帳後總餘額不變
**在每筆轉帳後執行**:
```sql
SELECT SUM(balance) AS total_balance FROM user_balances;
-- 預期: 始終為 10000.00
```

#### 4. 驗證最終總餘額
**Verify DB**:
```sql
SELECT SUM(balance) AS total_balance FROM user_balances;
-- 預期: 10000.00 (與初始總額相同)
```

#### 5. 驗證各使用者餘額總和
**Verify DB**:
```sql
SELECT
    user_id,
    balance,
    (SELECT SUM(balance) FROM user_balances) AS system_total
FROM user_balances
ORDER BY user_id;
-- 預期: system_total 對所有記錄都是 10000.00
```

### Expected Result
- ✅ 初始總餘額 = 10000.00
- ✅ 執行任何轉帳後總餘額 = 10000.00
- ✅ 最終總餘額 = 10000.00
- ✅ 餘額守恆定律成立

### Verification SQL
```sql
-- 驗證總餘額守恆
SELECT SUM(balance) AS total_balance FROM user_balances;
-- 預期: 10000.00

-- 驗證無負餘額
SELECT COUNT(*) FROM user_balances WHERE balance < 0;
-- 預期: 0

-- 驗證所有轉帳記錄的總額平衡
SELECT
    (SELECT SUM(amount) FROM transfers WHERE status = 'COMPLETED') AS total_transferred,
    (SELECT COUNT(*) FROM transfers WHERE status = 'COMPLETED') AS completed_count;
-- 總轉帳金額應合理，無異常大額
```

---

**導航**:
- [上一個: State Transition](04-State-Transition.md)
- [下一個: Cache Integration](06-Cache-Integration.md)
- [返回總覽](../00-README.md)
