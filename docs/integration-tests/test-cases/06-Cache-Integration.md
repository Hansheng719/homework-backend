# Cache Integration (快取整合)

本文件包含 3 個 Redis 快取行為驗證的測試案例。

---

## IT-018: 快取命中與未命中

**Test ID**: IT-018
**Category**: Cache Integration
**Test Name**: `balanceQuery_CacheHitMiss_BehavesCorrectly`

### Description
驗證第一次查詢建立快取（cache miss），第二次查詢使用快取（cache hit）。

### Preconditions
- 資料庫清空
- Redis 清空
- 一個使用者已建立: userAB (balance = 1000.00)

### Test Steps

#### 1. 第一次查詢餘額 (Cache Miss)
**Request**:
```http
GET /users/userAB/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userAB",
  "balance": 1000.00
}
```

**Verify Redis - 快取已建立**:
```bash
# 檢查 key 是否存在
redis-cli -p 6380 EXISTS "balance::userAB"
# 預期: (integer) 1

# 檢查快取值
redis-cli -p 6380 GET "balance::userAB"
# 預期: "1000.00" 或 JSON 格式的快取物件

# 檢查 TTL
redis-cli -p 6380 TTL "balance::userAB"
# 預期: 約 300 秒 (5 分鐘)
```

**Verify DB - 資料來自資料庫**:
```
第一次查詢應該執行 SQL 查詢
```

#### 2. 第二次查詢餘額 (Cache Hit)
**Request**:
```http
GET /users/userAB/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userAB",
  "balance": 1000.00
}
```

**Verify Redis - 快取仍存在**:
```bash
redis-cli -p 6380 EXISTS "balance::userAB"
# 預期: (integer) 1
```

**Verify DB**:
```
第二次查詢應該使用快取，不執行 SQL 查詢
（可通過日誌或監控確認）
```

### Expected Result
- ✅ 第一次查詢建立快取
- ✅ 快取 key 格式正確: `balance::{userId}`
- ✅ 快取 TTL 設定正確 (約 300 秒)
- ✅ 第二次查詢命中快取
- ✅ 快取值與資料庫一致

### Verification Redis
```bash
# 檢查快取存在
redis-cli -p 6380 EXISTS "balance::userAB"
# 預期: 1

# 檢查快取內容
redis-cli -p 6380 GET "balance::userAB"
# 預期: 包含 balance=1000.00 的資料

# 檢查 TTL
redis-cli -p 6380 TTL "balance::userAB"
# 預期: > 0 且 <= 300
```

---

## IT-019: 餘額更新後快取失效

**Test ID**: IT-019
**Category**: Cache Integration
**Test Name**: `balanceUpdate_AfterTransfer_CacheEvicted`

### Description
驗證當使用者餘額更新（例如執行轉帳）後，相關的快取被正確清除。

### Preconditions
- 資料庫清空
- Redis 清空
- 兩個使用者已建立:
  - userAC: balance = 1000.00
  - userAD: balance = 500.00

### Test Steps

#### 1. 查詢 userAC 餘額 (建立快取)
**Request**:
```http
GET /users/userAC/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userAC",
  "balance": 1000.00
}
```

**Verify Redis**:
```bash
redis-cli -p 6380 EXISTS "balance::userAC"
# 預期: (integer) 1
```

#### 2. 執行轉帳 (餘額變動)
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAC",
  "toUserId": "userAD",
  "amount": 300.00
}
```

**Expected Response**:
```http
HTTP/1.1 201 CREATED

{
  "id": 1,
  "fromUserId": "userAC",
  "toUserId": "userAD",
  "amount": 300.00,
  "status": "PENDING"
}
```

#### 3. 驗證快取已清除
**Verify Redis - userAC 快取已清除**:
```bash
redis-cli -p 6380 EXISTS "balance::userAC"
# 預期: (integer) 0
```

**Verify Redis - userAD 快取已清除** (如果之前有建立):
```bash
redis-cli -p 6380 EXISTS "balance::userAD"
# 預期: (integer) 0
```

#### 4. 再次查詢 userAC 餘額 (重新建立快取)
**Request**:
```http
GET /users/userAC/balance
```

**Expected Response**:
```http
HTTP/1.1 200 OK

