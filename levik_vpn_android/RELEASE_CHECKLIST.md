# Чек-лист безопасного релиза (Release Checklist)

Ни один Android APK или AAB не может быть передан пользователям до выполнения каждого из следующих пунктов:

1. **Регистрация в Google Play Console**:
   - Зарегистрировать Package Name `com.leviknet.vpn`.
   - Подключить **Play App Signing**. Хранить upload key в защищенном хранилище секретов CI.
2. **Google Play Integrity**:
   - Активировать Play Integrity API в Google Cloud Console и связать с проектом.
   - Настроить Mobile BFF на проверку имени пакета, отпечатка сертификата, свежести токена и аттестата устройства.
3. **Нативное ядро libXray**:
   - Установить официальный `libXray.aar` `v26.7.28` и проверить контрольные суммы SHA-256 в CI.
4. **Тестирование на физических устройствах**:
   - Провести тестирование на матрице устройств Android 8–16 (Wi-Fi, LTE, переключение сетей, энергосбережение, перезагрузка устройства).
5. **Декларации в Google Play Console**:
   - Указать foreground-service тип `specialUse` / `vpn`. Приложить демонстрационное видео включения VPN пользователем.
   - Заполнить раздел **Data Safety** (безопасность данных) в соответствии с реальной политикой конфиденциальности (`https://leviknet.com/legal/privacy`).
   - Указать URL удаления аккаунта: `https://leviknet.com/account/delete`.
6. **Автоматическая валидация**:
   - Убедиться, что `Android CI` успешно завершился для точного release-коммита.
   - Выполнять lint, тесты, APK, AAB и SBOM только в GitHub Actions; локальные Android-сборки запрещены релизной политикой.
7. **Direct OTA релиз (для сайта и прямых загрузок)**:
   - Сгенерировать криптографический манифест `update.json.sig` алгоритмом `SHA256withECDSA` (P-256) и опубликовать вместе с подписанным APK.
8. **Лицензии и SBOM**:
   - Сформировать спецификацию SBOM (CycloneDX JSON/XML) и архив соответствующего исходного кода (Corresponding Source) для нативных компонентов GPL.
9. **Запуск релиза**:
   - Запустить GitHub Actions workflow `.github/workflows/android-release.yml` для тега версии через защищенное окружение `production-release`.
   - Проверить, что draft GitHub Release опубликован вручную только после проверки evidence; публикация должна запустить `.github/workflows/android-publish.yml`.
   - Убедиться, что публичный подписанный feed доступен через `https://leviknet.com/downloads/android/stable/latest.json`, а доверенный APK URL перенаправляет на публичный GitHub Release.
