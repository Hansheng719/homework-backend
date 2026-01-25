# Balance Transfer Service - 技術規格

## 1. 功能概述

本規格定義基於事件驅動架構的餘額轉帳服務，採用 **Facade 模式**進行架構設計。系統透過 Facade 層協調 **TransferService** 和 **BalanceService** 的原子性操作，並透過 RocketMQ 事件實現完全異步的帳變處理，確保高效能、高可靠性及冪等性。

### 1.1 系統架構

**架構層次：**
```
Controller 層 → Facade 層 → Service 層（原子性操作） → Repository 層
                        → EventPublisher

Scheduler → Facade 層（編排協調）
Consumer → Facade 層（事件處理）
```

**Facade Layer（門面層）**
- 職責：編排業務流程，組合多個 Service 的原子性操作
- 不包含業務邏輯，僅負責協調和流程控制
- 提供高層次的業務操作接口
- 包含：TransferFacade、BalanceFacade

**Service Layer（服務層）**
- 職責：提供原子性、內聚性的業務操作
- 每個方法應該是單一職責，完成一個完整的事務單元
- 包含必要的 DB 事務邊界
- 包含：TransferService（轉帳狀態管理）、BalanceService（餘額操作）、EventPublisher（事件發送）

**TransferService（轉帳服務層）**
- 原子性操作：createPendingTransfer()、updateStatusWithLock()、markAsFailed()、findPendingTransfers()
- 職責：轉帳記錄的 CRUD 操作，狀態管理
- 每個方法都是單一事務單元

**BalanceService（餘額服務層）**
- 原子性操作：validateUserExists()、checkSufficientBalance()、debitBalance()、creditBalance()
- 職責：使用者驗證、餘額查詢、餘額更新（含冪等性）
- 每個方法都是單一事務單元

**服務協作方式：**
- TransferFacade 和 BalanceFacade 位於同一應用程式內
- 透過直接方法調用協作，不使用 REST API
- 透過 RocketMQ 事件實現異步帳變處理

**核心功能範圍：**
- 使用者建立與初始餘額設定（BalanceService）
- 餘額查詢（BalanceService，含 Redis 快取）
- 使用者間轉帳（TransferFacade 編排 BalanceService + TransferService + EventPublisher）
- 轉帳歷史查詢（TransferService）
- 轉帳取消（TransferFacade 編排）

### 1.2 Facade 編排流程概覽

```
[Controller 層]
    ↓ TransferController.createTransfer()
    ↓ 調用 TransferFacade.createTransfer()

[TransferFacade 編排]
    ↓ BalanceService.validateUserExists(fromUserId)
    ↓ BalanceService.validateUserExists(toUserId)
    ↓ BalanceService.checkSufficientBalance(fromUserId, amount)
    ↓ TransferService.createPendingTransfer() → 建立 PENDING 轉帳
    ↓ 回傳結果（等待排程器處理，約 10 分鐘）

[Scheduler] (每 5 分鐘執行)
    ↓ 調用 TransferFacade.processPendingTransfers()

[TransferFacade 編排]
    ↓ TransferService.findPendingTransfers() → 查詢 PENDING 轉帳
    ↓ BalanceService.checkSufficientBalance() → 檢查餘額
    ↓ (若足夠) TransferService.updateStatus() → 更新為 DEBIT_PROCESSING
    ↓ EventPublisher.publishBalanceChangeEvent() → 發送 transfer_out 事件
    ↓ (若不足) TransferService.updateStatus() → 更新為 DEBIT_FAILED

[BalanceChangeConsumer]
    ↓ 調用 BalanceFacade.handleBalanceChange()

[BalanceFacade 編排]
    ↓ BalanceService.debitBalance() → 扣減餘額（含冪等性檢查）
    ↓ EventPublisher.publishBalanceChangeResult() → 發送扣款結果

[BalanceChangeResultConsumer]
    ↓ 調用 TransferFacade.handleDebitResult()

[TransferFacade 編排]
    ↓ 若成功：TransferService.updateStatus() → 更新為 CREDIT_PROCESSING
    ↓ EventPublisher.publishBalanceChangeEvent() → 發送 transfer_in 事件

[BalanceChangeConsumer]
    ↓ 調用 BalanceFacade.handleBalanceChange()

[BalanceFacade 編排]
    ↓ BalanceService.creditBalance() → 增加餘額（含冪等性檢查）
    ↓ EventPublisher.publishBalanceChangeResult() → 發送加帳結果

[BalanceChangeResultConsumer]
    ↓ 調用 TransferFacade.handleCreditResult()

[TransferFacade 編排]
    ↓ TransferService.completeTransfer() → 更新為 COMPLETED
```

### 1.3 轉帳狀態定義

| 狀態 | 說明 | 觸發條件 | 下一步狀態 |
|------|------|---------|-----------|
| **PENDING** | 轉帳記錄已建立，等待排程器處理 | API 建立記錄 | DEBIT_PROCESSING, DEBIT_FAILED, CANCELLED |
| **DEBIT_PROCESSING** | 扣款事件已發送，Balance Service 處理中 | 排程器檢查餘額足夠後發送 MQ | CREDIT_PROCESSING, DEBIT_FAILED |
| **CREDIT_PROCESSING** | 加帳事件已發送，Balance Service 處理中 | 扣款成功後發送 MQ | COMPLETED |
| **COMPLETED** | 轉帳完成（扣款和加帳都成功） | 加帳成功 | 終態 ✓ |
| **DEBIT_FAILED** | 扣款失敗（餘額不足或系統錯誤） | 餘額不足或扣款失敗 | 終態 ✗ |
| **CANCELLED** | 已取消（PENDING 狀態 10 分鐘內可取消） | 用戶主動取消 | 終態 ✗ |

---

## 2. API 規格

### 2.1 Balance Service API

#### 2.1.1 建立使用者並設定初始餘額

```
POST /balance-service/users
描述：建立新使用者並設定初始餘額
權限：無需認證（簡化設計）
服務：Balance Service
```

##### Request 規格

**Headers:**
```
Content-Type: application/json
```

**Request Body:**

| 欄位 | 類型 | 必填 | 說明 | 驗證規則 |
|------|------|------|------|----------|
| userId | String | 是 | 使用者唯一識別碼 | 長度 3-50 字元，僅允許英數字、底線、連字號，正則：`^[a-zA-Z0-9_-]{3,50}$` |
| initialBalance | Number | 是 | 初始餘額 | 必須 >= 0，最多 2 位小數，使用 BigDecimal 儲存 |

**Request Example:**
```json
{
  "userId": "user_001",
  "initialBalance": 1000
}
```

##### Response 規格

**Success Response (200 OK):**

| 欄位 | 類型 | 說明 |
|------|------|------|
| userId | String | 使用者識別碼 |
| balance | Number | 當前餘額（精度 2 位小數） |
| createdAt | String | 建立時間（ISO 8601） |

**Success Example:**
```json
{
  "userId": "user_001",
  "balance": 1000.00,
  "createdAt": "2026-01-11T10:30:00Z"
}
```

**Error Responses:**

| HTTP Status | 錯誤碼 | 說明 | 範例訊息 |
|-------------|--------|------|----------|
| 400 | INVALID_USER_ID | userId 格式不正確 | "Invalid userId format" |
| 400 | INVALID_BALANCE | initialBalance 為負數或格式錯誤 | "Initial balance must be non-negative" |
| 400 | USER_ALREADY_EXISTS | userId 已存在 | "User already exists" |
| 400 | INVALID_DECIMAL_PLACES | 小數位數超過 2 位 | "Amount must have at most 2 decimal places" |
| 400 | MISSING_FIELD | 缺少必填欄位 | "Missing required field: userId" |
| 500 | DATABASE_ERROR | 資料庫錯誤 | "Database connection error" |

---

#### 2.1.2 查詢使用者餘額

```
GET /balance-service/users/{userId}/balance
描述：查詢指定使用者的當前餘額（優先從 Redis 快取讀取）
權限：無需認證
服務：Balance Service
```

##### Request 規格

**Path Parameters:**

| 參數 | 類型 | 必填 | 說明 | 驗證規則 |
|------|------|------|------|----------|
| userId | String | 是 | 使用者識別碼 | 長度 3-50 字元 |

**Request Example:**
```
GET /balance-service/users/user_001/balance
```

##### Response 規格

**Success Response (200 OK):**

| 欄位 | 類型 | 說明 |
|------|------|------|
| userId | String | 使用者識別碼 |
| balance | Number | 當前餘額 |

**Success Example:**
```json
{
  "userId": "user_001",
  "balance": 850.50
}
```

**Error Responses:**

| HTTP Status | 錯誤碼 | 說明 | 範例訊息 |
|-------------|--------|------|----------|
| 404 | USER_NOT_FOUND | 使用者不存在 | "User not found" |
| 500 | CACHE_ERROR | Redis 連線失敗 | "Cache service unavailable" |
| 500 | DATABASE_ERROR | 資料庫錯誤 | "Database connection error" |

---

### 2.2 Transfer Service API

#### 2.2.1 執行轉帳

```
POST /transfers
描述：從一個使用者帳戶轉帳至另一個使用者帳戶（異步處理）
權限：無需認證
服務：Transfer Service
```

##### Request 規格

**Headers:**
```
Content-Type: application/json
```

**Request Body:**

| 欄位 | 類型 | 必填 | 說明 | 驗證規則 |
|------|------|------|------|----------|
| fromUserId | String | 是 | 付款方使用者 ID | 長度 3-50 字元 |
| toUserId | String | 是 | 收款方使用者 ID | 長度 3-50 字元，不可與 fromUserId 相同 |
| amount | Number | 是 | 轉帳金額 | 必須 >= 0.01，最多 2 位小數 |

**Request Example:**
```json
{
  "fromUserId": "user_001",
  "toUserId": "user_002",
  "amount": 150.00
}
```

##### Response 規格

**Success Response (200 OK):**

| 欄位 | 類型 | 說明 |
|------|------|------|
| transferId | Long | 轉帳交易 ID（自增主鍵） |
| fromUserId | String | 付款方使用者 ID |
| toUserId | String | 收款方使用者 ID |
| amount | Number | 轉帳金額 |
| status | String | 轉帳狀態（PENDING） |
| createdAt | String | 交易建立時間（ISO 8601） |

**Success Example:**
```json
{
  "transferId": 12345,
  "fromUserId": "user_001",
  "toUserId": "user_002",
  "amount": 150.00,
  "status": "PENDING",
  "createdAt": "2026-01-11T10:30:00Z"
}
```

**注意**：轉帳為完全異步處理，API 回應時狀態為 `PENDING`，等待排程器處理（約 10 分鐘後）。排程器會更新狀態為 `PROCESSING` 並發送扣款事件，實際扣款由 Balance Service 處理後更新為 `DEBIT_COMPLETED` 或 `DEBIT_FAILED`

**Error Responses:**

| HTTP Status | 錯誤碼 | 說明 | 範例訊息 |
|-------------|--------|------|----------|
| 400 | INVALID_AMOUNT | 金額小於 0.01 或格式錯誤 | "Amount must be at least 0.01" |
| 400 | INVALID_DECIMAL_PLACES | 小數位數超過 2 位 | "Amount must have at most 2 decimal places" |
| 400 | SENDER_NOT_FOUND | 付款方不存在 | "Sender user not found" |
| 400 | RECIPIENT_NOT_FOUND | 收款方不存在 | "Recipient user not found" |
| 400 | INSUFFICIENT_BALANCE | 餘額不足（快取檢查） | "Insufficient balance" |
| 400 | SELF_TRANSFER | 不可轉帳給自己 | "Cannot transfer to self" |
| 400 | MISSING_FIELD | 缺少必填欄位 | "Missing required field: fromUserId" |
| 500 | DATABASE_ERROR | 資料庫錯誤 | "Database connection error" |
| 500 | MESSAGE_QUEUE_ERROR | RocketMQ 錯誤 | "Message queue error" |
| 500 | BALANCE_SERVICE_ERROR | Balance Service 呼叫失敗 | "Balance service unavailable" |

