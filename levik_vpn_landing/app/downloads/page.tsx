import type { Metadata } from "next";
import Link from "next/link";

import { AccountShieldIcon } from "@/components/account/account-icons";
import { LegalPage } from "@/components/legal-page";
import {
  CheckIcon,
  DeviceIcon,
  DownloadIcon,
  LockIcon,
  ShieldCheckIcon,
} from "@/components/icons";

export const metadata: Metadata = {
  title: "Скачать приложение",
  description:
    "Официальные каналы Levik VPN для Google Play и Direct APK без небезопасных или временных ссылок.",
  alternates: { canonical: "/downloads" },
};

export default function DownloadsPage() {
  return (
    <LegalPage
      eyebrow="Android"
      title="Скачать Levik VPN"
      updatedAt="23 августа 2026 года"
    >
      <section className="download-intro">
        <ShieldCheckIcon height={28} width={28} />
        <div>
          <h2>Публичный релиз ещё не опубликован</h2>
          <p>
            Сейчас нет безопасной публичной ссылки на APK или карточку Google
            Play. Мы не выдаём локальный файл, ссылку на приватный репозиторий
            или URL с токеном вместо официального релиза.
          </p>
        </div>
      </section>

      <div className="download-channel-grid">
        <section className="download-channel">
          <span className="download-channel__icon"><DeviceIcon height={28} width={28} /></span>
          <span className="download-channel__state">Ожидает публикации</span>
          <h2>Google Play</h2>
          <p>
            Play-сборка устанавливается и обновляется только через Google Play.
            Она предназначена для входа и использования уже активной подписки;
            внешних покупок внутри приложения нет.
          </p>
          <ul>
            <li><CheckIcon /> Обновления только через Google Play</li>
            <li><CheckIcon /> Без загрузки и установки APK</li>
            <li><CheckIcon /> Без Google Play Billing и внешних payment CTA</li>
          </ul>
        </section>

        <section className="download-channel">
          <span className="download-channel__icon"><DownloadIcon height={28} width={28} /></span>
          <span className="download-channel__state">Ожидает публичного Release</span>
          <h2>Direct APK</h2>
          <p>
            Direct-сборка появится только как подписанный asset публичного
            GitHub Release. До публикации immutable release загрузка недоступна.
          </p>
          <ul>
            <li><CheckIcon /> APK и manifest из одного release</li>
            <li><CheckIcon /> SHA-256, подпись, SBOM и notices</li>
            <li><CheckIcon /> OTA только для Direct variant</li>
          </ul>
        </section>
      </div>

      <section>
        <h2>Как появятся ссылки</h2>
        <p>
          После публикации эта страница будет вести на каноническую карточку
          Google Play и на публичный GitHub Release. Stable Direct-клиент будет
          игнорировать draft и prerelease, проверять manifest, checksum и подпись.
          GitHub token в приложение или download URL не встраивается.
        </p>
      </section>

      <section className="download-account-note">
        <LockIcon height={24} width={24} />
        <div>
          <h2>Один Levik Account для обоих каналов</h2>
          <p>
            Аккаунт и активная подписка общие для Play и Direct. Создать аккаунт
            можно без email и обязательного Telegram; установка приложения для
            управления identities не требуется.
          </p>
          <div className="button-row">
            <Link className="button button--primary" href="/signup">
              <AccountShieldIcon />
              Создать Levik Account
            </Link>
            <Link className="button button--quiet" href="/login">
              <LockIcon />
              Войти
            </Link>
          </div>
        </div>
      </section>
    </LegalPage>
  );
}
