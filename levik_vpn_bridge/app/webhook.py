from __future__ import annotations

import asyncio
import base64
import hmac
import html
import json
import logging
import re
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from aiohttp import web
from aiogram import Bot

from app.config import Settings
from app.cabinet_api import register_cabinet_routes
from app.cabinet_auth import MAX_BODY_BYTES
from app.delivery import deliver_paid_order, payment_payload
from app.happ_routing import is_enabled as happ_routing_enabled
from app.happ_routing import landing_page as happ_routing_landing_page
from app.happ_routing import routing_deeplink
from app.keyboards import access_success_keyboard, back_home_keyboard
from app.orders import OrderStore
from app.platega import PlategaApiError, PlategaClient
from app.remnawave import RemnawaveClient
from app.multi_subscription import combine_subscription_payloads, public_url
from app import wdtt

logger = logging.getLogger(__name__)

HAPP_SUBSCRIPTION_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{6,80}$")
HAPP_SUBSCRIPTION_BASE_URL = "https://sub.leviknet.com:2096"
MULTI_SUBSCRIPTION_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{32,80}$")
MULTI_SOURCE_HOSTS = {"sub.leviknet.com", "levik.levafart.store"}
FORWARDED_SUBSCRIPTION_HEADER_DENYLIST = {
    "authorization",
    "connection",
    "content-length",
    "cookie",
    "host",
    "proxy-authorization",
    "transfer-encoding",
}
GEO_ASSET_DIR = Path(__file__).resolve().parent.parent / "assets"
GEO_ASSET_FILENAMES = frozenset({"geoip.dat", "geosite.dat"})


