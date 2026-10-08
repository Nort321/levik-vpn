package com.leviknet.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexGuestRefreshPolicyTest {
    @Test fun `visible guest and background refresh cannot own guest process concurrently`() {
        val visible = Any()
        val refresh = Any()
        try {
            assertTrue(YandexGuestProcessCoordinator.tryAcquire(visible))
            assertFalse(YandexGuestProcessCoordinator.tryAcquire(refresh))
            YandexGuestProcessCoordinator.release(refresh)
            assertFalse(YandexGuestProcessCoordinator.tryAcquire(refresh))
            YandexGuestProcessCoordinator.release(visible)
            assertTrue(YandexGuestProcessCoordinator.tryAcquire(refresh))
            assertFalse(YandexGuestProcessCoordinator.tryAcquire(refresh))
        } finally {
            YandexGuestProcessCoordinator.release(visible)
            YandexGuestProcessCoordinator.release(refresh)
        }
    }

    @Test fun `only explicit anonymous provider origins and document routes are accepted`() {
        assertTrue(YandexGuestRefreshPolicy.navigation("https://disk.yandex.ru/i/abcdefgh1234", true))
        assertTrue(YandexGuestRefreshPolicy.configOrigin("https://docs.yandex.ru/docs/view?url=opaque"))
        assertTrue(YandexGuestRefreshPolicy.navigation("https://docs.yandex.ru/edit/d/opaque-public-document", true))
        assertTrue(YandexGuestRefreshPolicy.configOrigin("https://docs.yandex.ru/edit/d/opaque-public-document"))
        assertTrue(YandexGuestRefreshPolicy.navigation("https://docs.yandex.ru/showcaptchafast?retpath=opaque", true))
        assertTrue(YandexGuestRefreshPolicy.navigation("https://docs.yandex.ru/checkcaptchafast", true))
        assertFalse(YandexGuestRefreshPolicy.configOrigin("https://docs.yandex.ru/showcaptchafast?retpath=opaque"))
        assertTrue(YandexGuestRefreshPolicy.resource("https://yastatic.net/s3/editor.js"))
        assertTrue(YandexGuestRefreshPolicy.resource("https://opaque_guest.onlyoffice.disk.yandex.net/web-apps/apps/api/documents/api.js"))
        assertFalse(YandexGuestRefreshPolicy.navigation("https://yastatic.net/s3/editor.js", true))
        assertFalse(YandexGuestRefreshPolicy.configOrigin("https://opaque.onlyoffice.disk.yandex.net/docs/view"))
    }

    @Test fun `login challenge aliases userinfo ports fragments and unsafe schemes fail closed`() {
        for (url in listOf(
            "https://passport.yandex.ru/auth", "https://oauth.yandex.ru/authorize",
            "https://docs.yandex.ru/showcaptcha", "https://docs.yandex.ru/checkcaptcha",
            "https://yandex.ru/showcaptcha", "https://smartcaptcha.yandexcloud.net/captcha",
            "https://docs.yandex.ru.evil.test/docs/view", "https://evil.test@docs.yandex.ru/docs/view",
            "https://docs.yandex.ru:443/docs/view", "https://docs.yandex.ru/docs/view#other",
            "http://docs.yandex.ru/docs/view", "file:///etc/hosts", "content://private/value",
            "intent://docs.yandex.ru/docs/view", "https://a.b.onlyoffice.disk.yandex.net/api.js",
        )) assertFalse(url, YandexGuestRefreshPolicy.resource(url))
        assertFalse(YandexGuestRefreshPolicy.navigation("https://docs.yandex.ru/auth", true))
        assertFalse(YandexGuestRefreshPolicy.navigation("https://docs.yandex.ru/edit/api", true))
        assertFalse(YandexGuestRefreshPolicy.navigation("https://docs.yandex.ru/edit/d", true))
        assertFalse(YandexGuestRefreshPolicy.resource("https://docs.yandex.ru/" + "x".repeat(4096)))
    }
}