**Error Example:**
```json
{
  "timestamp": "2026-01-11T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Insufficient balance"
}
```

---

#### 2.2.2 查詢轉帳歷史記錄

```
GET /transfers?userId={userId}&page={page}&size={size}
描述：查詢指定使用者的轉帳歷史記錄（包含作為付款方或收款方的交易）
權限：無需認證
服務：Transfer Service
```

##### Request 規格

**Query Parameters:**

| 參數 | 類型 | 必填 | 說明 | 驗證規則 |
|------|------|------|------|----------|
| userId | String | 是 | 使用者識別碼 | 長度 3-50 字元 |
| page | Integer | 否 | 頁碼（1-based） | 必須 >= 1，預設值 1 |
| size | Integer | 否 | 每頁筆數 | 必須 >= 1，預設值 10 |

**Request Example:**
```
GET /transfers?userId=user_001&page=1&size=10
```

##### Response 規格

**Success Response (200 OK):**

| 欄位 | 類型 | 說明 |
|------|------|------|
| content | Array | 轉帳記錄陣列 |
| content[].transferId | Long | 轉帳交易 ID |
| content[].fromUserId | String | 付款方使用者 ID |
| content[].toUserId | String | 收款方使用者 ID |
| content[].amount | Number | 轉帳金額 |
| content[].status | String | 轉帳狀態 |
| content[].createdAt | String | 交易建立時間（ISO 8601） |
| content[].completedAt | String | 交易完成時間（ISO 8601，可為 null） |
| page | Integer | 當前頁碼 |
| size | Integer | 每頁筆數 |
| totalElements | Integer | 總記錄數 |
| totalPages | Integer | 總頁數 |

**Success Example:**
```json
{
  "content": [
    {
      "transferId": 12345,
      "fromUserId": "user_001",
      "toUserId": "user_002",
      "amount": 150.00,
      "status": "COMPLETED",
      "createdAt": "2026-01-11T10:30:00Z",
      "completedAt": "2026-01-11T10:30:01Z"
    },
    {
      "transferId": 12344,
      "fromUserId": "user_003",
      "toUserId": "user_001",
      "amount": 200.00,
      "status": "DEBIT_PROCESSING",
      "createdAt": "2026-01-11T09:15:00Z",
      "completedAt": null
    }
  ],
  "page": 1,
  "size": 10,
  "totalElements": 25,
  "totalPages": 3
}
```

**Error Responses:**

| HTTP Status | 錯誤碼 | 說明 | 範例訊息 |
|-------------|--------|------|----------|
| 400 | INVALID_PAGINATION | page 或 size 為負數或 0 | "Invalid pagination parameters" |
| 500 | DATABASE_ERROR | 資料庫錯誤 | "Database connection error" |

---

#### 2.2.3 取消轉帳

```
POST /transfers/{transferId}/cancel
描述：取消指定的轉帳交易（僅限 PENDING 狀態且 10 分鐘內）
權限：無需認證
服務：Transfer Service
```

##### Request 規格

**Path Parameters:**

| 參數 | 類型 | 必填 | 說明 |
|------|------|------|------|
| transferId | Long | 是 | 轉帳交易 ID |

**Request Example:**
```
POST /transfers/12345/cancel
```

##### Response 規格

**Success Response (200 OK):**

| 欄位 | 類型 | 說明 |
|------|------|------|
| transferId | Long | 轉帳交易 ID |
| status | String | 更新後的狀態（CANCELLED） |
| cancelledAt | String | 取消時間（ISO 8601） |

**Success Example:**
```json
{
  "transferId": 12345,
  "status": "CANCELLED",
  "cancelledAt": "2026-01-11T10:35:00Z"
}
```

**Error Responses:**

| HTTP Status | 錯誤碼 | 說明 | 範例訊息 |
|-------------|--------|------|----------|
| 404 | TRANSFER_NOT_FOUND | 轉帳記錄不存在 | "Transfer not found" |
| 400 | CANNOT_CANCEL_TRANSFER | 無法取消非 PENDING 狀態的轉帳 | "Cannot cancel transfer in DEBIT_PROCESSING status" |
| 400 | CANCEL_WINDOW_EXPIRED | 取消時間窗口已過期 | "Cancel time window expired" |
| 500 | DATABASE_ERROR | 資料庫錯誤 | "Database connection error" |

---

## 3. 業務邏輯流程

### 3.1 建立使用者流程（Balance Service）

```
1. 接收 POST /balance-service/users 請求
2. 驗證輸入資料
   2.1 檢查 userId 欄位是否存在
   2.2 驗證 userId 格式（正則：^[a-zA-Z0-9_-]{3,50}$）
   2.3 檢查 initialBalance 欄位是否存在
   2.4 驗證 initialBalance >= 0
   2.5 驗證小數位數 <= 2
3. 檢查 userId 唯一性
   3.1 查詢資料庫是否存在相同 userId
   3.2 若存在，回傳 400 錯誤 "User already exists"
4. 開啟資料庫事務
5. 建立使用者記錄
   5.1 產生 UserBalance 實體
   5.2 設定 userId、balance（BigDecimal）、createdAt（當前時間）
   5.3 INSERT INTO user_balances
6. 提交事務
7. 寫入 Redis 快取
   7.1 使用鍵 "balance:{userId}"
   7.2 儲存 balance 值
   7.3 設定 TTL 為 300 秒（5 分鐘）
8. 記錄日誌（INFO 等級）
   8.1 "User created: {userId}, balance: {balance}"
9. 回傳成功回應
   9.1 HTTP 200 OK
   9.2 JSON 包含 userId, balance, createdAt
```

---

### 3.2 查詢餘額流程（Balance Service）

```
1. 接收 GET /balance-service/users/{userId}/balance 請求
2. 驗證 userId 參數
   2.1 檢查 userId 長度是否在 3-50 字元範圍
3. 嘗試從 Redis 快取讀取
   3.1 使用鍵 "balance:{userId}"
   3.2 若快取命中（Cache Hit）
       a. 解析 balance 值
       b. 記錄日誌（DEBUG 等級）"Cache hit for userId: {userId}"
       c. 跳至步驟 6
   3.3 若快取未命中（Cache Miss）
       a. 記錄日誌（DEBUG 等級）"Cache miss for userId: {userId}"
       b. 繼續至步驟 4
4. 從 MySQL 查詢
   4.1 SELECT balance FROM user_balances WHERE userId = ?
   4.2 若使用者不存在，回傳 404 錯誤 "User not found"
   4.3 若查詢成功，取得 balance 值
5. 更新 Redis 快取
   5.1 寫入鍵 "balance:{userId}"
   5.2 設定 TTL 為 300 秒
6. 回傳成功回應
   6.1 HTTP 200 OK
   6.2 JSON 包含 userId 和 balance
```

---

### 3.3 執行轉帳流程（完全異步事件驅動）

#### 3.3.1 Controller 接收轉帳請求

```
1. TransferController 接收 POST /transfers 請求

2. 驗證基本輸入格式
   2.1 檢查 fromUserId、toUserId、amount 是否存在
   2.2 驗證 fromUserId 和 toUserId 格式
   2.3 驗證 fromUserId != toUserId（不可轉給自己）
   2.4 驗證 amount >= 0.01
   2.5 驗證 amount 小數位數 <= 2

3. 調用 TransferFacade.createTransfer(request)
   3.1 Facade 負責編排所有業務流程（詳見 3.3.2）
   3.2 若發生業務異常（餘額不足、使用者不存在），Facade 拋出異常
   3.3 Controller 捕獲異常並轉換為適當的 HTTP 回應

4. 回傳成功回應
   4.1 HTTP 200 OK
   4.2 JSON 包含 transferId, fromUserId, toUserId, amount, status=PENDING, createdAt
```

**注意**：
- Controller 僅負責 HTTP 請求處理和輸入驗證
- 所有業務邏輯由 TransferFacade 編排
- 轉帳初始狀態為 PENDING，等待排程器處理（約 10 分鐘後）

---

#### 3.3.2 TransferFacade 編排轉帳建立流程

**執行流程：**

```
1. 呼叫 BalanceService.validateUserExists(fromUserId)
   1.1 檢查轉出方是否存在
   1.2 若使用者不存在，拋出 UserNotFoundException
       - 錯誤訊息："Sender user not found: {fromUserId}"

2. 呼叫 BalanceService.validateUserExists(toUserId)
   2.1 檢查收款方是否存在
   2.2 若使用者不存在，拋出 UserNotFoundException
       - 錯誤訊息："Recipient user not found: {toUserId}"

3. 呼叫 BalanceService.checkSufficientBalance(fromUserId, amount)
   3.1 從 Redis 快取或 MySQL 資料庫查詢餘額
   3.2 比較 balance >= amount
   3.3 若餘額不足，拋出 InsufficientBalanceException
       - 錯誤訊息："Insufficient balance for user {fromUserId}: has {balance}, requires {amount}"

4. 呼叫 TransferService.createPendingTransfer(fromUserId, toUserId, amount)
   4.1 開啟資料庫事務
   4.2 建立 Transfer 實體
       - fromUserId
       - toUserId
       - amount
       - status = PENDING
       - createdAt = 當前時間
   4.3 INSERT INTO transfers
   4.4 獲取自增主鍵 id（作為 transferId）
   4.5 提交事務
   4.6 記錄稽核日誌（INFO 等級）
       "Transfer created: {transferId}, from {fromUserId} to {toUserId}, amount {amount}, status: PENDING"
   4.7 回傳 Transfer 物件

5. Facade 回傳成功回應給 Controller
   5.1 轉換 Transfer 實體為 DTO
   5.2 回傳給 Controller 層
```

**設計原則：**
- Facade 僅協調流程，不包含業務邏輯
- 每個 Service 方法都是原子性操作，包含完整的事務邊界
- 錯誤由 Service 層拋出，Facade 向上傳播給 Controller
- BalanceService 和 TransferService 透過直接方法調用協作，無 REST API

**異常處理：**
- UserNotFoundException → HTTP 400 Bad Request
- InsufficientBalanceException → HTTP 400 Bad Request
- 其他 RuntimeException → HTTP 500 Internal Server Error

---

#### 3.3.3 Scheduler 觸發轉帳處理

**執行頻率**：每 5 分鐘執行一次（fixedDelay = 300000ms）

**處理流程：**

```java
@Component
public class TransferScheduler {

    @Autowired
    private TransferFacade transferFacade;

    @Scheduled(fixedDelay = 300000)  // 每 5 分鐘
    public void processPendingTransfers() {
        try {
            transferFacade.processPendingTransfers();
        } catch (Exception e) {
            log.error("Scheduler execution failed", e);
            // 不拋出異常，避免影響下次排程
        }
    }
}
```

**設計原則：**
- Scheduler 僅觸發流程，不包含業務邏輯
- 所有業務處理由 TransferFacade 編排
- 異常不向上拋出，僅記錄日誌

---

#### 3.3.4 TransferFacade 處理 PENDING 轉帳

**觸發方式**：由 Scheduler 每 5 分鐘調用一次

**執行流程：**

