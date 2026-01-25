"""Business Logic Validation - IT-004 to IT-008."""
import pytest
from decimal import Decimal
from fixtures.api_client import APIClient
from helpers.assertions import assert_balance


@pytest.mark.business_logic
def test_IT004_transfer_fails_on_insufficient_balance(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-004: Transfer fails when amount exceeds available balance.

    Verifies:
    - 400 BAD_REQUEST with "Insufficient Balance" error
    - Balances remain unchanged
    - No transfer record created
    """
    user_h = f"{unique_user_id}_H"
    user_i = f"{unique_user_id}_I"

    # Setup: Create users
    api.create_user(user_h, 500.00)
    api.create_user(user_i, 200.00)

    # Attempt transfer exceeding balance
    response = api.create_transfer(user_h, user_i, 600.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "Insufficient" in error_data.get("message", "") or \
           "Insufficient" in error_data.get("error", "") or \
           "balance" in error_data.get("message", "").lower()

    # Verify balances unchanged
    response = api.get_balance(user_h)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 500.00)

    response = api.get_balance(user_i)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 200.00)

    # Verify no transfer record created
    db_cursor.execute(
        "SELECT COUNT(*) as count FROM transfers WHERE from_user_id = %s AND to_user_id = %s",
        (user_h, user_i)
    )
    result = db_cursor.fetchone()
    assert result["count"] == 0


@pytest.mark.business_logic
def test_IT005_duplicate_userId_creation_fails(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-005: Creating a user with duplicate userId fails.

    Verifies:
    - First creation succeeds with 201 CREATED
    - Second creation fails with 409 CONFLICT
    - Only one record exists in database
    """
    user_j = f"{unique_user_id}_J"

    # First creation succeeds
    response = api.create_user(user_j, 1000.00)
    assert response.status_code == 201
    data = response.json()
    assert data["userId"] == user_j
    assert_balance(data["balance"], 1000.00)

    # Second creation with same userId fails
    response = api.create_user(user_j, 2000.00)
    assert response.status_code == 409
    error_data = response.json()
    assert "already exists" in error_data.get("message", "").lower() or \
           "conflict" in error_data.get("error", "").lower() or \
           "duplicate" in error_data.get("message", "").lower()

    # Verify only one record exists with original balance
    db_cursor.execute(
        "SELECT COUNT(*) as count, MAX(balance) as balance FROM user_balances WHERE user_id = %s",
        (user_j,)
    )
    result = db_cursor.fetchone()
    assert result["count"] == 1
    assert_balance(result["balance"], 1000.00)


@pytest.mark.business_logic
def test_IT006_cannot_cancel_non_pending_transfer(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-006: Cannot cancel a transfer that is not in PENDING state.

    Verifies:
    - 400 BAD_REQUEST with "Invalid Transfer State" error
    - Transfer status remains COMPLETED
    - cancelledAt remains NULL
    """
    user_k = f"{unique_user_id}_K"
    user_l = f"{unique_user_id}_L"

    # Setup: Create users
    api.create_user(user_k, 1000.00)
    api.create_user(user_l, 500.00)

    # Create transfer
    response = api.create_transfer(user_k, user_l, 200.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]

    # Wait for transfer to complete
    from helpers.wait_utils import wait_for_transfer_completion
    wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED", timeout=30)

    # Attempt to cancel COMPLETED transfer
    response = api.cancel_transfer(transfer_id)
    assert response.status_code == 400
    error_data = response.json()
    assert "state" in error_data.get("message", "").lower() or \
           "cannot cancel" in error_data.get("message", "").lower() or \
           "not pending" in error_data.get("message", "").lower()

    # Verify transfer status remains COMPLETED
    db_cursor.execute(
        "SELECT status, cancelled_at FROM transfers WHERE id = %s",
        (transfer_id,)
    )
    transfer = db_cursor.fetchone()
    assert transfer["status"] == "COMPLETED"
    assert transfer["cancelled_at"] is None


@pytest.mark.business_logic
def test_IT007_cannot_cancel_transfer_after_10_minutes(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-007: Cannot cancel a transfer after 10-minute window.

    Note: This test simulates an expired transfer by manipulating the created_at timestamp.

    Verifies:
    - 400 BAD_REQUEST with "cancellation window expired" error
    - Transfer status remains PENDING
    - cancelledAt remains NULL
    """
    user_m = f"{unique_user_id}_M"
    user_n = f"{unique_user_id}_N"

    # Setup: Create users
    api.create_user(user_m, 1000.00)
    api.create_user(user_n, 500.00)

    # Create transfer
    response = api.create_transfer(user_m, user_n, 200.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]

    # Simulate transfer created 11 minutes ago by updating DB
    db_cursor.execute(
        "UPDATE transfers SET created_at = DATE_SUB(NOW(), INTERVAL 11 MINUTE) WHERE id = %s",
        (transfer_id,)
    )

    # Attempt to cancel expired transfer
    response = api.cancel_transfer(transfer_id)
    assert response.status_code == 400
    error_data = response.json()
    assert "window" in error_data.get("message", "").lower() or \
           "expired" in error_data.get("message", "").lower() or \
           "10 minute" in error_data.get("message", "").lower()

    # Verify transfer status remains PENDING
    db_cursor.execute(
        "SELECT status, cancelled_at FROM transfers WHERE id = %s",
        (transfer_id,)
    )
    transfer = db_cursor.fetchone()
    assert transfer["status"] == "PENDING"
    assert transfer["cancelled_at"] is None


@pytest.mark.business_logic
def test_IT008_self_transfer_is_rejected(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-008: User cannot transfer to themselves.

    Verifies:
    - 400 BAD_REQUEST with "Cannot transfer to the same user" error
    - Balance unchanged
    - No transfer record created
    """
    user_o = f"{unique_user_id}_O"

    # Setup: Create user
    api.create_user(user_o, 1000.00)

    # Attempt self-transfer
    response = api.create_transfer(user_o, user_o, 100.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "same user" in error_data.get("message", "").lower() or \
           "self" in error_data.get("message", "").lower() or \
           "cannot transfer to" in error_data.get("message", "").lower()

    # Verify balance unchanged
    response = api.get_balance(user_o)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1000.00)

    # Verify no transfer record created
    db_cursor.execute(
        "SELECT COUNT(*) as count FROM transfers WHERE from_user_id = %s AND to_user_id = %s",
        (user_o, user_o)
    )
    result = db_cursor.fetchone()
    assert result["count"] == 0
