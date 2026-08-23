from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

import aiohttp

from app.config import Settings


class PlategaApiError(RuntimeError):
    def __init__(self, endpoint: str, status: int | None = None) -> None:
        self.endpoint = endpoint
        self.status = status
        message = f"Platega API request failed: endpoint={endpoint}"
        if status is not None:
            message += f" status={status}"
        super().__init__(message)


class PlategaClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._session: aiohttp.ClientSession | None = None
        platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
        self._base_url = str(platega.get("base_url") or "https://app.platega.io").rstrip("/")

    async def __aenter__(self) -> "PlategaClient":
        timeout = aiohttp.ClientTimeout(total=self._settings.request_timeout)
        self._session = aiohttp.ClientSession(timeout=timeout)
        return self

    async def __aexit__(self, *_: object) -> None:
        if self._session is not None:
            await self._session.close()

    @property
    def session(self) -> aiohttp.ClientSession:
        if self._session is None:
            raise RuntimeError("PlategaClient is not started")
        return self._session

    def _headers(self) -> dict[str, str]:
        if not self._settings.platega_merchant_id or not self._settings.platega_api_key:
            raise PlategaApiError("auth")
        return {
            "X-MerchantId": self._settings.platega_merchant_id,
            "X-Secret": self._settings.platega_api_key,
            "Content-Type": "application/json",
        }

    async def _request(
        self,
        method: str,
        path: str,
        *,
        body: Mapping[str, Any] | None = None,
        endpoint: str,
    ) -> Any:
        try:
            async with self.session.request(
                method,
                f"{self._base_url}{path}",
                headers=self._headers(),
                json=body,
                allow_redirects=False,
            ) as response:
                if response.status < 200 or response.status >= 300:
                    raise PlategaApiError(endpoint, response.status)
                if response.content_length == 0:
                    return None
                return await response.json(content_type=None)
        except TimeoutError as exc:
            raise PlategaApiError(endpoint) from exc
        except asyncio.TimeoutError as exc:
            raise PlategaApiError(endpoint) from exc
        except aiohttp.ClientError as exc:
            raise PlategaApiError(endpoint) from exc

    async def create_transaction(
        self,
        *,
        payment_method: int,
        amount_rub: int | float,
        description: str,
        return_url: str,
        failed_url: str,
        payload: str,
        telegram_id: int,
        username: str | None,
    ) -> dict[str, Any]:
        amount = round(float(amount_rub), 2)
        payment_amount: int | float = int(amount) if amount.is_integer() else amount
        body = {
            "paymentMethod": payment_method,
            "paymentDetails": {
                "amount": payment_amount,
                "currency": "RUB",
            },
            "description": description,
            "return": return_url,
            "failedUrl": failed_url,
            "payload": payload,
            "metadata": {
                "userId": str(telegram_id),
                "userName": username or str(telegram_id),
            },
        }
        payload_data = await self._request("POST", "/transaction/process", body=body, endpoint="transaction.process")
        return payload_data if isinstance(payload_data, dict) else {}

    async def get_transaction(self, transaction_id: str) -> dict[str, Any] | None:
        payload = await self._request("GET", f"/transaction/{transaction_id}", endpoint="transaction.status")
        return payload if isinstance(payload, dict) else None
