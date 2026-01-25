"""Utilities for handling asynchronous operations."""
import time
import logging
from typing import Callable, Any, Optional
from tenacity import retry, stop_after_delay, wait_fixed, RetryError
from config.test_config import config

logger = logging.getLogger(__name__)


def wait_for_transfer_completion(
    db_cursor,
    transfer_id: int,
    expected_status: str = "COMPLETED",
    timeout: int = None
) -> bool:
    """
    Wait for transfer to reach expected status.

    Args:
        db_cursor: Database cursor
        transfer_id: Transfer ID to check
        expected_status: Expected transfer status
        timeout: Max wait time in seconds

    Returns:
        bool: True if status reached, False otherwise
    """
    timeout = timeout or config.async_wait_timeout
    poll_interval = config.async_poll_interval

    start_time = time.time()
    while time.time() - start_time < timeout:
        db_cursor.execute(
            "SELECT status FROM transfers WHERE id = %s",
            (transfer_id,)
        )
        result = db_cursor.fetchone()

        if result and result["status"] == expected_status:
            logger.info(
                f"Transfer {transfer_id} reached status {expected_status}"
            )
            return True

        time.sleep(poll_interval)

    logger.warning(
        f"Transfer {transfer_id} did not reach status {expected_status} "
        f"within {timeout}s"
    )
    return False


def wait_for_balance_update(
    api_client,
    user_id: str,
    expected_balance: float,
    timeout: int = None
) -> bool:
    """
    Wait for user balance to reach expected value.

    Args:
        api_client: API client instance
        user_id: User ID to check
        expected_balance: Expected balance value
        timeout: Max wait time in seconds

    Returns:
        bool: True if balance reached, False otherwise
    """
    from decimal import Decimal

    timeout = timeout or config.async_wait_timeout
    poll_interval = config.async_poll_interval

    start_time = time.time()
    while time.time() - start_time < timeout:
        response = api_client.get_balance(user_id)
        if response.status_code == 200:
            actual_balance = Decimal(str(response.json()["balance"]))
            expected_balance_decimal = Decimal(str(expected_balance))

            if actual_balance == expected_balance_decimal:
                logger.info(
                    f"User {user_id} balance reached {expected_balance}"
                )
                return True

        time.sleep(poll_interval)

    logger.warning(
        f"User {user_id} balance did not reach {expected_balance} "
        f"within {timeout}s"
    )
    return False


@retry(
    stop=stop_after_delay(30),
    wait=wait_fixed(0.5),
    reraise=True
)
def wait_for_condition(condition: Callable[[], bool]) -> bool:
    """
    Wait for a condition to become true.

    Args:
        condition: Callable that returns bool

    Returns:
        bool: True when condition met

    Raises:
        RetryError: If timeout exceeded
    """
    if not condition():
        raise Exception("Condition not met")
    return True
