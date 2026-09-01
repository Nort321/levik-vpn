# Уведомления об использовании сторонних библиотек

## Levik WhiteList Relay / WDTT Plus v15 (только Direct)

- Проект: <https://github.com/Ivan4537/WDTT-Plus>
- Зафиксированная ревизия: `3038b8ddc0306feb21d3c3624e2bc1c3c14639ad`
- Лицензия: GPL-3.0-only
- SHA-256 upstream-архива:
  `07c6a4c200c87c636a6d0855385e96284e73ddcc5b80c912a463b068ef964223`

Direct APK содержит модифицированный нативный клиент из полного fork в
`levik_whitelist_relay/`. Google Play flavor этот бинарник не содержит.

Механизм передачи TUN file descriptor частично основан на qWDTT
<https://github.com/SpaceNeuroX/proxy-turn-vk-android>, ревизия
`fae121efc3ef57b633516601d3c0d6b1be1fde7c`, GPL-3.0; SHA-256 upstream-
архива `1a2b4f559890e0688ea608c6890a7794131acd583acc612d23e30f59e8c53e9c`.

CSQTT и его PolyForm Noncommercial-код в приложение не включены.

### anet (Direct relay)

- Проект: <https://github.com/wlynxg/anet>
- Версия: `v0.0.5`, ревизия
  `839bc3a920f1b87dd3ce1386e425aa5ef2e69d24`
- Лицензия: BSD-3-Clause

Direct relay поставляет локальный fork, который убирает неподдерживаемые
Android-записи в приватные Go zone caches через `//go:linkname`, сохраняя
Android 11+ перечисление интерфейсов. Исходный код, лицензия и точное описание
патча входят в Corresponding Source; глобальное отключение linker-проверки не
используется.

## libXray

- Проект: <https://github.com/XTLS/libXray>
- Зафиксированная версия: `v26.7.28`
- Лицензия: MIT
- Хеш архива Android-релиза (SHA-256):
  `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`
- Хеш извлеченного Android AAR (SHA-256):
  `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d`

## Xray-core

- Проект: <https://github.com/XTLS/Xray-core>
- Версия: версия, включенная в libXray `v26.7.28`
- Лицензия: Mozilla Public License 2.0 (MPL-2.0)

## Библиотеки SagerNet sing, встроенные в libXray

Зафиксированный нативный AAR содержит скомпонованный код библиотек:

- `github.com/sagernet/sing` `v0.5.1` — GPL-3.0-or-later;
- `github.com/sagernet/sing-shadowsocks` `v0.2.7` — GPL-3.0-or-later.

Каждая опубликованная сборка APK/AAB, содержащая эти нативные библиотеки, сопровождается текстом GPL и машиночитаемым соответствующим исходным кодом (Corresponding Source) согласно разделу 6 GPLv3.

## Списки IP-диапазонов стран (Country IP blocks)

- Проект: <https://github.com/herrbischoff/country-ip-blocks>
- Снимок российских диапазонов IPv4/IPv6: 2025-05-16
- Лицензия: CC0 1.0 Universal

Файлы CIDR используются исключительно для локальной маршрутизации VPN-трафика на устройстве (`app/src/main/assets/RU_CIDR_SOURCE.txt`).

## Библиотеки AndroidX, Kotlin, kotlinx и Google Play

Эти зависимости сохраняют лицензии своих upstream-разработчиков. Каждый релизный артефакт сопровождается проверенным реестром лицензий и SBOM.
