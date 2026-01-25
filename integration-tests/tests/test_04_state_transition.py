"""State Transition - IT-012 to IT-014."""
import pytest
from decimal import Decimal
from fixtures.api_client import APIClient
from helpers.wait_utils import wait_for_transfer_completion
from helpers.assertions import assert_balance


@pytest.mark.state_transition
def test_IT012_transfer_state_pending_to_completed(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-012: Transfer state transitions from PENDING to COMPLETED.

    Verifies:
    - Initial state is PENDING
    - Final state is COMPLETED
    - completedAt timestamp is set
    - User balances are updated correctly
    """
    user_r = f"{unique_user_id}_R"
    user_s = f"{unique_user_id}_S"

    # Setup: Create users
    api.create_user(user_r, 1000.00)
    api.create_user(user_s, 500.00)

    # Create transfer
    response = api.create_transfer(user_r, user_s, 300.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]
    initial_status = response.json()["status"]
    assert initial_status == "PENDING"

    # Wait for transfer to complete
    assert wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED", timeout=30)

    # Verify final state
    db_cursor.execute(
        "SELECT status, completed_at FROM transfers WHERE id = %s",
        (transfer_id,)
    )
    transfer = db_cursor.fetchone()
    assert transfer["status"] == "COMPLETED"
    assert transfer["completed_at"] is not None

    # Verify balances updated
    response = api.get_balance(user_r)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 700.00)

    response = api.get_balance(user_s)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 800.00)


@pytest.mark.state_transition
def test_IT013_transfer_state_pending_to_cancelled(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-013: Transfer state transitions from PENDING to CANCELLED.

    Verifies:
    - Initial state is PENDING
    - State changes to CANCELLED after cancellation
    - cancelledAt timestamp is set
    - Balances remain unchanged
    """
    user_t = f"{unique_user_id}_T"
    user_u = f"{unique_user_id}_U"

    # Setup: Create users
    api.create_user(user_t, 1000.00)
    api.create_user(user_u, 500.00)

    # Create transfer
    response = api.create_transfer(user_t, user_u, 200.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]
    assert response.json()["status"] == "PENDING"

    # Cancel transfer
    response = api.cancel_transfer(transfer_id)
    assert response.status_code == 200

    # Verify state changed to CANCELLED
    db_cursor.execute(
        "SELECT status, cancelled_at FROM transfers WHERE id = %s",
        (transfer_id,)
    )
    transfer = db_cursor.fetchone()
    assert transfer["status"] == "CANCELLED"
    assert transfer["cancelled_at"] is not None

    # Verify balances unchanged
    response = api.get_balance(user_t)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1000.00)

    response = api.get_balance(user_u)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 500.00)


@pytest.mark.state_transition
def test_IT014_transfer_failed_state_on_insufficient_balance(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-014: Transfer enters FAILED state when balance is insufficient.

    Two possible scenarios:
    A) Transfer rejected at creation (400 BAD_REQUEST)
    B) Transfer created but marked FAILED

    Verifies:
    - Either 400 BAD_REQUEST or 201 CREATED with status=FAILED
    - Balances remain unchanged
    - No balance_changes records created (if transfer exists)
    """
    user_aa = f"{unique_user_id}_AA"
    user_ab = f"{unique_user_id}_AB"

    # Setup: Create users
    api.create_user(user_aa, 100.00)
    api.create_user(user_ab, 500.00)

    # Attempt transfer exceeding balance
    response = api.create_transfer(user_aa, user_ab, 500.00)

    # Scenario A: Rejected at creation
    if response.status_code == 400:
        # Verify no transfer record
        db_cursor.execute(
            "SELECT COUNT(*) as count FROM transfers WHERE from_user_id = %s",
            (user_aa,)
        )
        result = db_cursor.fetchone()
        assert result["count"] == 0

    # Scenario B: Created with FAILED status
    elif response.status_code == 201:
        transfer_id = response.json()["id"]
        transfer_status = response.json()["status"]
        assert transfer_status == "FAILED"

        # Verify no balance_changes records
        db_cursor.execute(
            "SELECT COUNT(*) as count FROM balance_changes WHERE external_id = %s",
            (transfer_id,)
        )
        result = db_cursor.fetchone()
        assert result["count"] == 0

    else:
        pytest.fail(f"Unexpected status code: {response.status_code}")

    # Verify balances unchanged
    response = api.get_balance(user_aa)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 100.00)

    response = api.get_balance(user_ab)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 500.00)
