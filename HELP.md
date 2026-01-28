# API Testing Guide with Curl Examples

This guide provides curl command examples for testing the Balance Transfer Service API endpoints.

## Prerequisites

Before running these examples, ensure:

1. **Docker infrastructure is running:**
   ```bash
   docker-compose up -d
   docker ps  # Verify MySQL, Redis, RocketMQ are running
   ```

2. **Spring Boot application is running:**
   ```bash
   # Start with local profile (faster 5-second scheduled jobs)
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

   # Or with default profile (5-minute scheduled jobs)
   ./mvnw spring-boot:run

   # Verify application is healthy
   curl http://localhost:8080/actuator/health
   ```

3. **Application base URL:** `http://localhost:8080`

## UserController Endpoints

### 1. Create User

Creates a new user with an initial balance.

**Endpoint:** `POST /users`

**Curl Example:**
```bash
curl -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "alice",
    "initialBalance": 1000.00
  }'
```

**Expected Response (201 Created):**
```json
{
  "userId": "alice",
  "balance": "1000.00",
  "createdAt": "2026-01-28T21:00:00",
  "version": 0
}
```

**Validation Rules:**
- `userId`: 3-50 characters, required, must be unique
- `initialBalance`: >= 0, required

**Example - Create another user:**
```bash
curl -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "bob",
    "initialBalance": 500.00
  }'
```

---

### 2. Get User Balance

Retrieves the current balance for a specific user. Results are cached in Redis for 5 minutes.

**Endpoint:** `GET /users/{userId}/balance`

**Curl Example:**
```bash
curl -X GET http://localhost:8080/users/alice/balance
```

**Expected Response (200 OK):**
```json
{
  "userId": "alice",
  "balance": "1000.00"
}
```

**Notes:**
- Balance is cached in Redis with key: `balance::alice`
- Cache TTL: 5 minutes (300 seconds)
- Cache is invalidated when balance changes (debit/credit operations)

**Example - Check multiple users:**
```bash
# Check Alice's balance
curl -X GET http://localhost:8080/users/alice/balance

# Check Bob's balance
curl -X GET http://localhost:8080/users/bob/balance
```

---

## TransferController Endpoints

### 3. Create Transfer

Creates a new transfer request. Transfer starts in `PENDING` status and will be processed asynchronously by scheduled jobs.

**Endpoint:** `POST /transfers`

**Curl Example:**
```bash
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "alice",
    "toUserId": "bob",
    "amount": 300.00
  }'
```

**Expected Response (201 Created):**
```json
{
  "id": 1,
  "fromUserId": "alice",
  "toUserId": "bob",
  "amount": "300.00",
  "status": "PENDING",
  "createdAt": "2026-01-28T21:00:00"
}
```

**Validation Rules:**
- `fromUserId`: 3-50 characters, required
- `toUserId`: 3-50 characters, required
- `amount`: > 0, required
- `fromUserId` cannot equal `toUserId` (enforced by `@NotSelfTransfer` validation)

**Transfer Status Flow:**
```
PENDING → DEBIT_PROCESSING → DEBIT_COMPLETED → CREDIT_PROCESSING → COMPLETED
           ↓                                       ↓
      DEBIT_FAILED                           (retry on failure)
```

**Processing Timeline:**
- **Local profile:** Jobs run every 5 seconds
- **Production profile:** Jobs run every 5 minutes
- Transfers are processed asynchronously via RocketMQ

---

### 4. Get Transfer History

Retrieves paginated transfer history for a specific user (both sent and received transfers).

**Endpoint:** `GET /transfers?userId={userId}&page={page}&size={size}`

**Curl Example:**
```bash
# Get first page (default: 20 items)
curl -X GET "http://localhost:8080/transfers?userId=alice"

# Get with pagination
curl -X GET "http://localhost:8080/transfers?userId=alice&page=0&size=10"

# Get second page
curl -X GET "http://localhost:8080/transfers?userId=alice&page=1&size=10"
```

