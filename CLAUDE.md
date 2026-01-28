# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Balance Transfer Service** - an event-driven Spring Boot application implementing atomic balance transfers between users using a Facade pattern architecture. The system uses MySQL for persistence, Redis for caching, RocketMQ for async processing, and ShedLock for distributed scheduler coordination.

## Build & Run Commands

### Prerequisites
- Java 21
- Docker and Docker Compose
- Maven (via wrapper: `./mvnw`)

### Start Infrastructure
```bash
# Start MySQL, Redis, RocketMQ
docker-compose up -d

# Verify services are running
docker ps
```

### Build & Test
```bash
# Clean build (skip tests)
./mvnw clean install -DskipTests

# Compile only
./mvnw clean compile

# Compile tests
./mvnw clean test-compile

# Run all unit tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=TransferProcessorJobTest

# Run specific test method
./mvnw test -Dtest=TransferServiceTest#createPendingTransfer_ValidRequest_CreateSuccessfully
```

### Run Application
```bash
# Run with default profile (production settings)
./mvnw spring-boot:run

# Run with local profile (faster scheduled jobs)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Run on different port
./mvnw spring-boot:run -Dserver.port=8081
```

### Integration Tests (Python/Pytest)
```bash
cd integration-tests

# Start test infrastructure (separate MySQL/Redis on different ports)
docker-compose -f docker/docker-compose.yml up -d

# Setup Python environment
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt

# Run all integration tests
pytest

# Run specific test category
pytest -m happy_path
pytest -m cache_integration

# Run specific test file
pytest tests/test_01_happy_path.py

# Run with HTML report
pytest --html=reports/html/report.html --self-contained-html
```

## Architecture & Key Design Patterns

### Layered Architecture
```
Controller → Facade → Service → Repository
              ↓
         EventPublisher
              ↓
         RocketMQ → Consumer → Facade
```

### Facade Pattern
The system uses **Facade** classes to orchestrate multi-service operations:
- **TransferFacade**: Coordinates transfer creation, processing, cancellation
- **BalanceFacade**: Handles balance change events and coordinates BalanceService

**Key principle**: Facades coordinate workflow but contain NO business logic. All business logic lives in Service classes which provide atomic, single-responsibility operations.

### Event-Driven Asynchronous Processing
Transfers follow this state machine via RocketMQ events:
```
PENDING → DEBIT_PROCESSING → DEBIT_COMPLETED → CREDIT_PROCESSING → COMPLETED
                ↓                                      ↓
          DEBIT_FAILED                          (retry on failure)
```

**Critical**: Balance changes (debit/credit) are:
- Processed asynchronously via RocketMQ
- Idempotent (using `balance_changes` table to track operations)
- Coordinated by BalanceFacade which publishes results back
- Triggered by scheduled jobs (not immediately on transfer creation)

### Scheduled Jobs with Distributed Locking
Three scheduled jobs process transfers (located in `TransferProcessorJob`):
1. **processPendingTransfers()**: Moves PENDING → DEBIT_PROCESSING
2. **processDebitProcessingTransfers()**: Retries stuck debit operations
3. **processCreditProcessingTransfers()**: Retries stuck credit operations

**Distributed Locking**: Uses ShedLock with Redis to ensure only ONE instance executes each job:
- Lock keys: `homework-backend:processPendingTransfers`, etc.
- Lock durations: 5s (local), 300s (production) - matches job frequency
- Non-blocking: If locked, other instances skip execution

**Configuration**: Lock durations are in `application.yaml` and `application-local.yaml`:
```yaml
scheduler:
  transfer-processor:
    pending-lock-at-most-seconds: 300  # prod: 5 min
    pending-lock-at-least-seconds: 5
```

### Idempotency via balance_changes Table
The `BalanceService` ensures idempotent operations:
- Each balance operation creates a `BalanceChange` record with unique `externalId` and `type`
- Before debit/credit, checks if `externalId` and `type` already exists
- If exists and COMPLETED: skip operation (already done)
- If exists and FAILED: can retry
- Prevents duplicate balance changes in retry scenarios