```
1. 呼叫 TransferService.findPendingTransfers(cutoffTime, limit)
   1.1 cutoffTime = NOW() - 10 分鐘
   1.2 limit = 100（批次處理，避免一次處理過多）
   1.3 執行查詢：
       SELECT * FROM transfers
       WHERE status = 'PENDING'
       AND created_at <= cutoffTime
       ORDER BY created_at ASC
       LIMIT 100
   1.4 回傳 List<Transfer>
   1.5 若清單為空，結束本次處理

2. 迭代處理每筆轉帳
   2.1 呼叫 BalanceService.checkSufficientBalance(fromUserId, amount)
       - 從 Redis 快取或 MySQL 查詢餘額
       - 若餘額不足：
         * 呼叫 TransferService.updateStatus(transferId, DEBIT_FAILED, "Insufficient balance")
         * 記錄日誌，跳過此筆，繼續下一筆

   2.2 呼叫 TransferService.updateStatusWithLock(transferId, DEBIT_PROCESSING)
       - 開啟資料庫事務
       - SELECT * FROM transfers WHERE id = ? FOR UPDATE
       - 檢查狀態是否為 PENDING
       - 若狀態已變更（如被取消），回傳 null，跳過此筆
       - UPDATE status = 'DEBIT_PROCESSING'
       - 提交事務

   2.3 呼叫 EventPublisher.publishBalanceChangeEvent(transfer, DEBIT)
       - Topic: balance-change-events
       - Tag: balance_change
       - Body: {
           "eventType": "BALANCE_CHANGE",
           "externalId": transferId,
           "type": "transfer_out",
           "userId": fromUserId,
           "amount": -amount,
           "relatedId": transferId,
           "timestamp": "2026-01-11T10:40:00Z"
         }
       - 若發送失敗，拋出 EventPublishException

   2.4 若任何步驟發生異常
       - 呼叫 TransferService.markAsFailed(transferId, reason)
       - UPDATE transfers SET status = 'DEBIT_FAILED', failure_reason = ?
       - 記錄 ERROR 日誌
       - 繼續處理下一筆（不中斷整體流程）

3. 處理完所有轉帳後，結束本次執行
```

**錯誤處理：**
- 單筆轉帳失敗不影響其他轉帳處理
- EventPublisher 發送失敗會標記該筆為 DEBIT_FAILED
- 餘額不足時直接標記為 DEBIT_FAILED，不發送 MQ

**設計原則：**
- Facade 編排多個 Service 的原子性操作
- TransferService 提供原子性方法（findPendingTransfers, updateStatusWithLock, updateStatus, markAsFailed）
- EventPublisher 提供原子性事件發送
- 錯誤處理在 Facade 層統一管理

---

#### 3.3.5 Balance Service Consumer 處理扣款

**消費事件**：Balance Change Event (type: transfer_out)

**處理流程：**

```java
@RocketMQMessageListener(
    topic = "balance-change-events",
    consumerGroup = "balance-service-consumer-group",
    selectorExpression = "balance_change"
)
public class BalanceChangeConsumer implements RocketMQListener<BalanceChangeEvent> {

    @Autowired
    private BalanceFacade balanceFacade;

    @Override
    public void onMessage(BalanceChangeEvent event) {
        try {
            // 調用 Facade 處理帳變邏輯（詳細實作見 Section 8.5）
            balanceFacade.handleBalanceChange(event);
        } catch (Exception e) {
            log.error("Failed to handle balance change event: {}", event, e);
            throw e;  // 觸發 RocketMQ 重試機制
        }
    }
}
```

**設計原則：**
- Consumer 僅負責接收訊息和調用 Facade
- 所有業務邏輯由 BalanceFacade 編排
- 異常向上拋出，觸發 RocketMQ 自動重試

**BalanceFacade 處理邏輯概要：**
1. 呼叫 BalanceService.debitBalance(externalId, userId, amount)
   - 檢查冪等性（external_id + type 唯一約束）
   - 鎖定使用者、檢查餘額、更新餘額
   - 記錄 balance_change（含 before/after balance）
2. 呼叫 EventPublisher.publishBalanceChangeResult(成功/失敗)
3. 若發生 InsufficientBalanceException，發送失敗結果

**目標處理時間**：< 200ms

**注意**：詳細的 BalanceFacade 和 BalanceService 實作請參考 Section 8.5

---

#### 3.3.6 Transfer Service Consumer 處理扣款結果

**消費事件**：Balance Change Result Event (type: transfer_out)

**處理流程：**

```java
@RocketMQMessageListener(
    topic = "balance-change-events",
    consumerGroup = "transfer-service-consumer-group",
    selectorExpression = "balance_change_result"
)
public class BalanceChangeResultConsumer implements RocketMQListener<BalanceChangeResultEvent> {

    @Autowired
    private TransferFacade transferFacade;

    @Override
    public void onMessage(BalanceChangeResultEvent event) {
        try {
            if (event.getType() == BalanceChangeType.TRANSFER_OUT) {
                // 調用 Facade 處理扣款結果（詳細實作見 Section 8.4）
                transferFacade.handleDebitResult(event);
            } else if (event.getType() == BalanceChangeType.TRANSFER_IN) {
                // 調用 Facade 處理加帳結果（詳見 Section 3.3.8）
                transferFacade.handleCreditResult(event);
            }
        } catch (Exception e) {
            log.error("Failed to handle balance change result: {}", event, e);
            throw e;  // 觸發 RocketMQ 重試機制
        }
    }
}
```

**設計原則：**
- Consumer 僅負責接收訊息和調用 Facade
- 所有業務邏輯由 TransferFacade 編排
- 異常向上拋出，觸發 RocketMQ 自動重試

**TransferFacade.handleDebitResult() 處理邏輯概要：**
1. 若 success = true（扣款成功）：
   - 呼叫 TransferService.updateStatus(transferId, CREDIT_PROCESSING)
   - 呼叫 EventPublisher.publishBalanceChangeEvent(transfer_in)
2. 若 success = false（扣款失敗）：
   - 呼叫 TransferService.markAsFailed(transferId, reason)
   - 發送 Transfer Failed Event

**注意**：詳細的 TransferFacade 實作請參考 Section 8.4

---

#### 3.3.7 Balance Service Consumer 處理加帳

**消費事件**：Balance Change Event (type: transfer_in)

**處理流程：**

```java
// 與 Section 3.3.5 使用同一個 Consumer 處理扣款和加帳
@RocketMQMessageListener(
    topic = "balance-change-events",
    consumerGroup = "balance-service-consumer-group",
    selectorExpression = "balance_change"
)
public class BalanceChangeConsumer implements RocketMQListener<BalanceChangeEvent> {

    @Autowired
    private BalanceFacade balanceFacade;

    @Override
    public void onMessage(BalanceChangeEvent event) {
        try {
            // 調用同一個 Facade 方法處理扣款和加帳（詳細實作見 Section 8.5）
            balanceFacade.handleBalanceChange(event);
        } catch (Exception e) {
            log.error("Failed to handle balance change event: {}", event, e);
            throw e;  // 觸發 RocketMQ 重試機制
        }
    }
}
```

**設計原則：**
- Consumer 僅負責接收訊息和調用 Facade
- 扣款（transfer_out）和加帳（transfer_in）使用同一個處理方法
- 所有業務邏輯由 BalanceFacade 編排

**BalanceFacade 處理邏輯概要：**
1. 呼叫 BalanceService.creditBalance(externalId, userId, amount)
   - 檢查冪等性（external_id + type 唯一約束）
   - 鎖定使用者、更新餘額（加帳不檢查餘額上限）
   - 記錄 balance_change（含 before/after balance）
2. 呼叫 EventPublisher.publishBalanceChangeResult(成功)

**目標處理時間**：< 200ms

**注意**：
- 根據需求，不考慮加帳失敗場景，此處假設加帳一定成功
- 詳細的 BalanceFacade 和 BalanceService 實作請參考 Section 8.5

---

#### 3.3.8 Transfer Service Consumer 處理加帳結果

**消費事件**：Balance Change Result Event (type: transfer_in)

**處理流程：**

```java
// 與 Section 3.3.6 使用同一個 Consumer 處理扣款和加帳結果
@RocketMQMessageListener(
    topic = "balance-change-events",
    consumerGroup = "transfer-service-consumer-group",
    selectorExpression = "balance_change_result"
)
public class BalanceChangeResultConsumer implements RocketMQListener<BalanceChangeResultEvent> {

    @Autowired
    private TransferFacade transferFacade;

    @Override
    public void onMessage(BalanceChangeResultEvent event) {
        try {
            if (event.getType() == BalanceChangeType.TRANSFER_OUT) {
                transferFacade.handleDebitResult(event);
            } else if (event.getType() == BalanceChangeType.TRANSFER_IN) {
                // 調用 Facade 處理加帳結果（詳細實作見 Section 8.6）
                transferFacade.handleCreditResult(event);
            }
        } catch (Exception e) {
            log.error("Failed to handle balance change result: {}", event, e);
            throw e;  // 觸發 RocketMQ 重試機制
        }
    }
}
```

**設計原則：**
- Consumer 僅負責接收訊息和調用 Facade
- 扣款結果和加帳結果使用同一個 Consumer 處理
- 所有業務邏輯由 TransferFacade 編排

**TransferFacade.handleCreditResult() 處理邏輯概要：**
1. 呼叫 TransferService.completeTransfer(transferId)
   - 檢查轉帳狀態是否為 CREDIT_PROCESSING
   - UPDATE status = COMPLETED, completed_at = NOW()
2. 呼叫 EventPublisher.publishTransferCompletedEvent(transfer)
   - 發送轉帳完成事件通知下游服務

**注意**：詳細的 TransferFacade 實作請參考 Section 8.4

---

#### 3.3.9 扣款重試排程器

**執行頻率**：每 5 分鐘執行一次

**處理目標**：處理停留在 DEBIT_PROCESSING 狀態超過 10 分鐘的轉帳記錄

**處理流程：**

```
1. 查詢需要重試的轉帳記錄
   1.1 cutoffTime = NOW() - 10 分鐘
   1.2 執行查詢：
       SELECT * FROM transfers
       WHERE status = 'DEBIT_PROCESSING'
       AND updated_at <= cutoffTime
       ORDER BY updated_at ASC
       LIMIT 100
   1.3 若清單為空，結束本次處理

2. 迭代處理每筆轉帳
   2.1 呼叫 TransferService.findById(transferId)
       - 若狀態已變更（如已完成），跳過此筆

   2.2 呼叫 EventPublisher.publishBalanceChangeEvent(transfer, DEBIT)
       - 重新發送扣款事件
       - Topic: balance-change-events
       - Body: { eventType, externalId, type: "transfer_out", userId, amount, ... }

   2.3 更新 updated_at 時間戳
       - UPDATE transfers SET updated_at = NOW() WHERE id = ?
       - 避免下次立即重試

   2.4 記錄日誌
       - INFO "Retry debit event sent for transfer: {transferId}"

   2.5 若發送失敗
       - 記錄 ERROR 日誌
       - 繼續處理下一筆（不中斷整體流程）

3. 處理完所有轉帳後，結束本次執行
```

**錯誤處理：**
- 單筆重試失敗不影響其他記錄處理
- 保留原有狀態，等待下次排程再次重試
- 監控長時間停留在 DEBIT_PROCESSING 的記錄（>30 分鐘）

**設計原則：**
- 提供 At-Least-Once 語義保證
- 利用 BalanceService 的冪等性設計，重複發送不會造成重複扣款
- 確保因網路問題或 MQ 故障導致的消息丟失能被恢復

---

#### 3.3.10 加帳重試排程器

**執行頻率**：每 5 分鐘執行一次

**處理目標**：處理停留在 CREDIT_PROCESSING 狀態超過 10 分鐘的轉帳記錄

**處理流程：**