**Expected Response (200 OK):**
```json
{
  "transfers": [
    {
      "id": 1,
      "fromUserId": "alice",
      "toUserId": "bob",
      "amount": "300.00",
      "status": "COMPLETED",
      "createdAt": "2026-01-28T21:00:00",
      "completedAt": "2026-01-28T21:00:30",
      "cancelledAt": null,
      "failureReason": null
    }
  ],
  "pagination": {
    "currentPage": 0,
    "pageSize": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

**Query Parameters:**
- `userId`: Required, 3-50 characters
- `page`: Optional, default: 0, min: 0
- `size`: Optional, default: 20, min: 1, max: 100

---

### 5. Cancel Transfer

Cancels a transfer that is still in `PENDING` status. Only pending transfers can be cancelled.

**Endpoint:** `POST /transfers/{transferId}/cancel`

**Curl Example:**
```bash
# Cancel transfer with ID 1
curl -X POST http://localhost:8080/transfers/1/cancel
```

**Expected Response (200 OK):**
```json
{
  "id": 1,
  "fromUserId": "alice",
  "toUserId": "bob",
  "amount": "300.00",
  "status": "CANCELLED",
  "cancelledAt": "2026-01-28T21:00:15"
}
```

**Important Notes:**
- Only transfers in `PENDING` status can be cancelled
- Once processing begins (DEBIT_PROCESSING or later), cancellation is not allowed
- Returns 400 Bad Request if transfer is already processing/completed

---

## Complete Workflow Example

Here's a complete end-to-end workflow demonstrating the transfer lifecycle:

### Step 1: Create Users

```bash
# Create Alice with $1000
curl -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "alice",
    "initialBalance": 1000.00
  }'

# Create Bob with $500
curl -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "bob",
    "initialBalance": 500.00
  }'
```

### Step 2: Check Initial Balances

```bash
# Alice should have $1000
curl -X GET http://localhost:8080/users/alice/balance

# Bob should have $500
curl -X GET http://localhost:8080/users/bob/balance
```

### Step 3: Create Transfer

```bash
# Transfer $300 from Alice to Bob
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "alice",
    "toUserId": "bob",
    "amount": 300.00
  }'

# Save the transfer ID from the response
```

### Step 4: Check Transfer Status

```bash
# Get Alice's transfer history
curl -X GET "http://localhost:8080/transfers?userId=alice"

# Expected initial status: PENDING
```

### Step 5: Wait for Processing

**Local profile (5-second jobs):** Wait ~10-15 seconds
**Production profile (5-minute jobs):** Wait ~10-15 minutes

The transfer will progress through states:
```
PENDING → DEBIT_PROCESSING → CREDIT_PROCESSING → COMPLETED
```

### Step 6: Verify Final Balances

```bash
# Alice should have $700 ($1000 - $300)
curl -X GET http://localhost:8080/users/alice/balance

# Bob should have $800 ($500 + $300)
curl -X GET http://localhost:8080/users/bob/balance
```

### Step 7: Check Final Transfer Status

```bash
# Get Alice's transfer history
curl -X GET "http://localhost:8080/transfers?userId=alice"

# Expected final status: COMPLETED with completedAt timestamp
```

---

## Testing Transfer Cancellation

To test cancellation, you must cancel within the PENDING window:

```bash
# 1. Create a transfer
RESPONSE=$(curl -s -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "alice",
    "toUserId": "bob",
    "amount": 100.00
  }')

# 2. Extract transfer ID (requires jq)
TRANSFER_ID=$(echo $RESPONSE | jq -r '.id')

# 3. Cancel immediately (before processing starts)
curl -X POST http://localhost:8080/transfers/$TRANSFER_ID/cancel

# Expected: status should be CANCELLED
```

**Important:** With local profile (5-second jobs), you must cancel within ~5 seconds. With production profile, you have ~5 minutes.

---

## Error Handling Examples

### Insufficient Balance

```bash
# Try to transfer more than Alice has
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "alice",
    "toUserId": "bob",
    "amount": 99999.00
  }'

# Transfer will be created as PENDING
# After processing, status will be DEBIT_FAILED with failureReason
```

### Self-Transfer (Validation Error)

```bash
# Try to transfer to yourself
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "alice",
    "toUserId": "alice",
    "amount": 100.00
  }'

# Expected: 400 Bad Request
# Error: "Cannot transfer to yourself"
```

### Invalid Amount

```bash
# Try to transfer zero or negative amount
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{
    "fromUserId": "alice",
    "toUserId": "bob",
    "amount": 0
  }'

# Expected: 400 Bad Request
# Error: "amount must be greater than 0"
```

### Non-existent User

```bash
# Check balance for non-existent user
curl -X GET http://localhost:8080/users/nonexistent/balance

