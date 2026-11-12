# Levik VPN Direct 2.8.32 (118)

Draft release only. Preserve the existing signing identity. Publishing and the public OTA feed require a separate instruction.

## Изменения

- «Безлимитные серверы» перенесены в раздел «Белые списки».
- Подключение начинается сразу после нахождения первого рабочего сервера; остальные проверки отменяются.
- Проверка соединения использует два независимых адреса и проверяет содержимое загруженных данных.
- Исправлена работа DNS через безлимитные серверы, которые пропускают только TCP.

## Release notes (EN)

Move Unlimited servers into the Allowlist section. Connect after the first verified route and cancel remaining checks. Verify a known HTTPS payload using independent test origins. Resolve DNS through HTTPS for TCP-only external servers.

## Validation

Targeted Android unit tests cover grouping, first-success selection, cancellation, payload validation and DNS routing. The release workflow builds and tests Direct, verifies signing and creates an APK-only draft. Do not publish OTA.
