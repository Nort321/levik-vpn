# Контракт безопасного OTA-обновления для Direct-сборки

GitHub repository остаётся публичным источником кода, а GitHub Actions — единственным источником сборки и полного release evidence. Служебные checksums, SBOM, corresponding source и подписанный provenance хранятся во внутреннем GitHub Actions artifact с минимальным сроком хранения; пользовательский GitHub Release содержит только Direct APK и русское описание версии. Workflow формирует provenance с repository, commit, tag, workflow run и SHA-256 полного checksum manifest, а затем подписывает его production ECDSA P-256 OTA-ключом. После ручной публикации стабильного GitHub Release отдельный workflow загружает внутренний artifact, проверяет checksums, provenance-подпись и ECDSA-подписи, после чего атомарно публикует ограниченный публичный feed на `https://leviknet.com/downloads/android/stable/latest.json`. Токены GitHub и deploy credentials никогда не попадают в APK. Сборка для Google Play (Play flavor) не содержит кода OTA-апдейтера и компонентов установки APK.

## Публичный GitHub Release

GitHub Release содержит только подписанный APK-файл приложения (`LevikVPN-direct-X.Y.Z.apk`). Манифесты, подписи, checksums и остальные доказательства сборки находятся во внутреннем workflow artifact и на выделенном OTA-origin, но не добавляются к пользовательскому Release.

## OTA-ассеты

Для каждой неизменяемой версии OTA-origin содержит APK, `update.json` и его отсоединённую подпись `update.json.sig`. В корне канала находятся короткоживущие `latest.json` и `latest.json.sig`. Указатель включает тег, `versionCode`, SHA-256 манифеста и срок действия не более 72 часов; он автоматически переподписывается GitHub Actions каждые сутки.

### Схема `update.json`

```json
{
  "schemaVersion": 1,
  "packageName": "com.leviknet.vpn",
  "versionCode": 20,
  "versionName": "2.0.0",
  "apkUrl": "https://leviknet.com/downloads/android/stable/v2.0.0/LevikVPN-direct-2.0.0.apk",
  "apkSize": 203423412,
  "apkSha256": "64-символьный-hex-хеш-sha256",
  "signingCertificateSha256": "64-символьный-hex-отпечаток-сертификата",
  "changelogRu": "Описание изменений в релизе",
  "changelogEn": "Release notes",
  "forceUpdate": false
}
```

Перед открытием системного инсталлятора Android проверяются:
- Имя пакета (`packageName`) точно соответствует `com.leviknet.vpn`.
- `versionCode` строго выше текущей установленной версии (защита от downgrade).
- Размер скачанного файла и SHA-256 хэш совпадают с манифестом.
- Отпечаток сертификата подписи извлеченного APK совпадает с доверенным ключом разработчика.

## Криптографическая подпись манифеста

- Алгоритм подписи: `SHA256withECDSA` (эллиптическая кривая NIST P-256 / secp256r1).
- Подписываемые данные: точные байты UTF-8 файла `update.json`.
- Формат подписи `update.json.sig`: Base64 от ASN.1 DER байтов подписи.
- `latest.json.sig` подписывает точные байты UTF-8 файла `latest.json` тем же алгоритмом.
- Публичный ключ верификации зашит в бинарный код Android-приложения (`DIRECT_UPDATE_MANIFEST_PUBLIC_KEY`).

Скрипт `scripts/release/generate-direct-update-manifest.sh` автоматически валидирует APK, генерирует манифест и подписывает его ключом разработчика.

Public feed и каталоги `vX.Y.Z` публикуются через forced-command SSH key. Серверный deploy-ключ способен только загрузить новую неизменяемую версию либо обновить подписанный указатель на уже существующую версию; он не имеет shell-доступа и не может заменить существующий релиз. Клиент отклоняет просроченный, подменённый или откатывающий версию указатель, manifest без production ECDSA-подписи и APK без закреплённого signing certificate.
