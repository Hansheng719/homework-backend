"""Data Integrity - IT-015 to IT-017."""
import pytest
from decimal import Decimal
from concurrent.futures import ThreadPoolExecutor, as_completed
from fixtures.api_client import APIClient
from helpers.wait_utils import wait_for_transfer_completion
from helpers.assertions import assert_balance


@pytest.mark.data_integrity
@pytest.mark.concurrent
def test_IT015_concurrent_transfer_balance_consistency(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-015: Concurrent transfers maintain balance consistency.

    Executes two concurrent transfers from the same user and verifies:
    - Final balance is consistent regardless of execution order
    - No negative balances
    - Sum of transfers equals balance change
    - No data race conditions
    """
    user_v = f"{unique_user_id}_V"
    user_w = f"{unique_user_id}_W"
    user_x = f"{unique_user_id}_X"

    # Setup: Create users
    api.create_user(user_v, 1000.00)
    api.create_user(user_w, 500.00)
    api.create_user(user_x, 500.00)

    # Define concurrent transfer operations
    def transfer_1():
        return api.create_transfer(user_v, user_w, 300.00)

    def transfer_2():
        return api.create_transfer(user_v, user_x, 400.00)

    # Execute concurrently
    with ThreadPoolExecutor(max_workers=2) as executor:
        future1 = executor.submit(transfer_1)
        future2 = executor.submit(transfer_2)

        response1 = future1.result()
        response2 = future2.result()

    # Collect transfer IDs
    transfer_ids = []
    if response1.status_code == 201:
        transfer_ids.append(response1.json()["id"])
    if response2.status_code == 201:
        transfer_ids.append(response2.json()["id"])

    # Wait for all successful transfers to complete
    for transfer_id in transfer_ids:
        wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED", timeout=30)

    # Verify results
    # Scenario A: Both succeed (user_v has enough balance)
    if response1.status_code == 201 and response2.status_code == 201:
        # userV should have 300.00 (1000 - 300 - 400)
        response = api.get_balance(user_v)
        assert response.status_code == 200
        assert_balance(response.json()["balance"], 300.00)

        response = api.get_balance(user_w)
        assert response.status_code == 200
        assert_balance(response.json()["balance"], 800.00)

        response = api.get_balance(user_x)
        assert response.status_code == 200
        assert_balance(response.json()["balance"], 900.00)

    # Scenario B: One succeeds, one fails (insufficient balance)
    elif response1.status_code == 201 and response2.status_code == 400:
        # Transfer 1 succeeded, transfer 2 failed
        response = api.get_balance(user_v)
        assert response.status_code == 200
        assert_balance(response.json()["balance"], 700.00)

    elif response1.status_code == 400 and response2.status_code == 201:
        # Transfer 2 succeeded, transfer 1 failed
        response = api.get_balance(user_v)
        assert response.status_code == 200
        assert_balance(response.json()["balance"], 600.00)

    else:
        pytest.fail(f"Unexpected response combination: {response1.status_code}, {response2.status_code}")

    # Verify no negative balance
    db_cursor.execute(
        "SELECT balance FROM user_balances WHERE user_id = %s",
        (user_v,)
    )
    result = db_cursor.fetchone()
    assert Decimal(str(result["balance"])) >= Decimal("0")


@pytest.mark.data_integrity
def test_IT016_multiple_sequential_transfers_accuracy(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-016: Multiple sequential transfers maintain accuracy.

    Executes 4 sequential transfers in a chain:
    - userY (5000) → userZ (500): 1200
    - userZ → userAA (1000): 400
    - userAA → userZ (1000): 500
    - userZ → userAA (1000): 600

    Verifies balance after each step and final balances:
    - userY: 3800.00
    - userZ: 1300.00
    - userAA: 2900.00
    - System total balance conserved (8000.00)
    """
    user_y = f"{unique_user_id}_Y"
    user_z = f"{unique_user_id}_Z"
    user_aa = f"{unique_user_id}_AA"

    # Setup: Create users
    api.create_user(user_y, 5000.00)
    api.create_user(user_z, 1500.00)
    api.create_user(user_aa, 1500.00)

    initial_total = 8000.00

    # Transfer 1: userY → userZ: 1200
    response = api.create_transfer(user_y, user_z, 1200.00)
    assert response.status_code == 201
    transfer_id_1 = response.json()["id"]
    wait_for_transfer_completion(db_cursor, transfer_id_1, "COMPLETED")

    response = api.get_balance(user_y)
    assert_balance(response.json()["balance"], 3800.00)
    response = api.get_balance(user_z)
    assert_balance(response.json()["balance"], 2700.00)

    # Transfer 2: userZ → userAA: 400
    response = api.create_transfer(user_z, user_aa, 400.00)
    assert response.status_code == 201
    transfer_id_2 = response.json()["id"]
    wait_for_transfer_completion(db_cursor, transfer_id_2, "COMPLETED")

    response = api.get_balance(user_z)
    assert_balance(response.json()["balance"], 2300.00)
    response = api.get_balance(user_aa)
    assert_balance(response.json()["balance"], 1900.00)

    # Transfer 3: userAA → userZ: 500
    response = api.create_transfer(user_aa, user_z, 500.00)
    assert response.status_code == 201
    transfer_id_3 = response.json()["id"]
    wait_for_transfer_completion(db_cursor, transfer_id_3, "COMPLETED")

    response = api.get_balance(user_aa)
    assert_balance(response.json()["balance"], 1400.00)
    response = api.get_balance(user_z)
    assert_balance(response.json()["balance"], 2800.00)

    # Transfer 4: userZ → userAA: 1500
    response = api.create_transfer(user_z, user_aa, 1500.00)
    assert response.status_code == 201
    transfer_id_4 = response.json()["id"]
    wait_for_transfer_completion(db_cursor, transfer_id_4, "COMPLETED")

    # Verify final balances
    response = api.get_balance(user_y)
    assert_balance(response.json()["balance"], 3800.00)

    response = api.get_balance(user_z)
    assert_balance(response.json()["balance"], 1300.00)

    response = api.get_balance(user_aa)
    assert_balance(response.json()["balance"], 2900.00)

    # Verify system total balance conserved
    db_cursor.execute(
        "SELECT SUM(balance) as total FROM user_balances WHERE user_id IN (%s, %s, %s)",
        (user_y, user_z, user_aa)
    )
    result = db_cursor.fetchone()
    assert_balance(result["total"], initial_total)


@pytest.mark.data_integrity
@pytest.mark.slow
def test_IT017_system_wide_total_balance_conservation(
    api: APIClient,
    db_cursor,
    unique_user_id
):
    """
    IT-017: System-wide total balance conservation law.

    Creates 5 users with total balance 10000.00, executes 10 transfers, and verifies:
    - Total balance remains 10000.00 after each transfer
    - No negative balances exist
    - Balance conservation law holds
    """
    users = [f"{unique_user_id}_{i}" for i in range(5)]
    initial_balances = [2000.00, 2500.00, 1500.00, 2000.00, 2000.00]
    initial_total = sum(initial_balances)

    # Setup: Create users
    for user, balance in zip(users, initial_balances):
        api.create_user(user, balance)

    # Execute 10 transfers
    transfers = [
        (users[0], users[1], 500.00),
        (users[1], users[2], 300.00),
        (users[2], users[3], 400.00),
        (users[3], users[4], 200.00),
        (users[4], users[0], 600.00),
        (users[1], users[3], 250.00),
        (users[2], users[0], 150.00),
        (users[3], users[1], 100.00),
        (users[4], users[2], 350.00),
        (users[0], users[4], 450.00),
    ]

    for from_user, to_user, amount in transfers:
        response = api.create_transfer(from_user, to_user, amount)
        assert response.status_code == 201
        transfer_id = response.json()["id"]
        wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED")

        # Verify total balance after each transfer
        db_cursor.execute(
            "SELECT SUM(balance) as total FROM user_balances WHERE user_id IN (%s, %s, %s, %s, %s)",
            tuple(users)
        )
        result = db_cursor.fetchone()
        assert_balance(result["total"], initial_total)

    # Verify no negative balances
    db_cursor.execute(
        "SELECT user_id, balance FROM user_balances WHERE user_id IN (%s, %s, %s, %s, %s) AND balance < 0",
        tuple(users)
    )
    negative_balances = db_cursor.fetchall()
    assert len(negative_balances) == 0, f"Found negative balances: {negative_balances}"

    # Final total balance verification
    db_cursor.execute(
        "SELECT SUM(balance) as total FROM user_balances WHERE user_id IN (%s, %s, %s, %s, %s)",
        tuple(users)
    )
    result = db_cursor.fetchone()
    assert_balance(result["total"], initial_total)
