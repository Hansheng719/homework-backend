# Integration Tests

Pytest-based integration test suite for the homework-backend Spring Boot application.

## Overview

This test suite implements 27 integration test cases across 7 categories:
- **Happy Path Scenarios** (3 tests): IT-001 to IT-003
- **Business Logic Validation** (5 tests): IT-004 to IT-008
- **Error Handling** (3 tests): IT-009 to IT-011
- **State Transition** (3 tests): IT-012 to IT-014
- **Data Integrity** (3 tests): IT-015 to IT-017
- **Cache Integration** (3 tests): IT-018 to IT-020
- **Edge Cases** (7 tests): IT-021 to IT-028

## Prerequisites

- **Python 3.9+** installed
- **Docker** installed and running
- **Spring Boot application** source code
- **Maven** or **Gradle** (for building the Spring Boot app)

## Setup

### 1. Start Test Infrastructure

Start MySQL and Redis containers:

```bash
cd integration-tests
docker-compose -f docker/docker-compose.yml up -d
```

Verify containers are running:

```bash
docker ps
```

You should see:
- `homework-mysql-test` on port 3307
- `homework-redis-test` on port 6380

### 2. Install Python Dependencies

Create a virtual environment and install dependencies:

```bash
# Create virtual environment
python3 -m venv venv

# Activate virtual environment
# On macOS/Linux:
source venv/bin/activate
# On Windows:
# venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt
```

### 3. Start Spring Boot Application

In a separate terminal, start the Spring Boot application:

```bash
cd ..  # Go back to project root
./mvnw spring-boot:run
```

Or with test profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=test
```

Wait for the application to start (usually ~30 seconds). The application should be accessible at `http://localhost:8080`.

### 4. Verify Setup

Check that all services are ready:

```bash
# Check MySQL
mysql -h 127.0.0.1 -P 3307 -u testuser -ptestpass testdb -e "SELECT 1;"

# Check Redis
redis-cli -p 6380 ping

# Check Spring Boot API
curl http://localhost:8080/actuator/health
```

## Running Tests

### Run All Tests

```bash
cd integration-tests
pytest
```

### Run Specific Test Category

```bash
# Run only happy path tests
pytest -m happy_path

# Run only business logic tests
pytest -m business_logic

# Run only cache integration tests
pytest -m cache_integration
```

### Run Specific Test File

```bash
pytest tests/test_01_happy_path.py
pytest tests/test_06_cache_integration.py
```

### Run Specific Test

```bash
pytest tests/test_01_happy_path.py::test_IT001_complete_transfer_flow
```

### Run with Verbose Output

```bash
pytest -v
pytest -vv  # Extra verbose
```

### Run with Parallel Execution

```bash
# Run with 4 parallel workers
pytest -n 4

# Run with auto-detection of CPU cores
pytest -n auto
```

### Generate HTML Report

```bash
pytest --html=reports/html/report.html --self-contained-html
```

Open `reports/html/report.html` in your browser to view the report.

### Run Tests Without Warnings

```bash
pytest -p no:warnings
```

### Run Only Slow Tests

```bash
pytest -m slow
```

### Run Excluding Slow Tests

```bash
pytest -m "not slow"
```

## Test Markers

Tests are marked with the following markers for categorization:

- `happy_path`: Happy path scenarios
- `business_logic`: Business logic validation
- `error_handling`: Error handling tests
- `state_transition`: State transition tests
- `data_integrity`: Data integrity tests
- `cache_integration`: Cache integration tests
- `edge_cases`: Edge case tests
- `slow`: Tests that may take longer to execute
- `concurrent`: Tests involving concurrent operations

## Configuration

### Environment Variables

You can customize test configuration using environment variables:

```bash
# API configuration
export API_BASE_URL=http://localhost:8080

# MySQL configuration
export MYSQL_HOST=localhost
export MYSQL_PORT=3307
export MYSQL_DATABASE=testdb
export MYSQL_USER=testuser
export MYSQL_PASSWORD=testpass

# Redis configuration
export REDIS_HOST=localhost
export REDIS_PORT=6380

# Test behavior
export CLEANUP_ENABLED=true
```

### Configuration File

Edit `config/test_config.py` to modify default configuration values.

## Project Structure

```
integration-tests/
├── conftest.py                  # Shared pytest fixtures
├── pytest.ini                   # Pytest configuration
├── requirements.txt             # Python dependencies
├── README.md                    # This file
│
├── config/
│   └── test_config.py           # Test configuration
│
├── fixtures/
│   └── api_client.py            # API client wrapper
│
├── helpers/
│   ├── assertions.py            # Custom assertions
│   ├── wait_utils.py            # Async operation helpers
│   └── data_generators.py      # Test data generators
│
├── tests/
│   ├── test_01_happy_path.py
│   ├── test_02_business_logic.py
│   ├── test_03_error_handling.py
│   ├── test_04_state_transition.py
│   ├── test_05_data_integrity.py
│   ├── test_06_cache_integration.py
│   └── test_07_edge_cases.py
│
└── docker/
    └── docker-compose.test.yml  # Test infrastructure
```

