"""Shared pytest fixtures for integration tests."""
import pytest
import requests
import pymysql
import redis
import logging
import uuid
from typing import Generator
from config.test_config import config

logger = logging.getLogger(__name__)


@pytest.fixture(scope="session")
def api_base_url() -> str:
    """Provide base URL for API requests."""
    return config.app.base_url


@pytest.fixture(scope="session")
def api_client(api_base_url: str) -> Generator[requests.Session, None, None]:
    """
    Provide HTTP client for API requests.

    Yields:
        requests.Session: Configured HTTP session
    """
    session = requests.Session()
    session.headers.update({
        "Content-Type": "application/json",
        "Accept": "application/json"
    })

    # Verify API is accessible
    try:
        response = session.get(
            f"{api_base_url}{config.app.health_check_endpoint}",
            timeout=5
        )
        logger.info(f"API health check: {response.status_code}")
    except Exception as e:
        logger.warning(f"API health check failed: {e}")

    yield session
    session.close()


@pytest.fixture(scope="session")
def db_connection() -> Generator[pymysql.Connection, None, None]:
    """
    Provide MySQL database connection.

    Yields:
        pymysql.Connection: Database connection
    """
    connection = pymysql.connect(
        host=config.mysql.host,
        port=config.mysql.port,
        user=config.mysql.user,
        password=config.mysql.password,
        database=config.mysql.database,
        charset=config.mysql.charset,
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True
    )

    logger.info(f"Database connected: {config.mysql.database}")
    yield connection
    connection.close()


@pytest.fixture
def db_cursor(db_connection: pymysql.Connection) -> Generator[pymysql.cursors.DictCursor, None, None]:
    """
    Provide database cursor for each test.

    Yields:
        pymysql.cursors.DictCursor: Database cursor
    """
    with db_connection.cursor() as cursor:
        yield cursor


@pytest.fixture(scope="session")
def redis_client() -> Generator[redis.Redis, None, None]:
    """
    Provide Redis client.

    Yields:
        redis.Redis: Redis client instance
    """
    client = redis.Redis(
        host=config.redis.host,
        port=config.redis.port,
        db=config.redis.db,
        decode_responses=config.redis.decode_responses
    )

    # Verify Redis connection
    try:
        assert client.ping(), "Redis connection failed"
        logger.info("Redis connected successfully")
    except Exception as e:
        logger.warning(f"Redis connection failed: {e}")

    yield client
    client.close()


@pytest.fixture(autouse=True)
def cleanup_database(db_cursor: pymysql.cursors.DictCursor) -> Generator[None, None, None]:
    """
    Clean database before each test.
    Executes before test runs.
    """
    if config.cleanup_enabled:
        logger.info("Cleaning database tables...")
        # Order matters: delete children before parents (respect foreign keys)
        db_cursor.execute("DELETE FROM balance_changes")
        db_cursor.execute("DELETE FROM transfers")
        db_cursor.execute("DELETE FROM user_balances")
        logger.info("Database cleaned")

    yield

    # Optional: cleanup after test (currently no-op)


@pytest.fixture(autouse=True)
def cleanup_redis(redis_client: redis.Redis) -> Generator[None, None, None]:
    """
    Clean Redis cache before each test.
    Executes before test runs.
    """
    if config.cleanup_enabled:
        logger.info("Flushing Redis cache...")
        redis_client.flushdb()
        logger.info("Redis cache flushed")

    yield

    # Optional: cleanup after test (currently no-op)


@pytest.fixture
def unique_user_id() -> str:
    """Generate unique user ID for test isolation."""
    return f"user_{uuid.uuid4().hex[:8]}"


@pytest.fixture
def api(api_client: requests.Session, api_base_url: str):
    """
    Provide API client helper.

    Returns:
        APIClient: Wrapper around HTTP session with convenience methods
    """
    from fixtures.api_client import APIClient
    return APIClient(api_client, api_base_url)
