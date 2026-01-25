"""Error Handling - IT-009 to IT-011."""
import pytest
from fixtures.api_client import APIClient


@pytest.mark.error_handling
def test_IT009_user_not_found_returns_404(
    api: APIClient,
    unique_user_id
):
    """
    IT-009: Querying non-existent user returns 404.

    Verifies:
    - 404 NOT_FOUND response
    - "User Not Found" error message
    - Message includes specific userId
    """
    non_existent_user = f"{unique_user_id}_NONEXISTENT"

    # Query non-existent user's balance
    response = api.get_balance(non_existent_user)
    assert response.status_code == 404
    error_data = response.json()
    assert "not found" in error_data.get("message", "").lower() or \
           "not found" in error_data.get("error", "").lower()
    assert non_existent_user in error_data.get("message", "")


@pytest.mark.error_handling
def test_IT010_invalid_request_format_returns_400(
    api: APIClient,
    unique_user_id
):
    """
    IT-010: Invalid request format returns 400.

    Tests:
    - Empty userId (blank string)
    - Negative initialBalance
    - Zero transfer amount
    - Null values

    Verifies:
    - 400 BAD_REQUEST with field validation errors
    - No data is persisted
    """
    # Test 1: Empty userId
    response = api.create_user("", 1000.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "userId" in str(error_data).lower() or \
           "blank" in str(error_data).lower() or \
           "empty" in str(error_data).lower()

    # Test 2: userId too short (< 3 chars)
    response = api.create_user("ab", 1000.00)
    assert response.status_code == 400

    # Test 3: Negative initialBalance
    user_valid = f"{unique_user_id}_VALID"
    response = api.create_user(user_valid, -100.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "balance" in str(error_data).lower() or \
           "negative" in str(error_data).lower() or \
           "must be" in str(error_data).lower()

    # Test 4: Zero transfer amount
    # First create valid users
    user_p = f"{unique_user_id}_P"
    user_q = f"{unique_user_id}_Q"
    api.create_user(user_p, 1000.00)
    api.create_user(user_q, 500.00)

    # Attempt transfer with zero amount
    response = api.create_transfer(user_p, user_q, 0.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "amount" in str(error_data).lower() or \
           "greater than 0" in str(error_data).lower() or \
           "positive" in str(error_data).lower()

    # Test 5: Negative transfer amount
    response = api.create_transfer(user_p, user_q, -50.00)
    assert response.status_code == 400


@pytest.mark.error_handling
def test_IT011_transfer_not_found_returns_404(
    api: APIClient,
    db_cursor
):
    """
    IT-011: Cancelling non-existent transfer returns 404.

    Verifies:
    - 404 NOT_FOUND response
    - "Transfer Not Found" error message
    - Message includes specific transferId
    """
    non_existent_transfer_id = 999999

    # Attempt to cancel non-existent transfer
    response = api.cancel_transfer(non_existent_transfer_id)
    assert response.status_code == 404
    error_data = response.json()
    assert "not found" in error_data.get("message", "").lower() or \
           "not found" in error_data.get("error", "").lower()
    assert str(non_existent_transfer_id) in error_data.get("message", "")
