# Уведомления об использовании сторонних библиотек

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