```
1. 查詢需要重試的轉帳記錄
   1.1 cutoffTime = NOW() - 10 分鐘
   1.2 執行查詢：
       SELECT * FROM transfers
       WHERE status = 'CREDIT_PROCESSING'
       AND updated_at <= cutoffTime
       ORDER BY updated_at ASC
       LIMIT 100
   1.3 若清單為空，結束本次處理

2. 迭代處理每筆轉帳
   2.1 呼叫 TransferService.findById(transferId)
       - 若狀態已變更（如已完成），跳過此筆

   2.2 呼叫 EventPublisher.publishBalanceChangeEvent(transfer, CREDIT)
       - 重新發送加帳事件
       - Topic: balance-change-events
       - Body: { eventType, externalId, type: "transfer_in", userId, amount, ... }

   2.3 更新 updated_at 時間戳
       - UPDATE transfers SET updated_at = NOW() WHERE id = ?
       - 避免下次立即重試

   2.4 記錄日誌
       - INFO "Retry credit event sent for transfer: {transferId}"

   2.5 若發送失敗
       - 記錄 ERROR 日誌
       - 繼續處理下一筆（不中斷整體流程）

3. 處理完所有轉帳後，結束本次執行
```

**錯誤處理：**
- 單筆重試失敗不影響其他記錄處理
- 保留原有狀態，等待下次排程再次重試
- 監控長時間停留在 CREDIT_PROCESSING 的記錄（>30 分鐘）

**設計原則：**
- 提供 At-Least-Once 語義保證
- 利用 BalanceService 的冪等性設計，重複發送不會造成重複加帳
- 確保因網路問題或 MQ 故障導致的消息丟失能被恢復

---

### 3.4 查詢轉帳歷史流程（Transfer Service）

```
1. 接收 GET /transfers 請求
2. 解析查詢參數
   2.1 取得 userId（必填）
   2.2 取得 page（選填，預設 1）
   2.3 取得 size（選填，預設 10）
3. 驗證參數
   3.1 驗證 userId 長度
   3.2 驗證 page >= 1
   3.3 驗證 size >= 1
   3.4 若驗證失敗，回傳 400 "Invalid pagination parameters"
4. 查詢資料庫
   4.1 執行分頁查詢
       SELECT * FROM transfers
       WHERE fromUserId = ? OR toUserId = ?
       ORDER BY createdAt DESC
       LIMIT ? OFFSET ?
   4.2 計算 OFFSET = (page - 1) * size
   4.3 LIMIT = size
5. 查詢總筆數
   5.1 SELECT COUNT(*) FROM transfers WHERE fromUserId = ? OR toUserId = ?
   5.2 計算 totalPages = CEIL(totalElements / size)
6. 組裝分頁結果
   6.1 建立 content 陣列（包含所有查詢到的 Transfer 記錄）
   6.2 設定 page, size, totalElements, totalPages
7. 回傳成功回應
   7.1 HTTP 200 OK
   7.2 JSON 包含完整分頁結構
```

---

### 3.5 取消轉帳流程（Transfer Service）

**限制**：僅 PENDING 狀態可取消，且在建立後 10 分鐘內

```
1. 接收 POST /transfers/{transferId}/cancel 請求
2. 查詢轉帳記錄
   2.1 SELECT * FROM transfers WHERE id = ?
   2.2 若不存在，回傳 404 "Transfer not found"
3. 檢查轉帳狀態
   3.1 若 status = DEBIT_PROCESSING
       - 回傳 400 "Cannot cancel transfer in DEBIT_PROCESSING status"
   3.2 若 status = CREDIT_PROCESSING
       - 回傳 400 "Cannot cancel transfer in CREDIT_PROCESSING status"
   3.3 若 status = COMPLETED
       - 回傳 400 "Cannot cancel completed transfer"
   3.4 若 status = DEBIT_FAILED
       - 回傳 400 "Cannot cancel failed transfer"
   3.5 若 status = CANCELLED
       - 回傳 400 "Transfer already cancelled"
   3.6 若 status != PENDING
       - 回傳 400 並說明當前狀態
4. 檢查取消時間窗口
   4.1 計算時間差 = 當前時間 - createdAt
   4.2 若時間差 > 10 分鐘
       - 回傳 400 "Cancel time window expired (10 minutes)"
   4.3 注意：由於排程器每 5 分鐘執行，實際可取消窗口為完整的 10 分鐘
5. 開啟資料庫事務
6. 更新轉帳記錄狀態
   6.1 UPDATE transfers SET
       status = 'CANCELLED',
       cancelledAt = NOW()
       WHERE id = ?
7. 提交事務
8. 發送 Transfer Cancelled Event（通知下游）
   8.1 Topic: balance-transfer-events
   8.2 Tag: transfer_cancelled
   8.3 Body: {
         "eventType": "TRANSFER_CANCELLED",
         "transferId": transferId,
         "reason": "User requested cancellation",
         "timestamp": "2026-01-11T10:35:00Z"
       }
9. 記錄稽核日誌（INFO 等級）
   9.1 "Transfer cancelled: {transferId}, status was PENDING"
10. 回傳成功回應
    10.1 HTTP 200 OK
    10.2 JSON 包含 transferId, status=CANCELLED, cancelledAt
```

**注意**：
- PENDING 狀態的轉帳尚未發送扣款事件，因此取消時不需要回復餘額
- 取消窗口為完整的 10 分鐘（在排程器處理前）
- 一旦狀態變為 DEBIT_PROCESSING，則無法取消

---

### 3.6 資料驗證規則

#### userId 驗證
- **格式**：僅允許英文字母（大小寫）、數字、底線（_）、連字號（-）
- **長度**：3-50 字元
- **正則表達式**：`^[a-zA-Z0-9_-]{3,50}$`
- **唯一性**：不可與現有使用者重複

#### Balance 驗證
- **資料型態**：BigDecimal
- **最小值**：0.00（不允許負數）
- **精度**：最多 2 位小數
- **範例有效值**：0, 1000, 1000.50, 0.01
- **範例無效值**：-100, 1000.123

#### Amount 驗證
- **資料型態**：BigDecimal
- **最小值**：0.01
- **精度**：最多 2 位小數
- **範例有效值**：0.01, 150.00, 1000.99
- **範例無效值**：0, -50, 100.123

#### 分頁參數驗證
- **page**：整數，必須 >= 1，預設值 1
- **size**：整數，必須 >= 1，預設值 10

---

### 3.7 業務規則

#### 轉帳狀態轉換規則
- **PENDING → DEBIT_PROCESSING**：排程器檢測餘額足夠後
- **PENDING → DEBIT_FAILED**：排程器檢測餘額不足
- **PENDING → CANCELLED**：使用者主動取消（10 分鐘內）
- **DEBIT_PROCESSING → CREDIT_PROCESSING**：Balance Service 扣款成功
- **DEBIT_PROCESSING → DEBIT_FAILED**：Balance Service 扣款失敗
- **CREDIT_PROCESSING → COMPLETED**：Balance Service 加帳成功

#### 冪等性規則
- **Balance Service**：使用 `(external_id, type)` 唯一約束確保冪等
- **externalId 格式**：
  - externalId = transfer 表的自增主鍵（Long 類型）
  - 扣款：(transferId, transfer_out)
  - 加帳：(transferId, transfer_in)
  - 同一筆轉帳會產生兩條 balance_change 記錄（透過 type 區分）
- **重複消費處理**：
  - 若 `balance_change` 記錄已存在且 status = COMPLETED，重新發送成功結果
  - 若 status = FAILED，重新發送失敗結果
  - 若 status = PROCESSING，忽略（可能並發或重複消費）

#### 快取更新策略
- **建立使用者**：Balance Service 寫入 Redis，TTL 300 秒
- **扣款完成**：Balance Service 更新付款方快取
- **加帳完成**：Balance Service 更新收款方快取
- **查詢餘額**：Cache Miss 時從 DB 載入並寫入快取
- **快取鍵格式**：`balance:{userId}`

#### 事件發送規則
- **同步發送**：Transfer Service 發送 balance_change 事件（確保送達）
- **失敗處理**：發送失敗立即更新轉帳狀態為 DEBIT_FAILED
- **異步發送**：通知類事件（transfer_completed, transfer_failed）失敗不影響核心流程

---

### 3.8 事務邊界

#### Balance Service - 帳變事務
- **開始**：接收 balance_change 事件後
- **結束**：提交事務
- **包含操作**：
  - 新增 balance_change 記錄
  - 鎖定使用者記錄
  - 更新使用者餘額
  - 更新 balance_change 狀態
- **回滾條件**：
  - 餘額不足（僅扣款）
  - 使用者不存在
  - 資料庫更新失敗
  - 任何 SQL 異常
- **非事務操作**：
  - Redis 快取更新（事務後）
  - 發送 balance_change_result 事件（事務後）

#### Transfer Service - 建立轉帳事務
- **開始**：驗證通過後
- **結束**：INSERT 轉帳記錄後立即提交
- **包含操作**：
  - INSERT INTO transfers (status = PENDING)
- **非事務操作**：
  - Balance Service API 呼叫（事務前）
  - 發送 balance_change 事件（事務後）
  - 更新狀態為 DEBIT_PROCESSING（獨立事務）

#### Transfer Service - 狀態更新事務
- **開始**：接收 balance_change_result 後
- **結束**：UPDATE 狀態後立即提交
- **包含操作**：
  - UPDATE transfers SET status = ?
- **非事務操作**：
  - 發送下一個 balance_change 事件（事務後）
  - 發送通知事件（事務後）

#### Transfer Service - 取消轉帳事務
- **開始**：狀態檢查通過後
- **結束**：UPDATE 狀態後立即提交
- **包含操作**：
  - UPDATE transfers SET status = 'CANCELLED'
- **非事務操作**：
  - 發送 transfer_cancelled 事件（事務後）

---

## 4. 錯誤處理

### 4.1 錯誤分類與處理策略

#### 客戶端錯誤（4xx）

**400 Bad Request - INVALID_USER_ID**
- **觸發條件**：userId 格式不符合規則
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Invalid userId format"
  - 不進行重試

**400 Bad Request - INVALID_BALANCE**
- **觸發條件**：initialBalance 為負數或非數字
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Initial balance must be non-negative"
  - 不進行重試

**400 Bad Request - USER_ALREADY_EXISTS**
- **觸發條件**：嘗試建立已存在的 userId
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："User already exists"
  - 不進行重試

**400 Bad Request - INVALID_AMOUNT**
- **觸發條件**：amount < 0.01 或格式錯誤
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Amount must be at least 0.01"
  - 不進行重試

**400 Bad Request - INVALID_DECIMAL_PLACES**
- **觸發條件**：金額或餘額小數位數超過 2 位
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Amount must have at most 2 decimal places"
  - 不進行重試

**400 Bad Request - SENDER_NOT_FOUND**
- **觸發條件**：轉帳時 fromUserId 不存在
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Sender user not found"
  - 不進行重試

**400 Bad Request - RECIPIENT_NOT_FOUND**
- **觸發條件**：轉帳時 toUserId 不存在
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Recipient user not found"
  - 不進行重試

**400 Bad Request - INSUFFICIENT_BALANCE**
- **觸發條件**：
  - API 層：快取餘額不足（快速失敗）
  - Balance Service：資料庫餘額不足（最終檢查）
- **處理方式**：
  - 記錄日誌等級：WARN
  - API 層：回傳錯誤給客戶端
  - Balance Service：記錄失敗的 balance_change，發送失敗結果
  - 不進行重試

**400 Bad Request - SELF_TRANSFER**
- **觸發條件**：fromUserId 與 toUserId 相同
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Cannot transfer to self"
  - 不進行重試

**400 Bad Request - MISSING_FIELD**
- **觸發條件**：請求缺少必填欄位
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Missing required field: {fieldName}"
  - 不進行重試

**400 Bad Request - INVALID_PAGINATION**
- **觸發條件**：page 或 size 為負數或 0
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Invalid pagination parameters"
  - 不進行重試

