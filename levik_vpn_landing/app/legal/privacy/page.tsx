import type { Metadata } from "next";
import Link from "next/link";

import { LegalPage } from "@/components/legal-page";

export const metadata: Metadata = {
  title: "Политика конфиденциальности / Privacy Policy",
  description:
    "Обработка данных Levik Account, сайта, Android-приложения, поддержки и VPN-сервиса.",
  alternates: { canonical: "/legal/privacy" },
};

export default function PrivacyPage() {
  return (
    <LegalPage
      eyebrow="Конфиденциальность / Privacy"
      title="Политика конфиденциальности / Privacy Policy"
      updatedAt="23 августа 2026 года / August 23, 2026"
      homeLabel="На главную / Home"
      updatedLabel="Последнее обновление / Last updated:"
    >
      <nav className="legal-document__languages" aria-label="Язык документа / Document language">
        <a href="#privacy-ru" lang="ru">Русский</a>
        <a href="#privacy-en" lang="en">English</a>
      </nav>

      <div className="legal-document__language" id="privacy-ru" lang="ru" role="region" aria-labelledby="privacy-ru-heading" tabIndex={-1}>
        <h2 id="privacy-ru-heading">Русская версия</h2>
        <section>
          <h3>1. Оператор и область действия</h3>
          <p>
            Оператор сервиса использует публичное наименование Levik VPN и
            работает на домене <code>leviknet.com</code>. Политика применяется к
            Levik Account, сайту, нативному Android-приложению, VPN-сервису и
            поддержке. Основной независимый канал связи — форма обращений в{" "}
            <Link href="/dashboard/support">личном кабинете</Link>.
            Дополнительный контакт —{" "}
            <Link href="https://t.me/leviksupportbot">@leviksupportbot</Link>;
            Telegram для обращения не обязателен.
          </p>
        </section>
        <section>
          <h3>2. Levik Account и способы входа</h3>
          <ul>
            <li>внутренний UUID account ID, Levik ID, display name, статус и даты аккаунта;</li>
            <li>для Google — стабильный subject (<code>sub</code>), время подтверждения и использования; email не является идентификатором и способом восстановления;</li>
            <li>для passkey — credential ID, открытый ключ, счётчик, название устройства и даты; приватный ключ остаётся на устройстве;</li>
            <li>для Levik ID — только memory-hard хеш парольной фразы с индивидуальной солью; исходный пароль не хранится;</li>
            <li>для recovery-кодов — только защищённые хеши, состояние одноразового использования и дата выпуска;</li>
            <li>для Telegram, если пользователь сам его привязал, — provider ID, доступное имя/username и даты связи. Telegram не является основным account ID.</li>
          </ul>
          <p>Email, SMTP, email OTP и magic links не используются.</p>
        </section>
        <section>
          <h3>3. Устройства, сеансы и безопасность</h3>
          <p>
            Обрабатываются название и платформа устройства, версия приложения и
            ОС, публичный идентификатор device-bound ключа, даты создания и
            активности, IP-адрес или его защищённое производное, user agent,
            nonce, события входа, отзыва и защиты от злоупотреблений. Секретный
            Android Keystore key не покидает устройство.
          </p>
          <p>
            В Play-сборке могут обрабатываться итоговые вердикты Google Play
            Integrity о приложении, лицензии и целостности устройства. Direct-
            сборка не обязана проходить Play Integrity. Вердикты не раскрывают
            содержимое устройства.
          </p>
        </section>
        <section>
          <h3>4. Подписки, платежи и поддержка</h3>
          <p>
            Для услуги обрабатываются entitlements, тариф, срок, серверные
            счётчики трафика, лимиты устройств, заказы, сумма, способ и статус
            оплаты, идентификатор операции и реферальные начисления. Полные
            реквизиты банковской карты Levik VPN не получает.
          </p>
          <p>
            Web-поддержка хранит номер, категорию, тему, сообщения, ответы,
            статусы и даты обращения. По явному согласию можно приложить только
            версию приложения, платформу и код ошибки. Пароли, recovery-коды,
            VPN-конфигурации, session tokens и содержимое трафика прикладывать нельзя.
          </p>
        </section>
        <section>
          <h3>5. VPN-трафик и локальная статистика</h3>
          <p>
            Levik VPN не использует содержимое VPN-трафика для профилирования и
            не сохраняет историю посещённых страниц, сообщений или содержимое
            запросов. Приложение локально показывает длительность, скорость и
            объём переданных данных. Серверные технические счётчики могут быть
            нужны для лимита тарифа и предотвращения злоупотреблений.
          </p>
        </section>
        <section>
          <h3>6. Цели и правовые основания</h3>
          <p>
            Данные нужны для исполнения договора и выдачи VPN-доступа,
            аутентификации, управления подписками и устройствами, платежей,
            поддержки, предотвращения атак, соблюдения обязательных требований
            и защиты законных интересов пользователей и сервиса. Необязательная
            identity связывается по действию пользователя.
          </p>
        </section>
        <section>
          <h3>7. Получатели данных</h3>
          <p>
            Минимально необходимые данные могут обрабатывать Google для
            выбранного Google Sign-In и Play Integrity, Telegram для явно
            выбранной Telegram identity, платёжный провайдер для операции,
            хостинг/инфраструктура для работы сервиса и уполномоченные органы при
            наличии законного требования. Данные не продаются и не используются
            для сторонней рекламы.
          </p>
        </section>
        <section>
          <h3>8. Хранение и защита</h3>
          <p>
            Одноразовые auth/WebAuthn/device challenges хранятся только до
            истечения короткого срока или первого использования. Сеансы имеют
            ограниченный idle и absolute срок и могут быть отозваны. Данные
            активного аккаунта и поддержки хранятся, пока нужны для услуги,
            безопасности или открытого обращения. После удаления платежные,
            договорные, antifraud- и audit-записи сохраняются отдельно лишь на
            обязательный или обоснованно необходимый срок и по возможности
            обезличиваются или псевдонимизируются.
          </p>
          <p>
            Передача защищена HTTPS; cookies имеют HttpOnly, Secure и SameSite;
            recovery-коды и парольные фразы не хранятся обратимо; чувствительные
            значения не помещаются в URL и production-логи.
          </p>
        </section>
        <section>
          <h3>9. Удаление и права пользователя</h3>
          <p>
            Пользователь может просматривать и отзывать identities, passkeys,
            сеансы и устройства, обращаться за исправлением данных и удалить
            аккаунт через публичный ресурс <Link href="/account/delete">/account/delete</Link>.
            Удаление отзывает credentials и активный доступ; оно не равно
            автоматическому возврату оплаты. Сведения, обязательные к хранению,
            отделяются от активной identity.
          </p>
        </section>
        <section>
          <h3>10. Cookies, дети и изменения</h3>
          <p>
            Используются только необходимые cookies для auth challenge,
            защищённого сеанса и CSRF. Рекламных cookies и стороннего tracking
            SDK на сайте нет. Сервис не предназначен для самостоятельного
            использования лицами, не достигшими возраста цифрового согласия в
            их стране. Изменения политики публикуются здесь с новой датой.
          </p>
        </section>
        <section>
          <h3>11. Краткое соответствие Google Play Data Safety</h3>
          <p>
            Приложение может передавать серверу идентификаторы аккаунта и
            устройства, данные приложения/диагностики, покупки и security-
            события для работы сервиса, защиты и поддержки. Содержимое VPN-
            трафика не собирается для аналитики или рекламы; данные шифруются
            при передаче; доступно удаление аккаунта. Фактическая декларация в
            Google Play должна обновляться вместе с SDK и поведением релизной сборки.
          </p>
        </section>
      </div>

      <div className="legal-document__language" id="privacy-en" lang="en" role="region" aria-labelledby="privacy-en-heading" tabIndex={-1}>
        <h2 id="privacy-en-heading">English version</h2>
        <section>
          <h3>1. Controller and scope</h3>
          <p>
            The service operator uses the public name Levik VPN and operates at{" "}
            <code>leviknet.com</code>. This policy covers Levik Account, the
            website, native Android app, VPN service, and support. The primary
            independent contact channel is the{" "}
            <Link href="/dashboard/support">in-account support form</Link>.
            The optional contact is{" "}
            <Link href="https://t.me/leviksupportbot">@leviksupportbot</Link>;
            Telegram is not required to contact support.
          </p>
        </section>
        <section>
          <h3>2. Account and sign-in data</h3>
          <p>
            We process an internal account UUID, Levik ID, display name, status,
            and timestamps. Depending on your choices, we process Google subject
            (not email as an identifier), passkey credential ID/public key/counter,
            a memory-hard salted password hash, one-way recovery-code hashes, or
            optional Telegram provider details. Passkey private keys stay on the
            device. Email, SMTP, email OTP, and magic links are not used.
          </p>
        </section>
        <section>
          <h3>3. Devices and security</h3>
          <p>
            Device/platform and app/OS versions, a public device-bound key
            identifier, session timestamps, IP address or protected derivative,
            user agent, nonces, and security events are processed to authenticate
            and prevent abuse. Google Play builds may send final Play Integrity
            verdicts; Direct builds do not depend on Play Integrity.
          </p>
        </section>
        <section>
          <h3>4. Service, payments, and support</h3>
          <p>
            Entitlements, plans, expiry, server-side usage counters, device
            limits, order amount/method/status, and transaction references are
            processed to deliver the service. Full card details are not received.
            Support stores the ticket reference, category, subject, messages,
            replies, status, and timestamps. Optional diagnostics are limited to
            app version, platform, and error code and must not contain secrets.
          </p>
        </section>
        <section>
          <h3>5. VPN traffic</h3>
          <p>
            Levik VPN does not use VPN traffic content for profiling and does not
            retain browsing history, message content, or request bodies. The app
            displays duration, speed, and traffic locally. Server-side technical
            counters may enforce plan limits and prevent abuse.
          </p>
        </section>
        <section>
          <h3>6. Purposes and recipients</h3>
          <p>
            Data is used for contract performance, authentication, subscriptions,
            payments, support, security, and mandatory compliance. Minimum data
            may be processed by Google for selected Google Sign-In/Play Integrity,
            Telegram for an explicitly linked identity, payment and infrastructure
            providers, and authorities with a valid legal request. Data is not sold
            or used for third-party advertising.
          </p>
        </section>
        <section>
          <h3>7. Retention and security</h3>
          <p>
            One-time challenges expire quickly or on first use. Sessions have
            limited idle and absolute lifetimes. Active-account and support data
            is retained while required for the service, security, or an open
            request. After deletion, mandatory payment, contract, antifraud, and
            audit records are separated and anonymised or pseudonymised where
            possible. HTTPS, secure cookies, one-way credential storage, and
            secret-free URLs/logs protect the data.
          </p>
        </section>
        <section>
          <h3>8. Rights and deletion</h3>
          <p>
            You can review or revoke identities, passkeys, sessions, and devices,
            request correction through web support, and delete the account at{" "}
            <Link href="/account/delete">/account/delete</Link>. Deletion revokes
            credentials and access but is not an automatic payment refund.
          </p>
        </section>
        <section>
          <h3>9. Cookies and Google Play disclosure</h3>
          <p>
            Only essential auth-challenge, session, and CSRF cookies are used; no
            advertising cookies or third-party web tracking SDKs are used. The
            Android app may send account/device identifiers, app diagnostics,
            purchases, and security events for service delivery, support, and
            security. Data is encrypted in transit and account deletion is
            available. The Play Data Safety declaration must be kept aligned with
            the actual release build and SDKs.
          </p>
        </section>
      </div>
    </LegalPage>
  );
}
