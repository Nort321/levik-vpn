import type { Metadata } from "next";
import Link from "next/link";

import { AccountDeletion } from "@/components/account/account-deletion";
import { DeleteAccountIcon } from "@/components/account/account-icons";
import { Brand } from "@/components/brand";
import { ArrowUpRightIcon, CheckIcon, LockIcon, ShieldCheckIcon } from "@/components/icons";
import { getOptionalAccountOverview } from "@/lib/web/account-actions";

export const metadata: Metadata = {
  title: "Удаление аккаунта",
  description:
    "Как удалить Levik Account, отозвать связанные credentials, сеансы и устройства.",
  alternates: { canonical: "/account/delete" },
};

export default async function DeleteAccountPage() {
  const account = await getOptionalAccountOverview();
  return (
    <main className="legal-page account-delete-page" id="main-content">
      <div className="legal-page__header">
        <Brand />
        <Link className="text-link" href={account ? "/dashboard/account-security" : "/"}>
          {account ? "Назад к аккаунту" : "На главную"}
          <ArrowUpRightIcon />
        </Link>
      </div>

      <article className="account-delete-document">
        <header>
          <span className="account-delete-document__icon"><DeleteAccountIcon height={36} width={36} /></span>
          <span className="section-kicker">Levik Account</span>
          <h1>Удаление аккаунта и данных</h1>
          <p>
            Страница доступна без Telegram и email. Само удаление требует входа
            в удаляемый Levik Account и повторного подтверждения.
          </p>
        </header>

        <section>
          <h2>Что произойдёт</h2>
          <ul className="account-delete-list">
            <li><CheckIcon /> Google и Telegram identities будут отвязаны и обезличены.</li>
            <li><CheckIcon /> Passkeys, парольная credential и recovery-коды будут отозваны.</li>
            <li><CheckIcon /> Активные web-сеансы и device-bound sessions будут завершены.</li>
            <li><CheckIcon /> Устройства потеряют доступ к аккаунту и новым VPN-профилям.</li>
            <li><CheckIcon /> Открытые обращения поддержки будут закрыты или обезличены.</li>
          </ul>
        </section>

        <section className="account-delete-retention">
          <ShieldCheckIcon />
          <div>
            <h2>Подписки и обязательные записи</h2>
            <p>
              Удаление аккаунта не является автоматическим возвратом платежа и
              может прекратить доступ к действующей подписке. Записи о платежах,
              antifraud- и security-событиях могут сохраняться отдельно только
              на срок, необходимый для бухгалтерских, договорных, спорных и
              обязательных требований. Связь с активной identity удаляется или
              псевдонимизируется, когда она больше не нужна.
            </p>
          </div>
        </section>

        {account ? (
          <section className="account-delete-action">
            <span className="card-kicker">Аккаунт {account.account.levikId}</span>
            <h2>Начать удаление</h2>
            <p>
              Сначала подтвердите Levik ID. Затем сервер выдаст одноразовое
              подтверждение, которое не сохраняется в URL или браузерном хранилище.
            </p>
            <AccountDeletion csrfToken={account.csrfToken} levikId={account.account.levikId} />
          </section>
        ) : (
          <section className="account-delete-action">
            <span className="card-kicker">Нужен вход</span>
            <h2>Войдите в удаляемый аккаунт</h2>
            <p>
              Поддержка не удаляет аккаунт только по имени, платежу или сообщению
              в мессенджере. Это защищает от удаления чужого аккаунта.
            </p>
            <Link
              className="button button--primary button--large"
              href="/login?next=%2Faccount%2Fdelete"
            >
              <LockIcon />
              Войти и продолжить
            </Link>
          </section>
        )}
      </article>
    </main>
  );
}