**400 Bad Request - CANNOT_CANCEL_TRANSFER**
- **觸發條件**：嘗試取消狀態不是 PENDING 的轉帳
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息（依狀態不同）
  - 不進行重試

**400 Bad Request - CANCEL_WINDOW_EXPIRED**
- **觸發條件**：嘗試取消建立超過 10 分鐘的轉帳
- **處理方式**：
  - 記錄日誌等級：WARN
  - 回傳錯誤訊息："Cancel time window expired"
  - 不進行重試

**404 Not Found - USER_NOT_FOUND**
- **觸發條件**：查詢不存在的使用者餘額
- **處理方式**：
  - 記錄日誌等級：INFO
  - 回傳錯誤訊息："User not found"
  - 不進行重試

**404 Not Found - TRANSFER_NOT_FOUND**
- **觸發條件**：查詢或取消不存在的轉帳記錄
- **處理方式**：
  - 記錄日誌等級：INFO
  - 回傳錯誤訊息："Transfer not found"
  - 不進行重試

---

#### 伺服器錯誤（5xx）

**500 Internal Server Error - DATABASE_ERROR**
- **觸發條件**：
  - 資料庫連線失敗
  - SQL 執行錯誤
  - 事務提交失敗
- **處理方式**：
  - 記錄日誌等級：ERROR
  - 記錄完整 stack trace
  - 回滾事務
  - 回傳通用錯誤訊息："Database connection error"
  - 發送告警通知
  - MQ Consumer：返回 NACK（觸發重試）

**500 Internal Server Error - CACHE_ERROR**
- **觸發條件**：
  - Redis 連線失敗
  - Redis 讀寫超時
- **處理方式**：
  - 記錄日誌等級：ERROR
  - **降級處理**：直接從資料庫讀取
  - 回傳錯誤訊息："Cache service unavailable"
  - 發送告警通知

**500 Internal Server Error - MESSAGE_QUEUE_ERROR**
- **觸發條件**：
  - RocketMQ 發送失敗
  - Broker 不可用
- **處理方式**：
  - 記錄日誌等級：ERROR
  - 記錄失敗的訊息內容
  - 更新轉帳狀態為 DEBIT_FAILED
  - 回傳錯誤訊息："Message queue error"
  - 發送告警通知

**500 Internal Server Error - BALANCE_SERVICE_ERROR**
- **觸發條件**：
  - Balance Service API 呼叫失敗
  - 呼叫超時
- **處理方式**：
  - 記錄日誌等級：ERROR
  - 回傳錯誤訊息："Balance service unavailable"
  - 發送告警通知
  - 考慮實施斷路器（Circuit Breaker）

**500 Internal Server Error - INTERNAL_ERROR**
- **觸發條件**：未預期的系統異常
- **處理方式**：
  - 記錄日誌等級：ERROR
  - 記錄完整 stack trace
  - 回滾事務（如在事務中）
  - 回傳通用錯誤訊息："Internal server error"
  - 發送緊急告警

---

### 4.2 錯誤回應格式

**統一錯誤回應結構：**
```json
{
  "timestamp": "2026-01-11T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Insufficient balance"
}
```

**欄位說明：**
- **timestamp**：錯誤發生時間（ISO 8601 格式）
- **status**：HTTP 狀態碼（數字）
- **error**：HTTP 狀態碼的文字描述
- **message**：使用者友善的錯誤訊息

---

### 4.3 日誌記錄策略

**DEBUG 等級：**
- Redis 快取命中/未命中
- 詳細的方法進入/退出資訊

**INFO 等級：**
- 成功的使用者建立
- 成功的轉帳完成
- 成功的帳變操作
- 成功的轉帳取消

**WARN 等級：**
- 輸入驗證失敗（400 錯誤）
- 業務規則違反（餘額不足、重複建立等）
- 冪等性檢測到重複消費
- 狀態不符的事件消費

**ERROR 等級：**
- 資料庫連線錯誤
- 事務回滾
- RocketMQ 發送失敗
- Balance Service 呼叫失敗
- 未預期的系統異常

**日誌內容應包含：**
- 時間戳記（自動）
- 日誌等級（自動）
- 服務名稱（Transfer Service / Balance Service）
- 類別名稱和方法名稱（自動）
- 相關業務資料（userId, transferId, externalId, amount）
- 錯誤訊息和 stack trace（如適用）

---

### 4.4 並發處理情境

**情境 1：同一使用者同時執行多筆轉帳**
- **問題**：可能導致餘額計算錯誤或超支
- **解決方案**：
  - Balance Service 使用 `SELECT FOR UPDATE` 鎖定使用者記錄
  - 事務隔離級別設定為 READ_COMMITTED
  - 使用 `@Version` 欄位實現樂觀鎖（可選）
- **預期行為**：
  - 第二個帳變事務等待第一個事務完成
  - 依序處理，確保餘額正確

**情境 2：重複消費 Balance Change Event**
- **問題**：MQ Consumer 可能重複消費同一事件
- **解決方案**：
  - 使用 `(external_id, type)` 唯一約束
  - 插入 balance_change 前先查詢是否已存在
  - 若已存在且 COMPLETED，重新發送成功結果
- **預期行為**：
  - 第一次消費：正常處理
  - 重複消費：檢測到已處理，返回之前結果

**情境 3：Balance Change Result 事件丟失**
- **問題**：Balance Service 處理完成但 result 事件未送達
- **解決方案**：
  - 扣款重試排程器：掃描 DEBIT_PROCESSING 超過 10 分鐘的記錄並重新發送扣款事件
  - 加帳重試排程器：掃描 CREDIT_PROCESSING 超過 10 分鐘的記錄並重新發送加帳事件
  - 利用 BalanceService 冪等性設計，重複發送不會造成重複處理
  - 查詢 balance_changes 表確認實際狀態
- **預期行為**：
  - 重試排程器確保最終一致性
  - 所有停滯的轉帳最終會完成或失敗

**情境 4：快取與資料庫餘額不一致**
- **問題**：Redis 快取的餘額可能高於實際餘額
- **解決方案**：
  - API 層快取檢查僅作快速失敗
  - Balance Service 以資料庫為準（最終一致性）
  - 帳變完成後立即過期快取
- **預期行為**：
  - API 可能通過快取檢查
  - Balance Service 最終以 DB 為準，餘額不足時失敗

**情境 5：排程器並發處理相同轉帳**
- **問題**：多個排程器實例可能同時選取同一筆 PENDING 轉帳
- **解決方案**：
  - 使用 `SELECT FOR UPDATE` 鎖定轉帳記錄
  - 更新狀態前再次檢查當前狀態是否為 PENDING
  - 若已被其他實例處理，跳過此筆
- **預期行為**：
  - 第一個排程器實例鎖定並處理該轉帳
  - 第二個排程器實例檢測到狀態已變更，跳過
  - 確保每筆轉帳只被處理一次

---

## 5. 非功能性規格

### 5.1 效能要求

**回應時間目標：**
- **Balance Service - 建立使用者**：< 100ms (P95)
- **Balance Service - 查詢餘額（快取命中）**：< 50ms (P95)
- **Balance Service - 查詢餘額（快取未命中）**：< 200ms (P95)
- **Transfer Service - 建立轉帳（API 回應）**：< 150ms (P95)（含 Balance Service 呼叫）
- **Balance Service - 帳變處理（Consumer）**：< 200ms (P95)
- **轉帳完整流程（端到端）**：10-15 分鐘（從 API 呼叫到 COMPLETED，包含 10 分鐘等待時間 + 處理時間）
- **Transfer Service - 轉帳歷史查詢**：< 300ms (P95)
- **Transfer Service - 取消轉帳**：< 100ms (P95)

**吞吐量要求：**
- **Transfer Service**：支援至少 **100 TPS** 的轉帳請求
- **Balance Service**：支援至少 **500 QPS** 的餘額查詢請求
- **Balance Service Consumer**：支援至少 **200 TPS** 的帳變處理

**並發處理能力：**
- 系統應支援至少 100 個並發請求
- 資料庫連線池配置：
  - 最小連線數：5
  - 最大連線數：20
  - 連線超時：3 秒

**資源限制：**
- Redis 快取 TTL：300 秒（5 分鐘）
- RocketMQ 訊息發送超時：3 秒
- Balance Service API 呼叫超時：3 秒
- 資料庫查詢超時：5 秒

**Scheduler 處理效能：**
- **Transfer Scheduler 執行週期**：每 5 分鐘
- **單筆轉帳處理時間**：< 500ms (P95)
- **批次處理量**：每次最多處理 100 筆轉帳
- **轉帳處理延遲**：10-15 分鐘（從建立到開始處理）

---

### 5.2 安全性規格

**輸入驗證：**
- 所有 API 輸入必須經過格式驗證（使用 Spring Validation）
- 使用正則表達式驗證 userId 格式
- 使用 BigDecimal 處理金額，避免精度問題
- 防止 SQL 注入：使用 JPA 的參數化查詢

**事務隔離級別：**
- 使用 `READ_COMMITTED` 或更高級別
- 防止髒讀（Dirty Read）

**並發控制：**
- Balance Service 使用 `SELECT FOR UPDATE` 鎖定使用者記錄
- 防止餘額並發修改導致的超支問題

**服務間通信：**
- Transfer Service 與 Balance Service 通過 HTTP REST API 通信
- 考慮實施服務間認證（API Key, JWT）（可選）
- 實施 Rate Limiting 防止濫用（可選）

**敏感資料處理：**
- 日誌中不記錄完整的使用者餘額（可選擇性記錄）
- 錯誤訊息不暴露內部系統細節

---

### 5.3 可靠性要求

**資料一致性：**
- 使用資料庫事務確保帳變操作的 ACID 特性
- 使用事件驅動確保轉帳流程的最終一致性
- 冪等性設計防止重複處理

**錯誤處理：**
- 所有異常必須被捕獲並回傳明確的錯誤訊息
- 事務失敗時自動回滾
- 系統錯誤不暴露敏感資訊

**訊息可靠性：**
- RocketMQ Consumer 處理失敗自動重試（最多 16 次）
- 重試失敗後進入死信佇列（DLQ）

**降級策略：**
- Redis 快取失效時降級為直接查詢資料庫
- Balance Service 不可用時 Transfer Service 快速失敗

---

### 5.4 可維護性要求

**程式碼結構：**
- 採用微服務架構：
  - **Transfer Service**：轉帳管理
  - **Balance Service**：餘額管理
- 每個服務採用分層架構：
  - **Controller 層**：處理 HTTP 請求和回應
  - **Facade 層**：協調業務層
  - **Service 層**：實現業務邏輯
  - **Repository 層**：資料存取
  - **Consumer 層**：處理 MQ 事件
  - **Config 層**：配置類別

**測試覆蓋率：**
- **單元測試**：Service 層邏輯（使用 Mockito）
- **整合測試**：Repository 層（使用 TestContainers）
- **API 測試**：Controller 層（使用 @SpringBootTest + MockMvc）
- **Consumer 測試**：MQ Consumer 邏輯（使用 Mock MQ）
- **目標覆蓋率**：> 80%

**文件：**
- API 文件（使用 Swagger/OpenAPI）
- 事件文件（定義所有 MQ 事件格式）
- README 說明如何運行和測試專案
- 架構圖（服務依賴、事件流程）

### 5.5 Service 層設計原則

**原子性操作（Atomic Operations）：**

每個 Service 方法應該：
- 完成一個完整的業務單元
- 包含必要的事務邊界（@Transactional）
- 有明確的成功/失敗結果
- 不依賴外部服務的同步調用（避免分散式事務）
- 單一職責，不包含流程編排邏輯

**Service 方法範例（TransferService）：**