# Expected: 404 Not Found
```

---

## Tips and Troubleshooting

### Pretty-Print JSON Responses

Install `jq` for formatted output:

```bash
# macOS
brew install jq

# Then pipe curl output to jq
curl -X GET http://localhost:8080/users/alice/balance | jq
```

### Check Application Logs

Monitor Spring Boot logs to see transfer processing in real-time:

```bash
# If started with ./mvnw spring-boot:run
# Logs appear in the terminal

# Look for these log messages:
# - "Processing pending transfers (lock acquired)" - Scheduler running
# - "Transfer ... moved to DEBIT_PROCESSING" - Transfer picked up
# - "Balance change event published" - MQ event sent
```

### Verify Redis Cache

Check if balances are cached:

```bash
# List all balance cache keys
redis-cli KEYS "balance::*"

# Get cached balance for alice
redis-cli GET "balance::alice"

# Check TTL (should be ~300 seconds)
redis-cli TTL "balance::alice"

# Flush all cache (for testing)
redis-cli FLUSHDB
```

### Check RocketMQ Console

View messages in the RocketMQ web console:

```
http://localhost:8088
```

- Topic: `balance-change-topic`
- Consumer groups: `balance-change-consumer-group`, `balance-change-result-consumer-group`

### Database Queries

Check data directly in MySQL:

```bash
mysql -h 127.0.0.1 -P 3306 -u root -prootpassword homework_db

# Check users and balances
SELECT * FROM user_balances;

# Check transfers
SELECT id, from_user_id, to_user_id, amount, status, created_at, completed_at
FROM transfers
ORDER BY created_at DESC
LIMIT 10;

# Check balance change history
SELECT * FROM balance_changes ORDER BY created_at DESC LIMIT 10;
```

### Reset Test Data

Clean up all data to start fresh:

```bash
# Option 1: Via SQL
mysql -h 127.0.0.1 -P 3306 -u root -prootpassword homework_db -e "
DELETE FROM balance_changes;
DELETE FROM transfers;
DELETE FROM user_balances;
"

# Option 2: Restart containers (full reset)
docker-compose down -v
docker-compose up -d
# Wait for MySQL to initialize
sleep 30
# Restart Spring Boot application
```

### Common Issues

**Issue:** Transfer stuck in PENDING forever
**Solution:**
- Check scheduled jobs are running (look for "lock acquired" in logs)
- Verify RocketMQ is running: `docker ps | grep rocketmq`
- Check application profile (local vs production job frequency)

**Issue:** Balance not updated after transfer
**Solution:**
- Wait longer for async processing to complete
- Check transfer status in transfer history
- Look for errors in application logs
- Verify RocketMQ consumer is processing messages

**Issue:** Cache showing stale balance
**Solution:**
- Cache should auto-invalidate on balance changes
- Manually flush: `redis-cli FLUSHDB`
- Check `BalanceOperationCompletedListener` is working (logs should show "Cache evicted")

---

## Service Information

### API Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/users` | Create user with initial balance |
| GET | `/users/{userId}/balance` | Get user balance (cached) |
| POST | `/transfers` | Create transfer (async processing) |
| GET | `/transfers?userId={userId}` | Get transfer history (paginated) |
| POST | `/transfers/{transferId}/cancel` | Cancel pending transfer |

### Service Ports

| Service | Port | URL |
|---------|------|-----|
| Spring Boot API | 8080 | http://localhost:8080 |
| Health Check | 8080 | http://localhost:8080/actuator/health |
| MySQL | 3306 | localhost:3306 |
| Redis | 6379 | localhost:6379 |
| RocketMQ NameServer | 9876 | localhost:9876 |
| RocketMQ Console | 8088 | http://localhost:8088 |

### Response Status Codes

| Code | Description | Example |
|------|-------------|---------|
| 200 | Success | Get balance, cancel transfer |
| 201 | Created | Create user, create transfer |
| 400 | Bad Request | Validation errors, invalid state |
| 404 | Not Found | User or transfer not found |
| 500 | Internal Error | Database/RocketMQ errors |

---

## Additional Resources

- **Architecture Documentation:** See `CLAUDE.md` for detailed architecture, patterns, and design decisions
- **Technical Specification:** See `SPEC.md` for complete Chinese technical specification
- **Integration Tests:** See `integration-tests/README.md` for pytest-based integration test guide
- **Assignment Requirements:** See `README.md` for original homework requirements