### Pessimistic Locking
Critical operations use database-level locking:
- `TransferRepository.findByIdForUpdate()`: Uses `@Lock(LockModeType.PESSIMISTIC_WRITE)`
- `UserBalanceRepository.findByUserIdForUpdate()`: Uses `@Lock(LockModeType.PESSIMISTIC_WRITE)`
- Prevents race conditions when updating transfer status or user balance

### Redis Caching
Balance queries are cached:
- Cache key: `balance::userId`
- TTL: 5 minutes (300 seconds)
- Evicted on: balance changes (debit/credit)
- Configured via Spring Cache abstraction in `RedisCacheConfig`

## Package Structure & Responsibilities

### `/config`
- `SchedulerProperties`: Scheduler configuration (cron expressions, lock durations)
- `ShedLockConfig`: ShedLock distributed locking configuration
- `RedisCacheConfig`: Redis cache manager setup
- `RocketMQConfig`: RocketMQ producer/consumer configuration

### `/entity`
Core JPA entities with critical indexes:
- `Transfer`: Transfer records with state machine
  - **Critical indexes**: `idx_status_created_at`, `idx_status_updated_at` for scheduler queries
- `UserBalance`: User balance records
- `BalanceChange`: Idempotency tracking for balance operations

### `/repository`
JPA repositories with custom queries:
- `TransferRepository`:
  - `findByIdForUpdate()`: Pessimistic lock for status updates
  - `findPendingTransfers()`: Scheduler query for PENDING transfers
- `UserBalanceRepository`:
  - `findByUserIdForUpdate()`: Pessimistic lock for balance updates

### `/service`
**Atomic, single-responsibility operations**:
- `TransferService`: Transfer CRUD, state transitions (single transaction per method)
- `BalanceService`: Balance validation, debit/credit with idempotency
- `EventPublisher`: RocketMQ event publishing

### `/facade`
**Workflow orchestration** (NO business logic):
- `TransferFacade`: Coordinates transfer creation, processing, cancellation
- `BalanceFacade`: Handles balance change events, coordinates service calls

### `/mq`
- `/producer`: `BalanceChangeProducer` - sends balance change events
- `/consumer`: `BalanceChangeConsumer`, `BalanceChangeResultConsumer` - process events
- `/msg`: Message DTOs (`BalanceChangeMsg`, `BalanceChangeResultMsg`)

### `/schedule`
- `TransferProcessorJob`: Three scheduled jobs with `@SchedulerLock` annotations

### `/event`
- `BalanceOperationCompletedListener`: Spring event listener for cache invalidation

## Environment Configuration

Three profiles available:
- **default** (application.yaml): Production settings - jobs run every 5 minutes
- **local** (application-local.yaml): Development settings - jobs run every 5 seconds
- **prod** (application-prod.yaml): Explicit production settings

**Critical settings by environment**:
```yaml
# Local (fast iteration)
scheduler.transfer-processor:
  pending-cron: "*/5 * * * * ?"  # Every 5 seconds
  pending-lock-at-most-seconds: 5

# Production (conservative)
scheduler.transfer-processor:
  pending-cron: "0 */5 * * * ?"  # Every 5 minutes
  pending-lock-at-most-seconds: 300
```

## Testing Strategy

### Unit Tests (JUnit 5 + Mockito)
- Located in `src/test/java/`
- Mock external dependencies (Facade, Service, Repository)
- Test individual methods in isolation
- Example: `TransferProcessorJobTest` (14 tests including @SchedulerLock annotation verification)

### Integration Tests (Pytest)
- Located in `integration-tests/`
- 27 tests across 7 categories
- Verifies: API response + Database state + Redis cache state
- Uses separate test infrastructure (MySQL on 3307, Redis on 6380)

**Important**: Integration tests require:
1. Test infrastructure running (`docker-compose -f docker/docker-compose.yml up -d`)
2. Spring Boot app running (`./mvnw spring-boot:run`)
3. Python environment with dependencies installed

## Common Development Workflows

### Adding a New Scheduled Job
1. Add job method to `TransferProcessorJob` with `@Scheduled` annotation
2. Add `@SchedulerLock` annotation with unique name
3. Add cron and lock duration properties to `SchedulerProperties`
4. Update `application.yaml` and `application-local.yaml` with configuration
5. Add unit tests verifying annotations and behavior

