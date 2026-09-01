# Levik WhiteList Relay

## Назначение

Levik WhiteList Relay — дополнительный VPN-движок для Direct-сборки Android-клиента. Он предназначен для мобильных сетей, в которых доступен только ограниченный набор внешних сервисов. Движок не заменяет Xray и не меняет существующие обычные или LTE-серверы.

Пользователь видит третью категорию: **«Мобильные серверы при белых списках»**. Названия VK, TURN, WireGuard и внутренние учётные данные в пользовательском интерфейсе не показываются.

## Архитектура

```text
Android Direct
  ├─ один VpnService и один TUN
  ├─ Xray engine (существующий)
  └─ Levik Relay engine
       └─ TURN/DTLS transport → локальный WireGuard dataplane relay-узла

Mobile BFF
  ├─ аутентификация Android-устройства
  ├─ RSA-envelope профиля для конкретного deviceId
  └─ запрос entitlement/lease у production bridge

Production bridge
  ├─ Remnawave остаётся источником истины
  ├─ проверка ACTIVE + expiry + фактической LTE squad
  ├─ outbox/reconciliation без изменения Remnawave
  └─ HMAC-запрос к node-agent

Relay node
  ├─ закрытый node-agent API
  ├─ локальный admin Unix socket
  ├─ matched client/server transport
  └─ WireGuard-порт недоступен из Интернета
```

Тестовый relay-узел не получает токен Remnawave, доступ к production БД или ключи Mobile BFF. Компрометация узла не должна давать возможность выдавать подписки.

## Правила доступа

Relay является capability существующей LTE-подписки, а не отдельной оплачиваемой подпиской.

Доступ выдаётся только когда одновременно выполняются условия:

1. подписка принадлежит авторизованному аккаунту;
2. Remnawave возвращает статус `ACTIVE`;
3. срок подписки не истёк;
4. фактическая `activeInternalSquads` содержит LTE squad;
5. для multi-подписки проверен мобильный компонент;
6. Android-устройство укладывается в общий device limit подписки;
7. используется Direct-сборка с поддерживаемой версией relay engine.

Удаление LTE squad, отзыв устройства или истечение подписки приводит к отзыву lease. Ошибка или неполная выборка Remnawave не должна вызывать массовый отзыв; в таком случае новые выдачи блокируются, а подтверждённые hard-expiry продолжают применяться.

## Профиль Android

Профиль версии 2 — discriminated union. Профиль версии 1 продолжает означать Xray, поэтому старые клиенты не ломаются.

Общие поля:

```text
version
profileId
issuedAt
expiresAt
engine: xray | levik-relay
category: regular | mobile | mobile-allowlist
networkRequirement: any | cellular-allowlist
servers[]
```

Relay-сервер содержит стабильный `nodeId`, endpoint транспорта, `turnFrontSni`, MTU/DNS/routes, версию протокола, capability flags и отдельный срок credential. `turnFrontSni` — только DNS SNI внешнего TURN TLS front; это не relay endpoint, не control-plane hostname и не отображаемое имя сервера. Секретные поля существуют только внутри уже используемого RSA-envelope конкретного Android-устройства и не попадают в URL, логи, analytics или crash reports.

## Жизненный цикл подключения

1. Android выбирает только cellular `Network`.
2. Детектор белых списков управляет предупреждением в UI: при `INACTIVE` или `UNKNOWN` пользователь подтверждает подключение, но сервер остаётся видимым и доступным. Протокольный preflight выполняется всегда.
3. Сервис отказывает fail-closed только при истёкшем профиле, отозванном entitlement или неподдерживаемой версии; отсутствие белых списков само по себе не блокирует и не разрывает туннель.
4. Единственный `VpnService` создаёт TUN и передаёт его file descriptor relay-процессу через Unix socket `SCM_RIGHTS`.
5. Relay-процесс поднимает WireGuard поверх TURN/DTLS. Его внешние сокеты исключены из VPN-loop через `protect(fd)`/underlying cellular network.
6. При смене сети движок останавливает старую сессию, выбирает доступную cellular-сеть и затем выполняет reconnect.

Автовыбор и fallback не смешивают категории: relay никогда самопроизвольно не заменяет обычный Xray-сервер и наоборот.

## Node API

Node-agent принимает только ограниченный набор health- и идемпотентных lifecycle-операций:

```text
GET  /livez
GET  /readyz
POST /internal/v1/leases/apply
POST /internal/v1/leases/rotate
POST /internal/v1/leases/revoke
POST /internal/v1/leases/status
```

Идентификаторы подписки и устройства передаются как domain-separated hashes. Запрос содержит revision и idempotency key. Подпись покрывает HTTP method, canonical path, timestamp, nonce и SHA-256 тела. Node отклоняет старые timestamps, повторные nonce, уменьшение revision и слишком большие payload.

На один `(subscription, device)` создаётся отдельный transport credential. Credential возвращается только при создании или контролируемой ротации и никогда не присутствует в health/status/audit-ответах.

Node-agent обращается к транспорту только через локальный Unix socket. Master credential хранится в root-only файле, не передаётся через argv/environment и не выводится в журнал.

## Сетевые границы

- публично открыт только UDP-порт relay-транспорта;
- внутренний WireGuard UDP-порт блокируется на публичном интерфейсе;
- admin Unix socket имеет режим `0600` либо group-scoped доступ с проверкой peer credentials;
- node API разрешён только с адресов production bridge/BFF и дополнительно защищён TLS + HMAC;
- IPv6 либо туннелируется явно, либо блокируется fail-closed; утечка через неописанный IPv6 route недопустима;
- Xray-контур продолжает работать независимо от доступности relay.

## Источник транспорта и лицензии

Matched transport основан на WDTT Plus v15, tag object `db764d72814924157603e97d072b1a6be653059a`, source commit `3038b8ddc0306feb21d3c3624e2bc1c3c14639ad`. Передача TUN FD адаптирована из qWDTT. Оба компонента учитываются как GPLv3; для каждого Direct-релиза публикуются Corresponding Source, применённые patches, build scripts, license text, notices и SBOM.

Код CSQTT не используется: его PolyForm Noncommercial license несовместима с платной подпиской без отдельного письменного разрешения.

## Этапы выпуска

1. Изолированный тестовый узел без production entitlement и без реальных пользователей.
2. Проверка matched client/server, unit/integration tests и сетевые fault tests.
3. Закрытый Direct-пилот с короткими lease и bridge allowlist из канонических SHA-256 subscription hashes; сырые UUID в конфигурацию не записываются.
4. 24–72 часа soak-тестов, проверка Android API 26–36, OEM/Doze, LTE↔Wi-Fi, loss/reorder и server restart.
5. Read-only reconciliation с Remnawave и сравнение desired/actual state.
6. Включение provisioning после проверки diff и rollback.
7. Несколько relay-узлов, health-aware выбор и draining перед общим выпуском.

До завершения этапов 2–4 production bridge остаётся в `disabled`; пилот включается только через `pilot`, а `all` запрещён до завершения soak-тестов.