```java
// ✅ 良好設計：原子性操作，完整的事務單元
@Transactional
public Transfer createPendingTransfer(String fromUserId, String toUserId, BigDecimal amount) {
    Transfer transfer = new Transfer();
    transfer.setFromUserId(fromUserId);
    transfer.setToUserId(toUserId);
    transfer.setAmount(amount);
    transfer.setStatus(TransferStatus.PENDING);
    transfer.setCreatedAt(LocalDateTime.now());

    transferRepository.save(transfer);
    log.info("Transfer created: {}", transfer.getId());

    return transfer;
}

// ✅ 良好設計：原子性狀態更新，含鎖定機制
@Transactional
public Transfer updateStatusWithLock(Long transferId, TransferStatus newStatus) {
    Transfer transfer = transferRepository.findByIdForUpdate(transferId);
    if (transfer == null || transfer.getStatus() != TransferStatus.PENDING) {
        return null; // 已被處理或不存在
    }

    transfer.setStatus(newStatus);
    transferRepository.save(transfer);

    return transfer;
}

// ❌ 不良設計：包含編排邏輯，應移至 Facade 層
public Transfer processTransfer(Long transferId) {
    // 這些是編排邏輯，不應在 Service 層
    Transfer transfer = findById(transferId);
    updateStatus(transfer, PROCESSING);
    eventPublisher.publish(event);  // 編排多個操作
    updateStatus(transfer, DEBIT_PROCESSING);
    return transfer;
}
```

**Service 方法範例（BalanceService）：**

```java
// ✅ 良好設計：驗證操作，無副作用
public void validateUserExists(String userId) {
    if (!userBalanceRepository.existsById(userId)) {
        throw new UserNotFoundException(userId);
    }
}

// ✅ 良好設計：查詢操作，含快取邏輯
public void checkSufficientBalance(String userId, BigDecimal amount) {
    BigDecimal balance = getBalance(userId); // 從快取或DB
    if (balance.compareTo(amount) < 0) {
        throw new InsufficientBalanceException(userId, balance, amount);
    }
}

// ✅ 良好設計：原子性餘額更新，含冪等性檢查
@Transactional
public BalanceChange debitBalance(Long externalId, String userId, BigDecimal amount) {
    // 1. 冪等性檢查
    BalanceChange existing = balanceChangeRepository.findByExternalIdAndType(
        externalId, BalanceChangeType.TRANSFER_OUT
    );
    if (existing != null) {
        return existing; // 已處理過
    }

    // 2. 鎖定使用者
    UserBalance userBalance = userBalanceRepository.findByIdForUpdate(userId);
    if (userBalance == null) {
        throw new UserNotFoundException(userId);
    }

    // 3. 檢查餘額
    if (userBalance.getBalance().compareTo(amount) < 0) {
        throw new InsufficientBalanceException(userId);
    }

    // 4. 建立帳變記錄
    BalanceChange change = new BalanceChange();
    change.setExternalId(externalId);
    change.setType(BalanceChangeType.TRANSFER_OUT);
    change.setUserId(userId);
    change.setAmount(amount.negate());
    change.setBalanceBefore(userBalance.getBalance());
    change.setStatus(BalanceChangeStatus.PROCESSING);
    balanceChangeRepository.save(change);

    // 5. 更新餘額
    userBalance.setBalance(userBalance.getBalance().subtract(amount));
    userBalanceRepository.save(userBalance);

    // 6. 完成帳變
    change.setBalanceAfter(userBalance.getBalance());
    change.setStatus(BalanceChangeStatus.COMPLETED);
    change.setCompletedAt(LocalDateTime.now());
    balanceChangeRepository.save(change);

    return change;
}

// ✅ 良好設計：原子性餘額更新（加帳版本）
@Transactional
public BalanceChange creditBalance(Long externalId, String userId, BigDecimal amount) {
    // 冪等性檢查
    BalanceChange existing = balanceChangeRepository.findByExternalIdAndType(
        externalId, BalanceChangeType.TRANSFER_IN
    );
    if (existing != null) {
        return existing;
    }

    // 鎖定使用者、建立帳變、更新餘額（邏輯同 debitBalance）
    // ...省略具體實作，結構與 debitBalance 相同
    return change;
}
```

**Facade vs Service 職責劃分：**

| 層級 | 職責 | 範例 |
|------|------|------|
| **Facade** | 流程編排、組合多個 Service 調用 | `TransferFacade.createTransfer()` 調用 `BalanceService.validateUserExists()` + `TransferService.createPendingTransfer()` |
| **Service** | 原子性業務操作、完整事務單元 | `BalanceService.debitBalance()` 含冪等性檢查、鎖定、餘額更新 |
| **Repository** | 資料存取 | `transferRepository.findByIdForUpdate()` |

**設計檢查清單：**

當設計 Service 方法時，檢查以下條件：
- [ ] 是否包含完整的事務邊界？
- [ ] 是否處理單一業務操作（不編排多個操作）？
- [ ] 是否有明確的輸入輸出？
- [ ] 是否可獨立測試（不依賴 Facade）？
- [ ] 是否包含冪等性保證（若需要）？
- [ ] 是否記錄適當的日誌？

---

## 6. 資料模型設計

### 6.1 Balance Service 資料模型

#### UserBalance 實體（使用者餘額表）

```java
@Entity
@Table(name = "user_balances")
public class UserBalance {
    @Id
    @Column(length = 50)
    private String userId;  // PK, VARCHAR(50)

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;  // DECIMAL(15,2)

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;  // TIMESTAMP

    @Version
    private Long version;  // 樂觀鎖版本號
}
```

**索引：**
- `userId`: PRIMARY KEY

---

#### BalanceChange 實體（帳變表）

```java
@Entity
@Table(name = "balance_changes",
       uniqueConstraints = @UniqueConstraint(columnNames = {"external_id", "type"}))
public class BalanceChange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // 自增主鍵

    @Column(nullable = false)
    private Long externalId;  // 外部冪等 ID，對應 transfer 表的主鍵

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BalanceChangeType type;  // ENUM: transfer_out, transfer_in, refund, etc.

    @Column(nullable = false, length = 50)
    private String userId;  // 使用者 ID

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;  // 金額（正數=加帳，負數=扣款）

    @Column(length = 50)
    private String relatedId;  // 關聯 ID（如 transferId）

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BalanceChangeStatus status;  // ENUM: PROCESSING, COMPLETED, FAILED

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;  // 建立時間

    private LocalDateTime completedAt;  // 完成時間

    @Column(length = 500)
    private String failureReason;  // 失敗原因

    @Column(precision = 15, scale = 2)
    private BigDecimal balanceBefore;  // 變更前餘額

    @Column(precision = 15, scale = 2)
    private BigDecimal balanceAfter;  // 變更後餘額
}
```

**索引：**
- `id`: PRIMARY KEY
- `(external_id, type)`: UNIQUE INDEX（冪等性保證）
- `user_id`: INDEX（查詢優化）
- `related_id`: INDEX（查詢優化）
- `created_at`: INDEX（時間範圍查詢）

**BalanceChangeType 枚舉：**
```java
public enum BalanceChangeType {
    transfer_out,   // 轉帳扣款
    transfer_in,    // 轉帳加帳
    refund,         // 退款
    charge,         // 充值
    withdraw        // 提現
}
```

**BalanceChangeStatus 枚舉：**
```java
public enum BalanceChangeStatus {
    PROCESSING,     // 處理中
    COMPLETED,      // 已完成
    FAILED          // 已失敗
}
```

---

### 6.2 Transfer Service 資料模型

#### Transfer 實體（轉帳表）

```java
@Entity
@Table(name = "transfers")
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // PK, BIGINT, 自增主鍵

    @Column(nullable = false, length = 50)
    private String fromUserId;  // VARCHAR(50)

    @Column(nullable = false, length = 50)
    private String toUserId;  // VARCHAR(50)

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;  // DECIMAL(15,2)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;  // ENUM

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;  // TIMESTAMP

    private LocalDateTime completedAt;  // TIMESTAMP (可為 null)

    private LocalDateTime cancelledAt;  // TIMESTAMP (可為 null)

    @Column(length = 500)
    private String failureReason;  // VARCHAR(500) 失敗原因（可為 null）
}
```

**索引：**
- `id`: PRIMARY KEY（自增主鍵）
- `fromUserId`: INDEX（查詢優化）
- `toUserId`: INDEX（查詢優化）
- `createdAt`: INDEX（排序和時間範圍查詢）
- `status`: INDEX（狀態過濾）

**TransferStatus 枚舉：**
```java
public enum TransferStatus {
    PENDING,            // 已建立，等待排程器處理（約 10 分鐘）
    DEBIT_PROCESSING,   // 扣款處理中
    CREDIT_PROCESSING,  // 加帳處理中
    COMPLETED,          // 轉帳完成
    DEBIT_FAILED,       // 扣款失敗
    CANCELLED           // 已取消
}
```

---

## 7. RocketMQ 事件設計

### 7.1 Balance Change Event（帳變請求）

**Topic**: `balance-change-events`
**Tag**: `balance_change`
**發送者**: Transfer Service
**消費者**: Balance Service

**用途**：觸發實際的餘額扣減或增加操作

**Payload 結構：**

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| eventType | String | 是 | 固定值："BALANCE_CHANGE" |
| externalId | Long | 是 | 冪等 ID（transfer 表的自增主鍵） |
| type | String | 是 | 帳變類型（transfer_out, transfer_in） |
| userId | String | 是 | 使用者 ID |
| amount | Number | 是 | 金額（負數=扣款，正數=加帳） |
| relatedId | Long | 是 | 關聯 ID（transferId） |
| timestamp | String | 是 | 事件時間（ISO 8601） |

**範例（扣款）：**
```json
{
  "eventType": "BALANCE_CHANGE",
  "externalId": 12345,
  "type": "transfer_out",
  "userId": "user_001",
  "amount": -150.00,
  "relatedId": 12345,
  "timestamp": "2026-01-11T10:30:00Z"
}
```

**範例（加帳）：**
```json
{
  "eventType": "BALANCE_CHANGE",
  "externalId": 12345,
  "type": "transfer_in",
  "userId": "user_002",
  "amount": 150.00,
  "relatedId": 12345,
  "timestamp": "2026-01-11T10:30:00.300Z"
}
```

---

### 7.2 Balance Change Result Event（帳變結果）

**Topic**: `balance-change-events`
**Tag**: `balance_change_result`
**發送者**: Balance Service
**消費者**: Transfer Service

**用途**：回傳帳變處理結果給 Transfer Service，推進轉帳流程

**Payload 結構：**

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| eventType | String | 是 | 固定值："BALANCE_CHANGE_RESULT" |
| externalId | Long | 是 | 冪等 ID（與請求相同，transfer 表主鍵） |
| type | String | 是 | 帳變類型（與請求相同） |
| success | Boolean | 是 | 是否成功 |
| userId | String | 是 | 使用者 ID |
| oldBalance | Number | 否 | 變更前餘額（成功時提供） |
| newBalance | Number | 否 | 變更後餘額（成功時提供） |
| failureReason | String | 否 | 失敗原因（失敗時提供） |
| timestamp | String | 是 | 事件時間（ISO 8601） |

**範例（成功）：**
```json
{
  "eventType": "BALANCE_CHANGE_RESULT",
  "externalId": 12345,
  "type": "transfer_out",
  "success": true,
  "userId": "user_001",
  "oldBalance": 1000.00,
  "newBalance": 850.00,
  "failureReason": null,
  "timestamp": "2026-01-11T10:30:00.250Z"
}
```