## Test Verification Points

Each test verifies:

1. **API Response**: HTTP status code, JSON structure, response values
2. **Database State**: Direct SQL queries to verify persisted data
3. **Redis State**: Redis commands to verify cache keys and values

Example:
```python
# 1. Verify API response
response = api.create_user("user_001", 1000.00)
assert response.status_code == 201
assert response.json()["balance"] == "1000.00"

# 2. Verify database
db_cursor.execute("SELECT balance FROM user_balances WHERE user_id = %s", ("user_001",))
result = db_cursor.fetchone()
assert Decimal(str(result["balance"])) == Decimal("1000.00")

# 3. Verify Redis cache
cache_key = "balance::user_001"
assert redis_client.exists(cache_key) > 0
```

## Handling Asynchronous Operations

Transfers are processed via RocketMQ asynchronously. Tests use polling to wait for completion:

```python
from helpers.wait_utils import wait_for_transfer_completion

# Create transfer
response = api.create_transfer(user_a, user_b, 300.00)
transfer_id = response.json()["id"]

# Wait for completion (polls database every 0.5s, max 30s)
wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED", timeout=30)
```

## Troubleshooting

### Tests Fail with Connection Errors

**Problem**: Tests cannot connect to MySQL or Redis.

**Solution**:
1. Verify Docker containers are running: `docker ps`
2. Check port availability: `lsof -i :3307` and `lsof -i :6380`
3. Restart containers: `docker-compose -f docker/docker-compose.test.yml restart`

### Tests Fail with API Timeout

**Problem**: Tests timeout waiting for API responses.

**Solution**:
1. Verify Spring Boot app is running: `curl http://localhost:8080/actuator/health`
2. Check application logs for errors
3. Increase timeout in `config/test_config.py`: `timeout: int = 60`

### Tests Fail Intermittently

**Problem**: Tests pass sometimes but fail other times.

**Solution**:
1. Enable cleanup: `export CLEANUP_ENABLED=true`
2. Run tests sequentially: `pytest -n 0`
3. Increase async wait timeout in `config/test_config.py`: `async_wait_timeout: int = 60`

### Database Has Stale Data

**Problem**: Tests fail due to data from previous runs.

**Solution**:
1. Clean database manually:
```bash
mysql -h 127.0.0.1 -P 3307 -u testuser -ptestpass testdb -e "
DELETE FROM balance_changes;
DELETE FROM transfers;
DELETE FROM user_balances;
"
```

2. Flush Redis:
```bash
redis-cli -p 6380 FLUSHDB
```

## Cleanup

### Stop Test Infrastructure

```bash
cd integration-tests
docker-compose -f docker/docker-compose.yml down
```

### Remove Volumes (Clean Slate)

```bash
docker-compose -f docker/docker-compose.yml down -v
```

### Deactivate Virtual Environment

```bash
deactivate
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Integration Tests

on: [push, pull_request]

jobs:
  integration-tests:
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: rootpass
          MYSQL_DATABASE: testdb
          MYSQL_USER: testuser
          MYSQL_PASSWORD: testpass
        ports:
          - 3307:3306
        options: >-
          --health-cmd="mysqladmin ping"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5

      redis:
        image: redis:7-alpine
        ports:
          - 6380:6379
        options: >-
          --health-cmd="redis-cli ping"
          --health-interval=5s
          --health-timeout=3s
          --health-retries=5

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Set up Python
        uses: actions/setup-python@v4
        with:
          python-version: '3.11'

      - name: Install Python dependencies
        run: |
          cd integration-tests
          pip install -r requirements.txt

      - name: Start Spring Boot Application
        run: |
          ./mvnw spring-boot:run &
          sleep 30

      - name: Run Integration Tests
        run: |
          cd integration-tests
          pytest -v --html=reports/report.html

      - name: Upload Test Report
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-report
          path: integration-tests/reports/
```

## Contributing

When adding new tests:

1. Follow the existing test structure and naming conventions
2. Use appropriate markers (`@pytest.mark.xxx`)
3. Include clear docstrings describing the test
4. Verify API response, database state, and Redis state
5. Clean up test data (handled by autouse fixtures)
6. Update this README if adding new test categories

## Support

For issues or questions:
- Check the test documentation in `/docs/integration-tests/`
- Review test case specifications in `/docs/integration-tests/test-cases/`
- Check Spring Boot application logs

## Test Summary

Total: 27 integration tests

| Category | Tests | IDs |
|----------|-------|-----|
| Happy Path | 3 | IT-001 to IT-003 |
| Business Logic | 5 | IT-004 to IT-008 |
| Error Handling | 3 | IT-009 to IT-011 |
| State Transition | 3 | IT-012 to IT-014 |
| Data Integrity | 3 | IT-015 to IT-017 |
| Cache Integration | 3 | IT-018 to IT-020 |
| Edge Cases | 7 | IT-021 to IT-028 |
