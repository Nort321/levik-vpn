# Levik VPN Direct 2.8.31 (117)

Draft release only. Keep the existing signing identity and OTA channel unchanged until publication is explicitly requested.

## Изменения

- «Безлимитные серверы»: автоматический поиск рабочего маршрута по полному сохранённому списку.
- Проверка передачи не менее 64 КБ по HTTPS перед подключением, прогресс и отмена поиска.
- Обновление списка раз в час; при недоступном обновлении сохраняется предыдущий список этой подписки.

## Release notes (EN)

Unlimited servers: automatically find a working route from the complete saved catalog. Verify at least 64 KiB of HTTPS traffic before connecting, with progress and cancellation. Refresh the catalog hourly and retain its last-good encrypted copy during network restrictions.

## Validation

Android Direct compilation and 757 unit tests passed before the version-only release preparation. The release pipeline additionally verifies signing, package/version metadata, runtime dependencies and release evidence. Build Direct only; do not publish the release or refresh the public OTA feed for this draft.