async def start_payment_webhook(
    *,
    bot: Bot,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> web.AppRunner | None:
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    platega_enabled = bool(platega.get("enabled", False))
    cabinet_enabled = bool(settings.cabinet_bridge_secret)
    multi_config = settings.data.get("multi_subscription") if isinstance(settings.data.get("multi_subscription"), dict) else {}
    multi_enabled = bool(multi_config.get("enabled", False))
    if not platega_enabled and not cabinet_enabled and not multi_enabled:
        logger.info("HTTP service is disabled")
        return None

    app = web.Application(client_max_size=MAX_BODY_BYTES)
    app["bot"] = bot
    app["settings"] = settings
    app["remnawave"] = remnawave
    app["order_store"] = order_store
    if platega_enabled:
        app.router.add_post("/levik-vpn-bot/platega/callback", handle_platega_callback)
    if cabinet_enabled:
        register_cabinet_routes(app)
    app.router.add_get("/levik-vpn-bot/wdtt/{token}.json", handle_wdtt_subscription)
    app.router.add_get("/levik-vpn-bot/happ-routing", handle_happ_routing)
    app.router.add_get("/levik-vpn-bot/happ-routing/off", handle_happ_routing_off)
    app.router.add_get("/levik-vpn-bot/happ-import/geo/{filename}", handle_happ_geo_asset)
    app.router.add_get("/levik-vpn-bot/happ-import/multi/{token}", handle_happ_import_multi)
    app.router.add_get("/levik-vpn-bot/happ-import/{token}", handle_happ_import)
    app.router.add_get("/multi/{token}", handle_multi_subscription)
    app.router.add_get("/health", handle_health)

    runner = web.AppRunner(app)
    await runner.setup()
    host = str(platega.get("webhook_host") or "0.0.0.0")
    port = int(platega.get("webhook_port") or 8096)
    site = web.TCPSite(runner, host=host, port=port)
    await site.start()
    logger.info("Bot HTTP service listening on %s:%s", host, port)
    return runner


async def handle_health(_: web.Request) -> web.Response:
    return web.json_response({"ok": True})


async def handle_happ_routing(request: web.Request) -> web.Response:
    return _happ_routing_response(request, enable=True)


async def handle_happ_routing_off(request: web.Request) -> web.Response:
    return _happ_routing_response(request, enable=False)


async def handle_happ_geo_asset(request: web.Request) -> web.StreamResponse:
    filename = str(request.match_info.get("filename") or "")
    if filename not in GEO_ASSET_FILENAMES:
        return web.Response(status=404)

    asset_path = GEO_ASSET_DIR / filename
    if not asset_path.is_file():
        logger.error("Happ geo asset is missing: %s", filename)
        return web.Response(status=503)

    return web.FileResponse(
        asset_path,
        headers={
            "Cache-Control": "public, max-age=86400",
            "Content-Disposition": f'inline; filename="{filename}"',
            "X-Content-Type-Options": "nosniff",
        },
    )


async def handle_happ_import(request: web.Request) -> web.Response:
    token = str(request.match_info.get("token") or "")
    if not HAPP_SUBSCRIPTION_TOKEN_PATTERN.fullmatch(token):
        return web.json_response({"ok": False}, status=404)

    subscription_url = f"{HAPP_SUBSCRIPTION_BASE_URL}/{token}"
    return _happ_import_response(subscription_url)


async def handle_happ_import_multi(request: web.Request) -> web.Response:
    token = str(request.match_info.get("token") or "")
    if not MULTI_SUBSCRIPTION_TOKEN_PATTERN.fullmatch(token):
        return web.json_response({"ok": False}, status=404)
    settings: Settings = request.app["settings"]
    return _happ_import_response(public_url(settings, token))


def _happ_import_response(subscription_url: str) -> web.Response:
    raw_deeplink = f"happ://add/{subscription_url}"
    deeplink = html.escape(raw_deeplink, quote=True)
    deeplink_json = json.dumps(raw_deeplink)
    return web.Response(
        text=f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Подключение Levik VPN</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; }}
    body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: #10141c; color: #f5f7fb; }}
    main {{ width: min(34rem, calc(100% - 2rem)); box-sizing: border-box; padding: 2rem; border-radius: 1.25rem; background: #1a2130; }}
    h1 {{ margin: 0 0 1rem; font-size: 1.6rem; }}
    p {{ margin: 0 0 1.5rem; color: #cbd4e5; line-height: 1.5; }}
    a {{ display: block; padding: 0.9rem 1rem; border-radius: 0.8rem; background: #4f7cff; color: white; text-align: center; text-decoration: none; font-weight: 700; }}
  </style>
</head>
<body>
  <main>
    <h1>Подключение Levik VPN</h1>
    <p>Если Happ не открылся автоматически, нажмите кнопку ниже.</p>
    <a href="{deeplink}">Открыть в Happ</a>
  </main>
  <script>window.location.replace({deeplink_json});</script>
</body>
</html>
""",
        content_type="text/html",
        charset="utf-8",
        headers={
            "Cache-Control": "no-store",
            "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
        },
    )


async def handle_multi_subscription(request: web.Request) -> web.Response:
    token = str(request.match_info.get("token") or "")
    if not MULTI_SUBSCRIPTION_TOKEN_PATTERN.fullmatch(token):
        return web.Response(status=404)
    settings: Settings = request.app["settings"]
    order_store: OrderStore = request.app["order_store"]
    remnawave: RemnawaveClient = request.app["remnawave"]
    record = order_store.get_multi_subscription_by_token(token)
    if record is None:
        return web.Response(status=404)

    primary_reference = str(record.get("primary_user_uuid") or "")
    mobile_reference = str(record.get("mobile_user_uuid") or "")
    primary, mobile = await asyncio.gather(
        remnawave.get_user_by_uuid(primary_reference),
        remnawave.get_user_by_uuid(mobile_reference),
    )
    if primary is None or mobile is None:
        return web.Response(status=404)
    if int(primary.get("telegramId") or 0) != int(record.get("telegram_id") or 0):
        return web.Response(status=404)
    if int(mobile.get("telegramId") or 0) != int(record.get("telegram_id") or 0):
        return web.Response(status=404)

    forwarded_headers = _subscription_request_headers(request)
    source_urls = [
        str(primary.get("subscriptionUrl") or ""),
        str(mobile.get("subscriptionUrl") or ""),
    ]
    safe_urls = [url for url in source_urls if _valid_multi_source_url(url)]
    if not safe_urls:
        return web.Response(status=502)
    results = await asyncio.gather(
        *(remnawave.fetch_subscription(url, headers=forwarded_headers) for url in safe_urls),
        return_exceptions=True,
    )
    payloads = [
        result[1]
        for result in results
        if isinstance(result, tuple) and result[0] == 200
    ]
    body = combine_subscription_payloads(payloads)
    if not body:
        return web.Response(
            status=403,
            headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"},
        )

    used_bytes = _non_negative_int(
        (mobile.get("userTraffic") if isinstance(mobile.get("userTraffic"), dict) else {}).get("usedTrafficBytes")
    )
    limit_bytes = _non_negative_int(mobile.get("trafficLimitBytes"))
    expire_at = _unix_timestamp(primary.get("expireAt"))
    announcement = _multi_announcement(used_bytes, limit_bytes)
    response_headers = {
        "Cache-Control": "private, no-store",
        "Content-Disposition": "attachment; filename=levik-multi.txt",
        "X-Content-Type-Options": "nosniff",
        "profile-title": _base64_header("Levik Мультиподписка"),
        "profile-update-interval": "1",
        "profile-web-page-url": public_url(settings, token),
        "support-url": "https://t.me/leviksupportbot",
        "subscription-userinfo": (
            f"upload=0; download={used_bytes}; total={limit_bytes}; expire={expire_at}"
        ),
        "announce": _base64_header(announcement),
    }
    if happ_routing_enabled(settings.data):
        response_headers["routing"] = routing_deeplink(
            settings.data,
            shield_enabled=order_store.get_shield_enabled(_non_negative_int(primary.get("id"))),
        )
    return web.Response(
        body=body,
        content_type="text/plain",
        charset="utf-8",
        headers=response_headers,
    )


def _multi_announcement(used_bytes: int, limit_bytes: int) -> str:
    remaining_bytes = max(0, limit_bytes - used_bytes)
    return (
        "Перестало работать? Обнови подписку!\n"
        "🌍 Обычные серверы: трафик безлимит!\n"
        f"📱 Мобильный трафик: осталось {_format_gigabytes(remaining_bytes)} "
        f"из {_format_gigabytes(limit_bytes)}"
    )


def _subscription_request_headers(request: web.Request) -> dict[str, str]:
    result: dict[str, str] = {}
    for name, value in request.headers.items():
        normalized = name.lower()
        if (
            normalized in FORWARDED_SUBSCRIPTION_HEADER_DENYLIST
            or normalized.startswith("x-forwarded-")
            or len(name) > 128
            or len(value) > 2048
        ):
            continue
        result[name] = value
    result["Accept-Encoding"] = "identity"
    return result


def _valid_multi_source_url(value: str) -> bool:
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        return False
    token = parsed.path.removeprefix("/")
    return bool(
        parsed.scheme == "https"
        and parsed.hostname in MULTI_SOURCE_HOSTS
        and port == 2096
        and parsed.username is None
        and parsed.password is None
        and not parsed.query
        and not parsed.fragment
        and "/" not in token
        and HAPP_SUBSCRIPTION_TOKEN_PATTERN.fullmatch(token)
    )


def _non_negative_int(value: object) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError):
        return 0


def _unix_timestamp(value: object) -> int:
    if not isinstance(value, str) or not value:
        return 0
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return 0
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return max(0, int(parsed.timestamp()))


def _format_gigabytes(value: int) -> str:
    amount = value / (1024**3)
    rendered = f"{amount:.1f}".rstrip("0").rstrip(".").replace(".", ",")
    return f"{rendered} ГБ"


def _base64_header(value: str) -> str:
    encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
    return f"base64:{encoded}"


def _happ_routing_response(request: web.Request, *, enable: bool) -> web.Response:
    settings: Settings = request.app["settings"]
    if not happ_routing_enabled(settings.data):
        return web.json_response({"ok": False}, status=404)
    return web.Response(
        text=happ_routing_landing_page(settings.data, enable=enable),
        content_type="text/html",
        charset="utf-8",
        headers={
            "Cache-Control": "no-store",
            "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
        },
    )


async def handle_wdtt_subscription(request: web.Request) -> web.Response:
    settings: Settings = request.app["settings"]
    order_store: OrderStore = request.app["order_store"]
    token = str(request.match_info.get("token") or "").removesuffix(".json")
    access = order_store.get_wdtt_access_by_token(token)
    if access is None:
        return web.json_response({"ok": False}, status=404)
    return web.json_response(wdtt.subscription_payload(settings, access))


async def handle_platega_callback(request: web.Request) -> web.Response:
    settings: Settings = request.app["settings"]
    order_store: OrderStore = request.app["order_store"]
    bot: Bot = request.app["bot"]
    remnawave: RemnawaveClient = request.app["remnawave"]

    if not _valid_headers(request, settings):
        return web.json_response({"ok": False}, status=401)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False}, status=400)
    if not isinstance(payload, dict):
        return web.json_response({"ok": False}, status=400)

    status = str(payload.get("status") or "").upper()
    transaction_id = str(payload.get("id") or "")
    if not transaction_id:
        return web.json_response({"ok": False}, status=400)

    order = order_store.get_by_provider_payment(transaction_id)
    if order is None:
        return web.json_response({"ok": False}, status=404)
    order_id = int(order.get("id") or 0)
    telegram_id = int(order.get("telegram_id") or 0)
    is_account_actor = order_store.is_cabinet_account_actor(telegram_id)
    if order_id <= 0 or (telegram_id <= 0 and not is_account_actor):
        return web.json_response({"ok": False}, status=404)
    existing_transaction = str(order.get("provider_payment_charge_id") or "")
    if not transaction_id or not existing_transaction or existing_transaction != transaction_id:
        return web.json_response({"ok": False}, status=409)

    try:
        async with PlategaClient(settings) as client:
            provider_payload = await client.get_transaction(existing_transaction)
    except PlategaApiError:
        logger.warning("failed to verify Platega callback order_id=%s", order_id)
        return web.json_response({"ok": False}, status=502)
    transaction = _transaction_data(provider_payload)
    if not _valid_authoritative_transaction(
        callback=payload,
        transaction=transaction,
        order=order,
        settings=settings,
        expected_payload=payment_payload(order_id, telegram_id),
    ):
        logger.warning("rejected mismatched Platega callback order_id=%s", order_id)
        return web.json_response({"ok": False}, status=409)

    if status in {"CANCELED", "CANCELLED", "CHARGEBACKED"}:
        order_store.mark_payment_canceled(order_id, status.lower())
        return web.json_response({"ok": True})
    if status != "CONFIRMED":
        return web.json_response({"ok": True})

    paid_order = order_store.mark_paid(
        order_id=order_id,
        telegram_payment_charge_id="platega",
        provider_payment_charge_id=transaction_id or existing_transaction,
    )
    if paid_order is None:
        return web.json_response({"ok": False}, status=404)

    result = await deliver_paid_order(
        telegram_id=telegram_id,
        settings=settings,
        remnawave=remnawave,
        order_store=order_store,
        order=paid_order,
    )
    if result.user_text and not is_account_actor:
        await bot.send_message(
            telegram_id,
            result.user_text,
            reply_markup=access_success_keyboard(
                result.user_uuid,
                subscription_url=result.subscription_url,
                offer_happ_routing=result.offer_happ_routing,
            ),
        )
    if (
        not is_account_actor
        and result.referral_telegram_id
        and result.referral_text
    ):
        await bot.send_message(result.referral_telegram_id, result.referral_text, reply_markup=back_home_keyboard())
    if not result.success:
        logger.error("paid order delivery failed order_id=%s", order_id)
        return web.json_response({"ok": False}, status=500)
    return web.json_response({"ok": True})


def _valid_headers(request: web.Request, settings: Settings) -> bool:
    merchant_id = request.headers.get("X-MerchantId", "")
    secret = request.headers.get("X-Secret", "")
    return hmac.compare_digest(merchant_id, settings.platega_merchant_id) and hmac.compare_digest(
        secret,
        settings.platega_api_key,
    )


def _transaction_data(payload: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    for key in ("transaction", "data", "result"):
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    return payload


def _transaction_id(payload: dict[str, Any]) -> str:
    return str(payload.get("id") or payload.get("transactionId") or "")


def _transaction_status(payload: dict[str, Any]) -> str:
    return str(payload.get("status") or payload.get("paymentStatus") or "").upper()


def _transaction_payload(payload: dict[str, Any]) -> str:
    return str(payload.get("payload") or payload.get("externalId") or "")


def _transaction_amount(payload: dict[str, Any]) -> tuple[Decimal | None, str]:
    details = payload.get("paymentDetails") if isinstance(payload.get("paymentDetails"), dict) else {}
    raw_amount = payload.get("amount") if payload.get("amount") is not None else details.get("amount")
    raw_currency = payload.get("currency") if payload.get("currency") is not None else details.get("currency")
    try:
        amount = Decimal(str(raw_amount)).quantize(Decimal("0.01"))
    except (InvalidOperation, TypeError, ValueError):
        amount = None
    return amount, str(raw_currency or "").upper()


def _transaction_commission(payload: dict[str, Any]) -> Decimal | None:
    raw_commission = payload.get("comission")
    if raw_commission is None:
        raw_commission = payload.get("commission")
    try:
        return Decimal(str(raw_commission)).quantize(Decimal("0.01"))
    except (InvalidOperation, TypeError, ValueError):
        return None


def _transaction_payment_method(payload: dict[str, Any]) -> int:
    value = payload.get("paymentMethod")
    if value is None:
        details = payload.get("paymentDetails")
        if isinstance(details, dict):
            value = details.get("paymentMethod")
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _valid_authoritative_transaction(
    *,
    callback: dict[str, Any],
    transaction: dict[str, Any],
    order: dict[str, object],
    settings: Settings,
    expected_payload: str,
) -> bool:
    stored_transaction_id = str(order.get("provider_payment_charge_id") or "")
    callback_id = _transaction_id(callback)
    provider_id = _transaction_id(transaction)
    callback_status = _transaction_status(callback)
    provider_status = _transaction_status(transaction)
    if (
        not stored_transaction_id
        or callback_id != stored_transaction_id
        or provider_id != stored_transaction_id
        or not callback_status
        or callback_status != provider_status
        or _transaction_payload(transaction) != expected_payload
    ):
        return False
    callback_payload = _transaction_payload(callback)
    if callback_payload and callback_payload != expected_payload:
        return False

    callback_amount, callback_currency = _transaction_amount(callback)
    provider_amount, provider_currency = _transaction_amount(transaction)
    if (
        callback_amount is None
        or provider_amount is None
        or callback_amount <= 0
        or callback_amount != provider_amount
        or callback_currency != "RUB"
        or provider_currency != "RUB"
    ):
        return False

    if callback_status == "CONFIRMED":
        try:
            expected_net_amount = Decimal(
                str(
                    order.get("provider_amount_rub")
                    or order.get("pay_amount_rub")
                    or order.get("price_rub")
                    or 0
                )
            ).quantize(Decimal("0.01"))
        except (InvalidOperation, TypeError, ValueError):
            return False
        commission = _transaction_commission(transaction)
        if (
            expected_net_amount <= 0
            or commission is None
            or commission < 0
            or provider_amount - commission != expected_net_amount
        ):
            return False

    expected_method = int(order.get("platega_payment_method") or 0)
    callback_method = _transaction_payment_method(callback)
    if expected_method <= 0 or callback_method != expected_method:
        return False
    provider_method_value = transaction.get("paymentMethod")
    if provider_method_value is None:
        details = transaction.get("paymentDetails")
        if isinstance(details, dict):
            provider_method_value = details.get("paymentMethod")
    if not str(provider_method_value or "").strip():
        return False
    provider_method = _transaction_payment_method(transaction)
    if provider_method and provider_method != expected_method:
        return False

    merchant = str(
        transaction.get("merchantId")
        or transaction.get("mechantId")
        or transaction.get("merchant_id")
        or ""
    )
    if not merchant or not hmac.compare_digest(merchant, settings.platega_merchant_id):
        return False
    return True