{
  "userId": "userAC",
  "balance": 700.00
}
```

**Verify Redis - 快取重新建立**:
```bash
redis-cli -p 6380 EXISTS "balance::userAC"
# 預期: (integer) 1

redis-cli -p 6380 GET "balance::userAC"
# 預期: 包含 balance=700.00 的資料 (更新後的值)
```

### Expected Result
- ✅ 轉帳前快取存在
- ✅ 轉帳後相關使用者的快取被清除
- ✅ 再次查詢時重新建立快取
- ✅ 新快取包含更新後的餘額

### Verification Redis
```bash
# 驗證轉帳前
redis-cli -p 6380 GET "balance::userAC"
# 應包含舊值 1000.00

# 驗證轉帳後（立即檢查）
redis-cli -p 6380 EXISTS "balance::userAC"
# 預期: 0 (已清除)

# 驗證再次查詢後
redis-cli -p 6380 GET "balance::userAC"
# 應包含新值 700.00
```

---

## IT-020: 多使用者快取隔離

**Test ID**: IT-020
**Category**: Cache Integration
**Test Name**: `multiUserCache_IndependentKeys_ProperIsolation`

### Description
驗證不同使用者的快取是獨立的，一個使用者的快取操作不影響其他使用者。

### Preconditions
- 資料庫清空
- Redis 清空
- 三個使用者已建立:
  - userAE: balance = 1000.00
  - userAF: balance = 2000.00
  - userAG: balance = 3000.00

### Test Steps

#### 1. 查詢三個使用者的餘額 (建立三個快取)
**Request 1**:
```http
GET /users/userAE/balance
```

**Request 2**:
```http
GET /users/userAF/balance
```

**Request 3**:
```http
GET /users/userAG/balance
```

**Verify Redis - 三個快取都存在**:
```bash
redis-cli -p 6380 KEYS "balance::*"
# 預期: 包含三個 key
# 1) "balance::userAE"
# 2) "balance::userAF"
# 3) "balance::userAG"
```

#### 2. 只更新 userAF 的餘額
**Request**:
```http
POST /transfers

{
  "fromUserId": "userAF",
  "toUserId": "userAE",
  "amount": 100.00
}
```

#### 3. 驗證只有相關使用者的快取被清除
**Verify Redis**:
```bash
# userAE 快取已清除 (接收方)
redis-cli -p 6380 EXISTS "balance::userAE"
# 預期: (integer) 0

# userAF 快取已清除 (發送方)
redis-cli -p 6380 EXISTS "balance::userAF"
# 預期: (integer) 0

# userAG 快取仍然存在 (不相關的使用者)
redis-cli -p 6380 EXISTS "balance::userAG"
# 預期: (integer) 1

# 驗證 userAG 快取值未變動
redis-cli -p 6380 GET "balance::userAG"
# 預期: 仍為 3000.00
```

#### 4. 再次查詢所有使用者餘額
**Request 1**:
```http
GET /users/userAE/balance
```

**Expected**: 1100.00 (快取未命中，從 DB 讀取)

**Request 2**:
```http
GET /users/userAF/balance
```

**Expected**: 1900.00 (快取未命中，從 DB 讀取)

**Request 3**:
```http
GET /users/userAG/balance
```

**Expected**: 3000.00 (快取命中)

### Expected Result
- ✅ 每個使用者有獨立的快取 key
- ✅ 快取 key 格式: `balance::{userId}`
- ✅ 更新時只清除相關使用者的快取
- ✅ 不相關使用者的快取保持不變
- ✅ 快取隔離正常運作

### Verification Redis
```bash
# 檢查所有快取 key
redis-cli -p 6380 KEYS "balance::*"

# 驗證各 key 的存在狀態
redis-cli -p 6380 EXISTS "balance::userAE"
redis-cli -p 6380 EXISTS "balance::userAF"
redis-cli -p 6380 EXISTS "balance::userAG"

# 驗證各 key 的值
redis-cli -p 6380 GET "balance::userAE"
redis-cli -p 6380 GET "balance::userAF"
redis-cli -p 6380 GET "balance::userAG"
```

---

**導航**:
- [上一個: Data Integrity](05-Data-Integrity.md)
- [下一個: Edge Cases](07-Edge-Cases.md)
- [返回總覽](../00-README.md)
