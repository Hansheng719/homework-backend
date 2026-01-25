"""Custom assertion helpers."""
from typing import Any, Dict, Optional
from decimal import Decimal
import pymysql.cursors
import redis


def assert_balance(
    actual: Any,
    expected: float,
    msg: Optional[str] = None
) -> None:
    """Assert balance values are equal."""
    actual_decimal = Decimal(str(actual))
    expected_decimal = Decimal(str(expected))
    assert actual_decimal == expected_decimal, \
        msg or f"Balance mismatch: expected {expected}, got {actual}"


def assert_transfer_status(
    transfer: Dict[str, Any],
    expected_status: str,
    msg: Optional[str] = None
) -> None:
    """Assert transfer status."""
    actual_status = transfer.get("status")
    assert actual_status == expected_status, \
        msg or f"Status mismatch: expected {expected_status}, got {actual_status}"


def assert_db_row_count(
    cursor: pymysql.cursors.DictCursor,
    table: str,
    expected_count: int,
    where_clause: str = "",
    msg: Optional[str] = None
) -> None:
    """Assert database row count."""
    query = f"SELECT COUNT(*) as count FROM {table}"
    if where_clause:
        query += f" WHERE {where_clause}"

    cursor.execute(query)
    result = cursor.fetchone()
    actual_count = result["count"]

    assert actual_count == expected_count, \
        msg or f"Row count mismatch in {table}: expected {expected_count}, got {actual_count}"


def assert_redis_key_exists(
    redis_client: redis.Redis,
    key: str,
    should_exist: bool = True,
    msg: Optional[str] = None
) -> None:
    """Assert Redis key existence."""
    exists = redis_client.exists(key) > 0

    if should_exist:
        assert exists, msg or f"Redis key '{key}' should exist but doesn't"
    else:
        assert not exists, msg or f"Redis key '{key}' should not exist but does"


def assert_redis_ttl(
    redis_client: redis.Redis,
    key: str,
    min_ttl: int,
    max_ttl: int,
    msg: Optional[str] = None
) -> None:
    """Assert Redis key TTL is within range."""
    ttl = redis_client.ttl(key)

    assert min_ttl <= ttl <= max_ttl, \
        msg or f"Redis key '{key}' TTL {ttl} not in range [{min_ttl}, {max_ttl}]"


def assert_pagination_metadata(
    pagination: Dict[str, Any],
    expected_current_page: int,
    expected_page_size: int,
    expected_total_elements: int,
    expected_total_pages: int,
    expected_has_next: bool,
    expected_has_previous: bool,
    msg: Optional[str] = None
) -> None:
    """Assert pagination metadata is correct."""
    assert pagination["currentPage"] == expected_current_page, \
        f"currentPage mismatch: expected {expected_current_page}, got {pagination['currentPage']}"
    assert pagination["pageSize"] == expected_page_size, \
        f"pageSize mismatch: expected {expected_page_size}, got {pagination['pageSize']}"
    assert pagination["totalElements"] == expected_total_elements, \
        f"totalElements mismatch: expected {expected_total_elements}, got {pagination['totalElements']}"
    assert pagination["totalPages"] == expected_total_pages, \
        f"totalPages mismatch: expected {expected_total_pages}, got {pagination['totalPages']}"
    assert pagination["hasNext"] == expected_has_next, \
        f"hasNext mismatch: expected {expected_has_next}, got {pagination['hasNext']}"
    assert pagination["hasPrevious"] == expected_has_previous, \
        f"hasPrevious mismatch: expected {expected_has_previous}, got {pagination['hasPrevious']}"
