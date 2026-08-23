# Нативный артефакт libXray для Android

Для сборки релизов и работы VPN-модуля требуется файл:

`app/libs/libXray.aar`

Используется официальный релиз XTLS/libXray `v26.7.28`:

- Релиз: <https://github.com/XTLS/libXray/releases/tag/v26.7.28>
- Контрольная сумма архива (SHA-256):
  `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`
- Контрольная сумма извлеченного `libXray.aar` (SHA-256):
  `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d`

Файл `.aar` намеренно исключен из Git. Для автоматической загрузки и верификации выполните из корня репозитория:

```bash
./scripts/ci/fetch-libxray.sh
```
