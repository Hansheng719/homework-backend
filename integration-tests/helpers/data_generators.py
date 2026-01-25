"""Test data generation utilities."""
import random
import string
from faker import Faker

fake = Faker()


def generate_user_id(prefix: str = "user") -> str:
    """Generate a random user ID."""
    random_suffix = ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"{prefix}_{random_suffix}"


def generate_amount(min_amount: float = 1.0, max_amount: float = 10000.0) -> float:
    """Generate a random amount."""
    return round(random.uniform(min_amount, max_amount), 2)


def generate_initial_balance(min_balance: float = 100.0, max_balance: float = 100000.0) -> float:
    """Generate a random initial balance."""
    return round(random.uniform(min_balance, max_balance), 2)
