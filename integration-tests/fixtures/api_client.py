"""API client helper functions."""
import pytest
import requests
from typing import Dict, Any, Optional
from config.test_config import config


class APIClient:
    """HTTP API client wrapper."""

    def __init__(self, session: requests.Session, base_url: str):
        self.session = session
        self.base_url = base_url

    def create_user(
        self,
        user_id: str,
        initial_balance: float
    ) -> requests.Response:
        """Create a new user."""
        return self.session.post(
            f"{self.base_url}/users",
            json={
                "userId": user_id,
                "initialBalance": initial_balance
            },
            timeout=config.app.timeout
        )

    def get_balance(self, user_id: str) -> requests.Response:
        """Get user balance."""
        return self.session.get(
            f"{self.base_url}/users/{user_id}/balance",
            timeout=config.app.timeout
        )

    def create_transfer(
        self,
        from_user_id: str,
        to_user_id: str,
        amount: float
    ) -> requests.Response:
        """Create a transfer."""
        return self.session.post(
            f"{self.base_url}/transfers",
            json={
                "fromUserId": from_user_id,
                "toUserId": to_user_id,
                "amount": amount
            },
            timeout=config.app.timeout
        )

    def get_transfer_history(
        self,
        user_id: str,
        page: int = 0,
        size: int = 20
    ) -> requests.Response:
        """Get transfer history."""
        return self.session.get(
            f"{self.base_url}/transfers",
            params={
                "userId": user_id,
                "page": page,
                "size": size
            },
            timeout=config.app.timeout
        )

    def cancel_transfer(self, transfer_id: int) -> requests.Response:
        """Cancel a transfer."""
        return self.session.post(
            f"{self.base_url}/transfers/{transfer_id}/cancel",
            timeout=config.app.timeout
        )


@pytest.fixture
def api(api_client: requests.Session, api_base_url: str) -> APIClient:
    """Provide API client helper."""
    return APIClient(api_client, api_base_url)
