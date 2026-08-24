# Контракт безопасного OTA-обновления для Direct-сборки

Private GitHub repository остаётся источником сборки и полного release evidence. Служебные checksums, SBOM, corresponding source и подписанный provenance хранятся во внутреннем GitHub Actions artifact с минимальным сроком хранения; пользовательский GitHub Release содержит только Direct APK и русское описание версии. Поскольку GitHub Artifact Attestations недоступны для user-owned private repositories, workflow формирует собственный provenance с repository, commit, tag, workflow run и SHA-256 полного checksum manifest, а затем подписывает его production ECDSA P-256 OTA-ключом. После ручной публикации стабильного GitHub Release отдельный workflow загружает внутренний artifact, проверяет checksums, provenance-подпись и ECDSA-подпись update manifest, после чего атомарно публикует ограниченный публичный feed на `https://leviknet.com/downloads/android/stable/latest.json`. Токены GitHub и deploy credentials никогда не попадают в APK. Сборка для Google Play (Play flavor) не содержит кода OTA-апдейтера и компонентов установки APK.

## Релизные ассеты

Каждый релиз содержит три обязательных файла:
1. Подписанный APK-файл приложения (`LevikVPN-direct-X.Y.Z.apk`).
2. JSON-манифест обновления (`update.json`).
3. Криптографическая отсоединенная подпись манифеста (`update.json.sig`).

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
- Публичный ключ верификации зашит в бинарный код Android-приложения (`DIRECT_UPDATE_MANIFEST_PUBLIC_KEY`).

Скрипт `scripts/release/generate-direct-update-manifest.sh` автоматически валидирует APK, генерирует манифест и подписывает его ключом разработчика.

Public feed и каталоги `vX.Y.Z` публикуются атомарно через forced-command SSH key. Серверный deploy-ключ способен только загрузить новую неизменяемую версию; он не имеет shell-доступа и не может заменить существующий релиз. Даже при компрометации web-сервера клиент отвергнет manifest без production ECDSA-подписи и APK без закреплённого signing certificate.
