# Levik VPN для Android

Нативный клиент на Kotlin и Jetpack Compose для подключения к сервису Levik VPN. Реализует независимую авторизацию Levik Account через Chrome Custom Tabs (`https://leviknet.com/activate`), привязку сессий к аппаратному ключу Android Keystore, подпись каждого сетевого запроса к Mobile BFF, локальное шифрование профилей (AES-GCM) и маршрутизацию трафика через `android.net.VpnService` с нативным ядром `libXray`.

## Локальная конфигурация разработки

Параметры разработки задаются в файле `local.properties` (не отслеживается в Git):

```properties
sdk.dir=/absolute/path/to/Android/sdk
levik.cabinetBaseUrl=https://leviknet.com
levik.playIntegrityCloudProjectNumber=123456789012
```

Параметры подписи Direct и Play вариантов изолированы друг от друга. При релизной сборке значения передаются через переменные окружения или защищенные секреты CI: `LEVIK_DIRECT_SIGNING_*` и `LEVIK_PLAY_SIGNING_*`.

Для Direct-сборки с поддержкой безопасного OTA-обновления дополнительно требуются публичный ключ манифеста и отпечаток сертификата: `LEVIK_UPDATE_MANIFEST_PUBLIC_KEY` и `LEVIK_UPDATE_SIGNING_CERTIFICATE_SHA256`.

## Контракт Mobile BFF

Каждый запрос к Mobile BFF подписывается аппаратным ключом Android Keystore (`PS256` на API 35+, `RS256` на API 26–34) по каноническому формату:

```text
v1
METHOD
/api/mobile/v1/path
EPOCH_SECONDS
NONCE_BASE64URL
DEVICE_ID
SHA256_HEX_ACCESS_TOKEN_OR_EMPTY
SHA256_HEX_BODY
```

Сервер вычисляет `deviceId` как SHA-256 от публичного ключа устройства, валидирует метку времени, проверяет однократность `nonce`, аутентифицирует подпись и возвращает профиль в зашифрованном виде (`RSA-OAEP-256+A256GCM`).

## Сборка и тестирование

Используйте JDK 17 и Android SDK 36:

```bash
./gradlew verifyAllDependencyLocks
./gradlew lintDirectRelease lintPlayRelease
./gradlew assembleDirectRelease bundlePlayRelease
./gradlew testDirectReleaseUnitTest testPlayReleaseUnitTest
./gradlew :app:cyclonedxDirectBom
```

Подробный чек-лист перед релизом в Google Play описан в [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
