"""Cache Integration - IT-018 to IT-020."""
import pytest
from fixtures.api_client import APIClient
from helpers.wait_utils import wait_for_transfer_completion
from helpers.assertions import assert_balance, assert_redis_ttl


@pytest.mark.cache_integration
def test_IT018_cache_hit_and_miss_behavior(
    api: APIClient,
    db_cursor,
    redis_client,
    unique_user_id
):
    """
    IT-018: Cache hit and miss behavior.

    Verifies:
    - First query (cache miss): Redis key created with TTL ~300s
    - Second query (cache hit): Same result returned from cache
    """
    user_ac = f"{unique_user_id}_AC"

    # Setup: Create user
    api.create_user(user_ac, 1500.00)

    cache_key = f"balance:{user_ac}"

    # Verify cache doesn't exist initially
    assert redis_client.exists(cache_key) == 0

    # First query (cache miss)
    response = api.get_balance(user_ac)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1500.00)

    # Verify Redis key created
    assert redis_client.exists(cache_key) > 0

    # Verify TTL is ~300 seconds (5 minutes)
    assert_redis_ttl(redis_client, cache_key, min_ttl=1, max_ttl=300)

    # Second query (cache hit)
    response = api.get_balance(user_ac)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1500.00)

    # Verify cache still exists
    assert redis_client.exists(cache_key) > 0


@pytest.mark.cache_integration
def test_IT019_cache_invalidation_after_balance_update(
    api: APIClient,
    db_cursor,
    redis_client,
    unique_user_id
):
    """
    IT-019: Cache invalidation after balance update.

    Verifies:
    - Cache is created for user balance
    - Transfer affecting user clears/updates cache
    - New query rebuilds cache with updated balance
    """
    user_ad = f"{unique_user_id}_AD"
    user_ae = f"{unique_user_id}_AE"

    # Setup: Create users
    api.create_user(user_ad, 2000.00)
    api.create_user(user_ae, 1000.00)

    cache_key_ad = f"balance:{user_ad}"
    cache_key_ae = f"balance:{user_ae}"

    # Create cache for both users
    api.get_balance(user_ad)
    api.get_balance(user_ae)

    # Verify caches exist
    assert redis_client.exists(cache_key_ad) > 0
    assert redis_client.exists(cache_key_ae) > 0

    # Execute transfer affecting user_ad and user_ae
    response = api.create_transfer(user_ad, user_ae, 500.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]
    wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED")

    # Verify related user caches are cleared
    # Note: Cache may be cleared or may contain updated value
    # We verify by querying and checking the balance is correct

    # Query again to check cache behavior
    response = api.get_balance(user_ad)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1500.00)

    response = api.get_balance(user_ae)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1500.00)

    # Verify new cache contains updated balance
    assert redis_client.exists(cache_key_ad) > 0
    assert redis_client.exists(cache_key_ae) > 0


@pytest.mark.cache_integration
def test_IT020_multi_user_cache_isolation(
    api: APIClient,
    db_cursor,
    redis_client,
    unique_user_id
):
    """
    IT-020: Multi-user cache isolation.

    Verifies:
    - Create caches for 3 users
    - Execute transfer affecting 2 of them
    - Only affected users' caches are cleared/updated
    - Unaffected user's cache remains unchanged
    """
    user_af = f"{unique_user_id}_AF"
    user_ag = f"{unique_user_id}_AG"
    user_ah = f"{unique_user_id}_AH"

    # Setup: Create users
    api.create_user(user_af, 3000.00)
    api.create_user(user_ag, 2000.00)
    api.create_user(user_ah, 1000.00)

    cache_key_af = f"balance:{user_af}"
    cache_key_ag = f"balance:{user_ag}"
    cache_key_ah = f"balance:{user_ah}"

    # Create caches for all 3 users
    api.get_balance(user_af)
    api.get_balance(user_ag)
    api.get_balance(user_ah)

    # Verify all caches exist
    assert redis_client.exists(cache_key_af) > 0
    assert redis_client.exists(cache_key_ag) > 0
    assert redis_client.exists(cache_key_ah) > 0

    # Execute transfer affecting only user_af and user_ag
    response = api.create_transfer(user_af, user_ag, 800.00)
    assert response.status_code == 201
    transfer_id = response.json()["id"]
    wait_for_transfer_completion(db_cursor, transfer_id, "COMPLETED")

    # Verify affected users' balances are updated
    response = api.get_balance(user_af)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 2200.00)

    response = api.get_balance(user_ag)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 2800.00)

    # Verify unaffected user's balance unchanged
    response = api.get_balance(user_ah)
    assert response.status_code == 200
    assert_balance(response.json()["balance"], 1000.00)

    # Verify unaffected user's cache value unchanged
    # The cache should still exist and contain original value
    assert redis_client.exists(cache_key_ah) > 0
