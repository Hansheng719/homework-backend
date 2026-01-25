"""Test configuration for integration tests."""
import os
from pydantic import BaseModel, Field


class AppConfig(BaseModel):
    """Application configuration."""
    base_url: str = Field(
        default=os.getenv("API_BASE_URL", "http://localhost:8080")
    )
    health_check_endpoint: str = Field(default="/actuator/health")
    timeout: int = Field(default=30)


class MySQLConfig(BaseModel):
    """MySQL database configuration."""
    host: str = Field(default=os.getenv("MYSQL_HOST", "localhost"))
    port: int = Field(default=int(os.getenv("MYSQL_PORT", "3306")))
    database: str = Field(default=os.getenv("MYSQL_DATABASE", "taskdb"))
    user: str = Field(default=os.getenv("MYSQL_USER", "taskuser"))
    password: str = Field(default=os.getenv("MYSQL_PASSWORD", "taskpass"))
    charset: str = Field(default="utf8mb4")


class RedisConfig(BaseModel):
    """Redis configuration."""
    host: str = Field(default=os.getenv("REDIS_HOST", "localhost"))
    port: int = Field(default=int(os.getenv("REDIS_PORT", "6379")))
    db: int = Field(default=0)
    decode_responses: bool = Field(default=True)


class TestConfig(BaseModel):
    """Global test configuration."""
    app: AppConfig = Field(default_factory=AppConfig)
    mysql: MySQLConfig = Field(default_factory=MySQLConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)

    # Test execution settings
    async_wait_timeout: int = Field(default=30)  # seconds
    async_poll_interval: float = Field(default=0.5)  # seconds
    cleanup_enabled: bool = Field(
        default=os.getenv("CLEANUP_ENABLED", "true").lower() == "true"
    )


# Global test config instance
config = TestConfig()