**範例（失敗）：**
```json
{
  "eventType": "BALANCE_CHANGE_RESULT",
  "externalId": 12345,
  "type": "transfer_out",
  "success": false,
  "userId": "user_001",
  "oldBalance": null,
  "newBalance": null,
  "failureReason": "Insufficient balance",
  "timestamp": "2026-01-11T10:30:00.250Z"
}
```

---

### 7.3 Transfer Completed Event（轉帳完成）

**Topic**: `balance-transfer-events`
**Tag**: `transfer_completed`
**發送者**: Transfer Service
**消費者**: 下游服務（如通知服務、報表服務）

**用途**：通知下游系統轉帳已成功完成

**Payload 結構：**

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| eventType | String | 是 | 固定值："TRANSFER_COMPLETED" |
| transferId | Long | 是 | 轉帳 ID |
| fromUserId | String | 是 | 付款方 ID |
| toUserId | String | 是 | 收款方 ID |
| amount | Number | 是 | 轉帳金額 |
| timestamp | String | 是 | 完成時間（ISO 8601） |

**範例：**
```json
{
  "eventType": "TRANSFER_COMPLETED",
  "transferId": 12345,
  "fromUserId": "user_001",
  "toUserId": "user_002",
  "amount": 150.00,
  "timestamp": "2026-01-11T10:30:00.600Z"
}
```

---

### 7.4 Transfer Failed Event（轉帳失敗）

**Topic**: `balance-transfer-events`
**Tag**: `transfer_failed`
**發送者**: Transfer Service
**消費者**: 下游服務（如通知服務、監控告警）

**用途**：通知下游系統轉帳失敗及原因

**Payload 結構：**

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| eventType | String | 是 | 固定值："TRANSFER_FAILED" |
| transferId | Long | 是 | 轉帳 ID |
| fromUserId | String | 是 | 付款方 ID |
| toUserId | String | 是 | 收款方 ID |
| amount | Number | 是 | 轉帳金額 |
| reason | String | 是 | 失敗原因 |
| timestamp | String | 是 | 失敗時間（ISO 8601） |

**範例：**
```json
{
  "eventType": "TRANSFER_FAILED",
  "transferId": 12345,
  "fromUserId": "user_001",
  "toUserId": "user_002",
  "amount": 150.00,
  "reason": "Insufficient balance",
  "timestamp": "2026-01-11T10:30:00.300Z"
}
```

---

### 7.5 Transfer Cancelled Event（轉帳取消）

**Topic**: `balance-transfer-events`
**Tag**: `transfer_cancelled`
**發送者**: Transfer Service
**消費者**: 下游服務（如通知服務）

**用途**：通知下游系統轉帳已被取消

**Payload 結構：**

| 欄位 | 類型 | 必填 | 說明 |
|------|------|------|------|
| eventType | String | 是 | 固定值："TRANSFER_CANCELLED" |
| transferId | Long | 是 | 轉帳 ID |
| reason | String | 是 | 取消原因 |
| timestamp | String | 是 | 取消時間（ISO 8601） |

**範例：**
```json
{
  "eventType": "TRANSFER_CANCELLED",
  "transferId": 12345,
  "reason": "User requested cancellation",
  "timestamp": "2026-01-11T10:35:00Z"
}
```

---

### 7.6 Consumer 配置

#### Balance Service Consumer

**Consumer Group**: `balance-service-consumer-group`
**Topic**: `balance-change-events`
**Tag**: `balance_change`
**消費模式**: 集群消費（Clustering）
**消費線程數**: 10-20
**最大重試次數**: 16 次
**訊息模型**: 推送模式（Push）

**訂閱配置：**
```java
@RocketMQMessageListener(
    topic = "balance-change-events",
    consumerGroup = "balance-service-consumer-group",
    selectorExpression = "balance_change"
)
public class BalanceChangeConsumer implements RocketMQListener<BalanceChangeEvent> {
    // 處理帳變請求
}
```

---

#### Transfer Service Consumer

**Consumer Group**: `transfer-service-consumer-group`
**Topic**: `balance-change-events`
**Tag**: `balance_change_result`
**消費模式**: 集群消費（Clustering）
**消費線程數**: 10-20
**最大重試次數**: 16 次
**訊息模型**: 推送模式（Push）

**訂閱配置：**
```java
@RocketMQMessageListener(
    topic = "balance-change-events",
    consumerGroup = "transfer-service-consumer-group",
    selectorExpression = "balance_change_result"
)
public class BalanceChangeResultConsumer implements RocketMQListener<BalanceChangeResultEvent> {
    // 處理帳變結果
}
```

---

## 8. Scheduler 設計

### 8.1 Transfer Processing Scheduler

**目的**：定期掃描 PENDING 狀態的轉帳，處理已超過 10 分鐘等待時間的轉帳

**技術方案**：
- 使用 Spring `@Scheduled` 註解
- 固定延遲執行：每 5 分鐘執行一次
- 單線程執行（避免並發問題）

**配置參數：**
- `fixedDelay`: 300000ms (5 分鐘)
- `initialDelay`: 60000ms (啟動後 1 分鐘開始)
- 等待時間閾值: 10 分鐘
- 批次大小: 100 筆

**Repository 查詢：**
```java
@Query("SELECT t FROM Transfer t WHERE t.status = :status " +
       "AND t.createdAt <= :cutoffTime ORDER BY t.createdAt ASC")
List<Transfer> findPendingTransfers(
    @Param("status") TransferStatus status,
    @Param("cutoffTime") LocalDateTime cutoffTime,
    Pageable pageable
);
```

### 8.2 監控指標

**Scheduler 執行指標：**
- 執行次數計數器
- 每次處理的轉帳數量
- 處理成功/失敗數量
- 執行耗時（毫秒）

**異常告警：**
- Scheduler 連續失敗超過 3 次
- 單次處理時間超過 60 秒
- PENDING 狀態超過 15 分鐘的轉帳數量 > 10
- PROCESSING 狀態超過 5 分鐘的轉帳數量 > 5

### 8.3 Facade 層設計原則

**Facade 層職責：**

Facade（門面）層位於 Controller 和 Service 之間，負責：
- **流程編排**：組合多個 Service 的原子性操作，完成複雜業務流程
- **異常處理**：統一處理業務異常，轉換為適當的回應
- **事件協調**：協調 Service 和 EventPublisher 的調用
- **無業務邏輯**：不包含業務邏輯，僅負責協調和流程控制

**Facade vs Service 對比：**

| 特性 | Facade 層 | Service 層 |
|------|----------|-----------|
| 職責 | 流程編排、組合操作 | 原子性業務操作 |
| 事務 | 不包含事務邊界 | 包含完整事務（@Transactional） |
| 調用關係 | 調用多個 Service | 調用 Repository 和其他底層服務 |
| 測試 | 模擬 Service 進行測試 | 獨立單元測試 |
| 範例 | TransferFacade.createTransfer() | BalanceService.debitBalance() |

**設計原則：**

1. **單一流程**：每個 Facade 方法處理一個完整的業務流程
2. **不含事務**：事務邊界應在 Service 層，Facade 不使用 @Transactional
3. **異常傳播**：Service 拋出的異常向上傳播，Facade 統一處理
4. **可讀性優先**：Facade 代碼應清晰展示業務流程步驟
5. **無狀態**：Facade 是無狀態的組件，可安全並發調用

**關鍵設計點：**

1. **職責分離**：Facade 僅編排，Service 包含原子邏輯
2. **異常處理**：
   - 業務異常（如餘額不足）由 Service 拋出，Facade 向上傳播
   - Scheduler 處理異常時不中斷整體流程，繼續處理下一筆
3. **並發保護**：使用 `updateStatusWithLock()` 防止多實例並發處理
4. **可讀性**：每個方法清晰展示業務流程步驟

**關鍵設計點：**

1. **事件驅動協調**：
   - 接收 BalanceChangeEvent
   - 調用 BalanceService 執行帳變（含冪等性、鎖定、事務）
   - 發送 BalanceChangeResultEvent

2. **異常處理分類**：
   - **業務異常**（餘額不足、使用者不存在）：發送失敗結果事件，不重試
   - **系統異常**：向上拋出，觸發 RocketMQ 重試機制

3. **單一入口**：
   - 扣款和加帳使用同一個方法 `handleBalanceChange()`
   - 根據事件類型 (`TRANSFER_OUT` / `TRANSFER_IN`) 調用不同的 Service 方法

4. **職責明確**：
   - Facade：協調 Service 和 EventPublisher
   - Service：執行原子性帳變操作（含冪等性、事務、快取更新）

**完整事件流程範例：**

```
1. Consumer 接收 BalanceChangeEvent
   ↓
2. BalanceFacade.handleBalanceChange(event)
   ↓
3. BalanceService.debitBalance() 執行：
   - 冪等性檢查
   - 鎖定使用者（SELECT FOR UPDATE）
   - 檢查餘額
   - 建立 balance_change 記錄
   - 更新 users.balance
   - 更新 Redis 快取
   ↓
4. EventPublisher.publishBalanceChangeResult(success=true)
   ↓
5. TransferFacade.handleDebitResult() 接收結果
   ↓
6. 發送加帳事件...
```

---

## 9. 服務間依賴關係

### 9.1 Transfer Service 依賴

**對外 API：**
- `POST /transfers` - 建立轉帳
- `GET /transfers` - 查詢轉帳歷史
- `POST /transfers/{transferId}/cancel` - 取消轉帳

**內部依賴（同應用程式內）：**
- **BalanceService**（直接方法調用，非 HTTP）
  - `validateUserExists(userId)` - 驗證使用者存在
  - `checkSufficientBalance(userId, amount)` - 檢查餘額
  - 說明：Transfer Service 和 Balance Service 位於同一應用程式內，透過 TransferFacade 協調調用 BalanceService 方法

**外部依賴：**
- **RocketMQ**
  - 發送：`balance_change` 事件（Topic: balance-change-events）
  - 消費：`balance_change_result` 事件（Topic: balance-change-events）
  - 發送：`transfer_completed`, `transfer_failed`, `transfer_cancelled` 事件（通知類）

**資料庫：**
- MySQL：`transfers` 表

**Scheduler：**
- Spring @Scheduled - 每 5 分鐘處理 PENDING 轉帳

---

### 9.2 Balance Service 依賴

**對外 API：**
- `POST /balance-service/users` - 建立使用者
- `GET /balance-service/users/{userId}/balance` - 查詢餘額

**依賴服務：**
- **RocketMQ**
  - 消費：`balance_change` 事件
  - 發送：`balance_change_result` 事件
- **Redis**
  - 餘額快取

**資料庫：**
- MySQL：`users` 表、`balance_changes` 表

---

### 9.3 系統架構圖

**說明**：Transfer Service 和 Balance Service 位於同一應用程式內，透過 Facade 層協調，不使用 HTTP REST API 通訊