### Modifying Transfer State Machine
1. Update `TransferStatus` enum if adding new states
2. Update `TransferService` state transition methods
3. Update `TransferFacade` orchestration logic
4. Update scheduled jobs if needed
5. Update integration tests to verify new state flow

### Adding New Balance Operations
1. Create operation in `BalanceService` (ensure idempotency via `balance_changes`)
2. Define event message in `/mq/msg`
3. Update `BalanceChangeProducer` to send event
4. Update `BalanceChangeConsumer` to handle event
5. Update `BalanceFacade` to coordinate
6. Add cache eviction logic if needed

## Critical Implementation Details

### Transfer Processing Flow
Transfers are NOT processed immediately on creation:
1. Controller creates PENDING transfer (returns immediately)
2. Scheduler picks up PENDING transfers (every 5min prod, 5sec local)
3. Scheduler validates balance and moves to DEBIT_PROCESSING
4. MQ consumer processes debit asynchronously
5. MQ consumer processes credit asynchronously
6. Transfer reaches COMPLETED state

**This means**: After `POST /transfers`, the transfer will be PENDING for up to 10 minutes (production) before processing begins.

### Database Indexes Are Critical
The `Transfer` entity has carefully designed composite indexes:
- `idx_status_created_at`: For `findPendingTransfers()` scheduler query
- `idx_status_updated_at`: For retry scheduler queries
- Without these indexes, scheduler queries become extremely slow (10-50x) on large datasets

### ShedLock Prevents Duplicate Job Execution
When running multiple application instances:
- Each scheduled job attempts to acquire a Redis lock
- Only ONE instance acquires the lock and executes
- Other instances skip execution (non-blocking)
- Lock automatically released after completion or timeout
- Lock keys visible in Redis: `redis-cli KEYS homework-backend:*`

### Idempotency Is Everywhere
Key idempotency mechanisms:
1. **BalanceService**: Uses `balance_changes` table to track operations by `externalId`
2. **RocketMQ**: Consumer retries are safe due to idempotency
3. **Pessimistic locks**: Prevent concurrent modifications

## Troubleshooting

### Scheduled Jobs Not Running
- Check `@EnableScheduling` is present in `DemoApplication`
- Verify cron expression in application.yaml
- Check logs for "(lock acquired)" message
- Verify Redis is running: `redis-cli -p 6379 ping`

### Multiple Instances Executing Same Job
- Verify ShedLock dependencies are in pom.xml
- Check Redis connection is working
- Verify `@SchedulerLock` annotations are present
- Check lock duration matches job frequency

### Transfers Stuck in PENDING
- Check scheduled job is running (logs show execution every 5min/5sec)
- Verify RocketMQ is running: `docker ps | grep rocketmq`
- Check MQ consumer is processing: look for consumer logs
- Verify database has `idx_status_created_at` index

### Balance Changes Not Reflected
- Check Redis cache: `redis-cli KEYS balance::*`
- Verify cache eviction is working (check `BalanceOperationCompletedListener`)
- Check `balance_changes` table for operation status
- Verify idempotency checks are not blocking operations

### Integration Tests Failing
- Verify test infrastructure: `docker ps` (should see homework-mysql-test, homework-redis-test)
- Check Spring Boot app is running: `curl http://localhost:8080/actuator/health`
- Clean test data: `redis-cli -p 6380 FLUSHDB` and truncate test database tables
- Run tests sequentially: `pytest -n 0`

## Service Ports

| Service | Port | Notes |
|---------|------|-------|
| Spring Boot | 8080 | Main application |
| MySQL | 3306 | Production/dev database |
| Redis | 6379 | Cache and ShedLock |
| RocketMQ NameServer | 9876 | Message queue |
| RocketMQ Broker | 10911 | Message queue |
| RocketMQ Console | 8088 | Web UI for RocketMQ |
| MySQL (test) | 3307 | Integration test database |
| Redis (test) | 6380 | Integration test cache |

## Important Files

- `SPEC.md`: Detailed Chinese technical specification with complete architecture diagrams
- `README.md`: Original homework assignment requirements
- `pom.xml`: Maven dependencies including ShedLock 5.10.2
- `docker-compose.yaml`: Production/dev infrastructure
- `integration-tests/docker/docker-compose.yml`: Test infrastructure
- `integration-tests/README.md`: Complete integration testing guide
