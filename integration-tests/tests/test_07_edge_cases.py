"""Edge Cases - IT-021 to IT-028."""
import pytest
from decimal import Decimal
from fixtures.api_client import APIClient
from helpers.assertions import assert_pagination_metadata, assert_balance


@pytest.mark.edge_cases
def test_IT021_empty_result_set_pagination(
    api: APIClient,
    unique_user_id
):
    """
    IT-021: Empty result set pagination.

    Verifies:
    - Query transfer history for user with no transfers
    - Returns 200 OK (not 404)
    - Empty transfers array
    - Pagination metadata: totalElements=0, totalPages=0, hasNext=false, hasPrevious=false
    """
    user_ai = f"{unique_user_id}_AI"

    # Create user with no transfers
    api.create_user(user_ai, 1000.00)

    # Query transfer history
    response = api.get_transfer_history(user_ai, page=0, size=20)
    assert response.status_code == 200
    data = response.json()

    # Verify empty transfers array
    assert len(data["transfers"]) == 0

    # Verify pagination metadata
    assert_pagination_metadata(
        data["pagination"],
        expected_current_page=0,
        expected_page_size=20,
        expected_total_elements=0,
        expected_total_pages=0,
        expected_has_next=False,
        expected_has_previous=False
    )


@pytest.mark.edge_cases
def test_IT023_boundary_pagination_sizes(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-023: Boundary pagination sizes.

    Tests:
    - size=1 (minimum): Returns 1 record
    - size=100 (maximum): Returns all records
    - size=0 (below minimum): Returns 400 BAD_REQUEST
    - size=101 (above maximum): Returns 400 BAD_REQUEST
    """
    user_aj = f"{unique_user_id}_AJ"
    user_ak = f"{unique_user_id}_AK"

    # Setup: Create users and 50 transfers
    api.create_user(user_aj, 100000.00)
    api.create_user(user_ak, 10000.00)

    for i in range(50):
        api.create_transfer(user_aj, user_ak, 100.00)

    # Wait a bit for transfers to be created
    import time
    time.sleep(2)

    # Test 1: size=1 (minimum)
    response = api.get_transfer_history(user_aj, page=0, size=1)
    assert response.status_code == 200
    data = response.json()
    assert len(data["transfers"]) == 1
    assert data["pagination"]["pageSize"] == 1
    assert data["pagination"]["totalElements"] >= 50

    # Test 2: size=100 (maximum)
    response = api.get_transfer_history(user_aj, page=0, size=100)
    assert response.status_code == 200
    data = response.json()
    assert len(data["transfers"]) >= 50
    assert data["pagination"]["pageSize"] == 100

    # Test 3: size=0 (below minimum)
    response = api.get_transfer_history(user_aj, page=0, size=0)
    assert response.status_code == 400

    # Test 4: size=101 (above maximum)
    response = api.get_transfer_history(user_aj, page=0, size=101)
    assert response.status_code == 400


@pytest.mark.edge_cases
def test_IT024_exactly_10_minute_cancellation_boundary(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-024: Exactly 10-minute cancellation boundary.

    Verifies consistent behavior at the boundary (exactly 10 minutes):
    - Either always allow or always reject
    - Boundary definition is clear
    """
    user_al = f"{unique_user_id}_AL"
    user_am = f"{unique_user_id}_AM"

    # Setup: Create users
    api.create_user(user_al, 1000.00)
    api.create_user(user_am, 500.00)

    # Create transfer
    response = api.create_transfer(user_al, user_am, 200.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]

    # Simulate transfer created exactly 10 minutes ago
    db_cursor.execute(
        "UPDATE transfers SET created_at = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE id = %s",
        (transfer_id,)
    )

    # Attempt to cancel exactly at 10-minute boundary
    response = api.cancel_transfer(transfer_id)

    # Verify consistent behavior
    # Either 200 OK (allowed) or 400 BAD_REQUEST (not allowed)
    assert response.status_code in [200, 400]

    # Document the boundary behavior
    if response.status_code == 200:
        # Boundary is inclusive (≤ 10 minutes allowed)
        db_cursor.execute(
            "SELECT status FROM transfers WHERE id = %s",
            (transfer_id,)
        )
        transfer = db_cursor.fetchone()
        assert transfer["status"] == "CANCELLED"
    else:
        # Boundary is exclusive (< 10 minutes allowed)
        error_data = response.json()
        assert "window" in error_data.get("message", "").lower() or \
               "expired" in error_data.get("message", "").lower()


@pytest.mark.edge_cases
def test_IT025_negative_amount_validation(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-025: Negative amount validation.

    Verifies:
    - Attempt transfer with amount=-100.00
    - Returns 400 BAD_REQUEST with "must be greater than 0"
    - No transfer record created
    """
    user_an = f"{unique_user_id}_AN"
    user_ao = f"{unique_user_id}_AO"

    # Setup: Create users
    api.create_user(user_an, 1000.00)
    api.create_user(user_ao, 500.00)

    # Attempt transfer with negative amount
    response = api.create_transfer(user_an, user_ao, -100.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "greater than 0" in error_data.get("message", "").lower() or \
           "positive" in error_data.get("message", "").lower() or \
           "must be" in error_data.get("message", "").lower()

    # Verify no transfer record created
    db_cursor.execute(
        "SELECT COUNT(*) as count FROM transfers WHERE from_user_id = %s AND to_user_id = %s",
        (user_an, user_ao)
    )
    result = db_cursor.fetchone()
    assert result["count"] == 0


@pytest.mark.edge_cases
def test_IT026_zero_amount_validation(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-026: Zero amount validation.

    Verifies:
    - Attempt transfer with amount=0.00
    - Returns 400 BAD_REQUEST with "must be greater than 0"
    - No transfer record created
    """
    user_ap = f"{unique_user_id}_AP"
    user_aq = f"{unique_user_id}_AQ"

    # Setup: Create users
    api.create_user(user_ap, 1000.00)
    api.create_user(user_aq, 500.00)

    # Attempt transfer with zero amount
    response = api.create_transfer(user_ap, user_aq, 0.00)
    assert response.status_code == 400
    error_data = response.json()
    assert "greater than 0" in error_data.get("message", "").lower() or \
           "positive" in error_data.get("message", "").lower()

    # Verify no transfer record created
    db_cursor.execute(
        "SELECT COUNT(*) as count FROM transfers WHERE from_user_id = %s AND to_user_id = %s",
        (user_ap, user_aq)
    )
    result = db_cursor.fetchone()
    assert result["count"] == 0


@pytest.mark.edge_cases
def test_IT027_very_large_amount_handling(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-027: Very large amount handling.

    Verifies:
    - Create user with balance near DECIMAL(15,2) max
    - Execute transfer with very large amount
    - Amounts stored with precision to 2 decimal places
    - No overflow errors
    """
    user_ar = f"{unique_user_id}_AR"
    user_as = f"{unique_user_id}_AS"

    # DECIMAL(15,2) max is 9999999999999.99
    large_balance = 9999999999999.99
    transfer_amount = 5000000000000.00

    # Setup: Create users
    response = api.create_user(user_ar, large_balance)
    assert response.status_code == 201
    assert_balance(response.json()["balance"], large_balance)

    response = api.create_user(user_as, 1000.00)
    assert response.status_code == 201

    # Execute transfer with large amount
    response = api.create_transfer(user_ar, user_as, transfer_amount)
    assert response.status_code == 201
    transfer_id = response.json()["id"]

    # Wait for completion
    from helpers.wait_utils import wait_for_transfer_completion
    wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED", timeout=30)

    # Verify amounts stored with precision
    db_cursor.execute(
        "SELECT amount FROM transfers WHERE id = %s",
        (transfer_id,)
    )
    transfer = db_cursor.fetchone()
    assert_balance(transfer["amount"], transfer_amount)

    # Verify balances calculated correctly
    expected_balance_ar = large_balance - transfer_amount
    expected_balance_as = 1000.00 + transfer_amount

    response = api.get_balance(user_ar)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], expected_balance_ar)

    response = api.get_balance(user_as)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], expected_balance_as)


@pytest.mark.edge_cases
def test_IT028_special_characters_in_userId(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-028: Special characters in userId.

    Tests:
    - Valid special characters (underscore, hyphen): user_test-001
    - Invalid special characters (@ # space): Should fail if restricted
    - SQL injection prevention: user'; DROP TABLE users;--

    Verifies safe handling without executing malicious code.
    """
    # Test 1: Valid special characters (underscore, hyphen)
    user_valid = f"user_test-001_{unique_user_id[:6]}"
    response = api.create_user(user_valid, 1000.00)
    assert response.status_code == 201
    data = response.json()
    assert data["userId"] == user_valid

    # Verify in database
    db_cursor.execute(
        "SELECT user_id FROM user_balances WHERE user_id = %s",
        (user_valid,)
    )
    result = db_cursor.fetchone()
    assert result["user_id"] == user_valid

    # Test 2: Invalid special characters (@ # space)
    invalid_users = [
        f"user@test_{unique_user_id[:6]}",
        f"user#test_{unique_user_id[:6]}",
        f"user test_{unique_user_id[:6]}"
    ]

    for invalid_user in invalid_users:
        response = api.create_user(invalid_user, 1000.00)
        # May succeed or fail depending on validation rules
        # If succeeds, verify data integrity
        if response.status_code == 201:
            db_cursor.execute(
                "SELECT user_id FROM user_balances WHERE user_id = %s",
                (invalid_user,)
            )
            result = db_cursor.fetchone()
            assert result is not None

    # Test 3: SQL injection prevention
    sql_injection_user = f"user'; DROP TABLE user_balances;--"
    response = api.create_user(sql_injection_user, 1000.00)

    # Should either reject (400) or safely store as literal string (201)
    if response.status_code == 201:
        # Verify tables still exist
        db_cursor.execute("SHOW TABLES LIKE 'user_balances'")
        tables = db_cursor.fetchall()
        assert len(tables) > 0, "Table was dropped - SQL injection vulnerability!"

        # Verify user stored as literal string
        db_cursor.execute(
            "SELECT user_id FROM user_balances WHERE user_id = %s",
            (sql_injection_user,)
        )
        result = db_cursor.fetchone()
        assert result is not None
        assert result["user_id"] == sql_injection_user

    # Verify total user count is reasonable
    db_cursor.execute("SELECT COUNT(*) as count FROM user_balances")
    result = db_cursor.fetchone()
    assert result["count"] < 100  # Shouldn't have exploded