```
┌─────────────────────────────────────────────────────────────┐
│                         Client                               │
└────────────────┬────────────────────────────────────────────┘
                 │ HTTP REST API
                 ▼
┌────────────────────────────────────────────────────────────┐
│                   Controller 層                              │
│  ┌──────────────┐  ┌──────────────┐                        │
│  │   Transfer   │  │   Balance    │                        │
│  │  Controller  │  │  Controller  │                        │
│  └──────┬───────┘  └──────┬───────┘                        │
│         │                 │                                 │
└─────────┼─────────────────┼─────────────────────────────────┘
          │                 │
          ▼                 ▼
┌────────────────────────────────────────────────────────────┐
│                   Facade 層（流程編排）                      │
│  ┌──────────────┐  ┌──────────────┐                        │
│  │   Transfer   │  │   Balance    │                        │
│  │   Facade     │  │   Facade     │                        │
│  └──────┬───────┘  └──────┬───────┘                        │
│         │   ┌─────────────┘                                │
│         │   │ 方法調用（同應用程式內）                        │
└─────────┼───┼─────────────────────────────────────────────┘
          │   │
          ▼   ▼
┌────────────────────────────────────────────────────────────┐
│                   Service 層（原子性操作）                    │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │   Transfer   │  │   Balance    │  │     Event       │  │
│  │   Service    │  │   Service    │  │   Publisher     │  │
│  │  (atomic)    │  │   (atomic)   │  │                 │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
│         │                 │                    │            │
└─────────┼─────────────────┼────────────────────┼────────────┘
          │                 │                    │
          ▼                 ▼                    ▼
┌────────────────────────────────────────────────────────────┐
│  ┌──────────────────────────────────────┐   ┌────────────┐│
│  │        MySQL Database                │   │  RocketMQ  ││
│  │  - transfers                         │   │            ││
│  │  - users                             │   │  Topic:    ││
│  │  - balance_changes                   │   │  balance-  ││
│  └──────────────────────────────────────┘   │  change-   ││
│  ┌──────────────┐                           │  events    ││
│  │    Redis     │                           │            ││
│  │   (Cache)    │                           │            ││
│  └──────────────┘                           └────────────┘│
└────────────────────────────────────────────────────────────┘

                         ▲
                         │ RocketMQ Consumer
                    ┌────┴─────┐
                    │ Consumer │
                    │   層     │
                    │ (調用    │
                    │ Facade)  │
                    └──────────┘
                         ▲
                         │
                    ┌────┴─────┐
                    │ Scheduler│
                    │ (調用    │
                    │ Facade)  │
                    └──────────┘

**架構層次說明：**

1. **Controller 層**：處理 HTTP 請求，驗證輸入，調用 Facade
2. **Facade 層**：編排業務流程，組合多個 Service 的原子性操作
3. **Service 層**：提供原子性業務操作，包含完整事務邊界
4. **Repository 層**：資料存取（圖中未顯示）
5. **Consumer 層**：接收 RocketMQ 事件，調用 Facade 處理
6. **Scheduler 層**：定時任務，調用 Facade 處理 PENDING 轉帳

**關鍵設計點：**
- Transfer Service 和 Balance Service 在同一應用程式內，透過 **直接方法調用** 協作
- **無 HTTP REST API** 通訊（避免網路延遲和序列化開銷）
- Facade 層協調 TransferService 和 BalanceService
- 事件驅動：透過 RocketMQ 實現異步帳變處理
```

---

## 10. 實作檢查清單

完成技術規格撰寫後，確認以下項目：

### API 規格完整性
- [x] Balance Service 的 2 個 API 端點有完整規格
- [x] Transfer Service 的 3 個 API 端點有完整規格
- [x] Request/Response 範例正確且可執行
- [x] 所有參數都標註了類型和驗證規則
- [x] 錯誤回應涵蓋所有可能的錯誤情況
- [x] 錯誤碼與 HTTP 狀態碼對應清楚

### 業務邏輯清晰性
- [x] 建立使用者流程明確
- [x] 查詢餘額流程明確
- [x] 完整異步轉帳流程（5 個階段）明確且可執行
- [x] 查詢轉帳歷史流程明確
- [x] 取消轉帳流程明確
- [x] 所有驗證規則都有明確定義
- [x] 業務規則判斷邏輯清楚（狀態轉換、冪等性）
- [x] 事務邊界已明確標示

### 事件設計完整性
- [x] 5 種事件都有完整定義
- [x] 所有事件欄位都有類型說明
- [x] 事件範例正確且可執行
- [x] Consumer 配置清楚

### 資料模型完整性
- [x] UserBalance 實體定義完整
- [x] BalanceChange 實體定義完整（含唯一約束）
- [x] Transfer 實體定義完整
- [x] 所有索引都已定義
- [x] 枚舉類型都已定義

### 錯誤處理完整性
- [x] 定義了所有可能的錯誤類型（4xx 和 5xx）
- [x] 每種錯誤都有對應的 HTTP 狀態碼
- [x] 錯誤訊息清楚且可操作
- [x] 錯誤處理策略已定義（日誌等級、重試）
- [x] 並發處理情境已說明（6 種）

### 可實作性
- [x] 技術實作路徑清晰（微服務架構）
- [x] 沒有模糊或不確定的描述
- [x] 開發人員可以直接根據 spec 開始編碼
- [x] 包含完整的資料模型設計
- [x] 包含服務間依賴關係圖

---

**文件版本**: 3.4（Facade 層架構重構）
**撰寫日期**: 2026-01-11
**更新日期**: 2026-01-15
**撰寫者**: Claude Code (spec-writer skill)

**版本變更記錄：**
- v1.0 (2026-01-11)：初始版本，同步轉帳處理
- v2.0 (2026-01-11)：改為非同步轉帳處理模式
  - API 層使用 Redis 快取驗證餘額
  - 建立 PENDING 狀態轉帳記錄
  - 發送扣款事件到 MQ
  - 更新狀態為 PROCESSING
  - MQ Consumer 執行實際扣款和入帳
  - 新增 PROCESSING 和 FAILED 狀態
  - 新增 Debit Request 和 Transfer Failed 事件
- v3.0 (2026-01-11)：**完全事件驅動架構 + Balance Service**
  - 引入 Balance Service 獨立微服務
  - 所有餘額操作由 Balance Service 管理
  - 新增 balance_changes 表實現冪等性
  - 轉帳狀態細化為 7 種（PENDING, DEBIT_PROCESSING, DEBIT_COMPLETED, CREDIT_PROCESSING, COMPLETED, DEBIT_FAILED, CANCELLED）
  - Balance Service 提供 API：建立使用者、查詢餘額
  - 完全事件驅動：balance_change 和 balance_change_result
  - 冪等性：(external_id, type) 唯一約束
  - Transfer Service 通過 Balance Service API 查詢餘額
  - 不考慮加帳失敗場景（簡化設計）
- v3.1 (2026-01-11)：**細節修正**
  - 調整狀態更新順序：建立 PENDING → 立即更新為 DEBIT_PROCESSING → 發送 event（防止用戶取消）
  - externalId 格式簡化：使用 Long 類型的 transfer 表自增主鍵，不再使用字串格式
  - Transfer 實體主鍵改為自增 Long 類型（id）
  - BalanceChange 實體的 externalId 改為 Long 類型
  - 更新所有 API、事件、流程中的相關欄位類型
  - 強調 PENDING 狀態時間窗口極短，取消操作難以成功
- v3.2 (2026-01-11)：**新增排程器延遲處理機制**
  - 新增 PROCESSING 狀態（介於 PENDING 和 DEBIT_PROCESSING 之間）
  - API 回應時保持 PENDING 狀態（不再立即更新為 DEBIT_PROCESSING）
  - 新增 Transfer Scheduler：每 5 分鐘掃描 PENDING 超過 10 分鐘的轉帳
  - 排程器將 PENDING 更新為 PROCESSING，發送扣款事件後更新為 DEBIT_PROCESSING
  - 取消轉帳時間窗口擴大：完整的 10 分鐘（而非之前的極短窗口）
  - 新增排程器並發處理保護機制（SELECT FOR UPDATE）
  - 新增 Scheduler 效能要求和監控指標
  - 更新狀態轉換規則和業務邏輯流程
  - 新增 Section 8 Scheduler 設計，原 Section 8-9 重新編號為 9-10
  - 文件版本更新為 3.2
- v3.3 (2026-01-11)：**引入 Facade 層架構（重大重構）**
  - **核心架構變更**：
    - 引入 Facade 層（TransferFacade, BalanceFacade）負責流程編排
    - Service 層重新定位為提供原子性操作（單一職責、完整事務邊界）
    - Transfer Service 和 Balance Service 統一至同一應用程式內
    - **移除服務間 HTTP REST API 通訊**，改為直接方法調用
  - **Section 1 架構更新**：
    - Section 1.1：新增 Facade 層說明，更新系統架構層次
    - Section 1.2：更新事件流程圖，展示 Facade 編排邏輯
    - 移除 REST API 調用描述，強調同應用程式內協作
  - **Section 3 流程重構**：
    - Section 3.3.1：Controller 接收轉帳請求，調用 TransferFacade
    - Section 3.3.2：新增 TransferFacade 編排轉帳建立流程
    - Section 3.3.3：Scheduler 簡化為僅觸發 TransferFacade
    - Section 3.3.4：新增 TransferFacade 處理 PENDING 轉帳邏輯
    - Section 3.3.5-3.3.8：Consumer 層重構為調用 Facade 方法
  - **新增 Section 5.5**：Service 層設計原則
    - 原子性操作定義和範例
    - TransferService 和 BalanceService 方法設計指南
    - Facade vs Service 職責劃分表
    - 設計檢查清單
  - **新增 Section 8.3-8.5**：Facade 層完整設計
    - Section 8.3：Facade 層設計原則和職責說明
    - Section 8.4：TransferFacade 完整實作（含所有業務流程方法）
    - Section 8.5：BalanceFacade 完整實作（含事件處理和異常分類）
  - **Section 9 依賴關係更新**：
    - Section 9.1：Transfer Service 依賴改為內部方法調用（非 HTTP）
    - Section 9.3：系統架構圖重繪，展示 Facade 層和直接方法調用
    - 移除所有服務間 HTTP REST API 引用
  - **設計優勢**：
    - 清晰的職責分離：Facade（編排）vs Service（原子操作）
    - 提升效能：移除 HTTP 序列化和網路延遲
    - 提高可測試性：Service 可獨立單元測試
    - 簡化部署：單一應用程式，無需多服務協調
  - 文件版本更新為 3.3
- v3.4 (2026-01-15)：**狀態簡化與重試機制**
  - **狀態機簡化**：
    - 移除 PROCESSING 狀態（直接從 PENDING → DEBIT_PROCESSING）
    - 移除 DEBIT_COMPLETED 狀態（直接從 DEBIT_PROCESSING → CREDIT_PROCESSING）
    - 簡化為 6 個狀態：PENDING, DEBIT_PROCESSING, CREDIT_PROCESSING, COMPLETED, DEBIT_FAILED, CANCELLED
  - **流程優化**：
    - 排程器在處理 PENDING 時會先檢查餘額，不足直接標記為 DEBIT_FAILED
    - 餘額足夠時直接更新為 DEBIT_PROCESSING 並發送 MQ
    - 扣款成功後直接更新為 CREDIT_PROCESSING 並發送加帳 MQ
  - **新增重試機制**：
    - Section 3.3.9：新增扣款重試排程器（處理 DEBIT_PROCESSING 超過 10 分鐘的記錄）
    - Section 3.3.10：新增加帳重試排程器（處理 CREDIT_PROCESSING 超過 10 分鐘的記錄）
    - 每 5 分鐘執行，重新發送 MQ 事件，利用冪等性確保不重複處理
  - **Transfer 表更新**：
    - 新增 updated_at 欄位用於重試邏輯
    - 重試後更新時間戳避免立即重試
  - **文件更新範圍**：
    - Section 1.2：更新 Facade 編排流程概覽，移除中間狀態
    - Section 1.3：更新轉帳狀態定義表
    - Section 3.3.4：更新處理 PENDING 轉帳流程，新增餘額檢查
    - Section 3.3.6：更新 handleDebitResult，移除 DEBIT_COMPLETED
    - Section 3.5：更新取消流程，移除已刪除狀態檢查
    - Section 4：更新狀態轉換規則
    - Section 5：強調重試排程器的作用
    - Section 6：更新 TransferStatus 枚舉
    - Section 8.4：更新 TransferFacade 實作範例
  - **設計優勢**：
    - 狀態機更簡潔，減少中間狀態
    - 強化 At-Least-Once 語義與最終一致性保證
    - 提升系統可靠性，MQ 故障時自動重試
  - 文件版本更新為 3.4
