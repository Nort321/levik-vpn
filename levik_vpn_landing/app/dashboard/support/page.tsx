import Link from "next/link";

import { ActionFeedback } from "@/components/account/action-feedback";
import { AccountNav } from "@/components/account/account-nav";
import { SendReplyIcon, TicketIcon } from "@/components/account/account-icons";
import { formatAccountDate } from "@/components/account/date-time";
import { ArrowUpRightIcon, LockIcon, ShieldCheckIcon, SupportIcon } from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import {
  createSupportTicketAction,
  getSupportOverview,
  replySupportTicketAction,
} from "@/lib/web/account-actions";

type SupportPageProps = {
  searchParams: Promise<{ error?: string; notice?: string }>;
};

const CATEGORY_LABELS = {
  connection: "Подключение",
  account: "Аккаунт",
  subscription: "Подписка и оплата",
  privacy: "Конфиденциальность и удаление",
  other: "Другое",
} as const;

const STATUS_LABELS = {
  open: "Открыто",
  waiting_for_user: "Нужен ваш ответ",
  waiting_for_support: "Ожидает поддержки",
  closed: "Закрыто",
} as const;

export default async function SupportPage({ searchParams }: SupportPageProps) {
  const [view, params] = await Promise.all([getSupportOverview(), searchParams]);
  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Без email и мессенджера</span>
          <h1>Поддержка Levik VPN</h1>
          <p>
            Создайте обращение и читайте ответы здесь. Telegram может быть
            дополнительным уведомлением, но для поддержки он не нужен.
          </p>
        </div>
      </header>
      <AccountNav current="/dashboard/support" />
      <ActionFeedback error={params.error} notice={params.notice} />

      <div className="support-shortcuts">
        <Link href="/dashboard/devices">
          <SupportIcon />
          <span><strong>Устройства приложения</strong><small>Проверить привязку и активность</small></span>
          <ArrowUpRightIcon />
        </Link>
        <Link href="/status">
          <ShieldCheckIcon />
          <span><strong>Статус сервиса</strong><small>Проверить известные инциденты</small></span>
          <ArrowUpRightIcon />
        </Link>
      </div>

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Новое обращение</span><h2>Опишите проблему</h2></div>
          <TicketIcon />
        </div>
        <form action={createSupportTicketAction} className="account-form support-ticket-form">
          <input name="csrf" type="hidden" value={view.csrfToken} />
          <div className="account-form__grid">
            <label>
              <span>Категория</span>
              <select defaultValue="connection" name="category" required>
                {Object.entries(CATEGORY_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
            <label>
              <span>Тема</span>
              <input maxLength={160} minLength={5} name="subject" placeholder="Кратко: что не работает" required type="text" />
            </label>
          </div>
          <label>
            <span>Описание</span>
            <textarea
              maxLength={8_000}
              minLength={20}
              name="message"
              placeholder="Что произошло, когда началось и какой текст ошибки вы видите"
              required
              rows={6}
            />
          </label>
          <label className="account-checkbox">
            <input name="includeDiagnostics" type="checkbox" value="yes" />
            <span>
              Приложить тип платформы браузера. VPN-конфигурация, ключи, токены
              и содержимое трафика не прикладываются.
            </span>
          </label>
          <SubmitButton className="button button--primary" pendingText="Создаём обращение…">
            <TicketIcon />
            Создать обращение
          </SubmitButton>
        </form>
      </section>

      <section className="dashboard-section">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">История</span><h2>Ваши обращения</h2></div>
          <span className="session-count">{view.tickets.length}</span>
        </div>
        {view.tickets.length > 0 ? (
          <div className="support-ticket-list">
            {view.tickets.map((ticket) => (
              <details className="support-ticket" key={ticket.id} open={ticket.status === "waiting_for_user"}>
                <summary>
                  <span className="support-ticket__icon"><TicketIcon /></span>
                  <span className="support-ticket__title">
                    <strong>{ticket.subject}</strong>
                    <small>{ticket.reference} · {CATEGORY_LABELS[ticket.category]}</small>
                  </span>
                  <span className={`support-ticket__status support-ticket__status--${ticket.status}`}>
                    {STATUS_LABELS[ticket.status]}
                  </span>
                  <time dateTime={ticket.updatedAt}>{formatAccountDate(ticket.updatedAt)}</time>
                </summary>
                <div className="support-thread">
                  {ticket.replies.map((reply) => (
                    <article className={`support-reply support-reply--${reply.author}`} key={reply.id}>
                      <header>
                        <strong>
                          {reply.author === "support"
                            ? "Поддержка Levik VPN"
                            : reply.author === "system"
                              ? "Система"
                              : "Вы"}
                        </strong>
                        <time dateTime={reply.createdAt}>{formatAccountDate(reply.createdAt)}</time>
                      </header>
                      <p>{reply.body}</p>
                    </article>
                  ))}
                  {ticket.replies.length === 0 ? (
                    <p className="support-thread__empty">Сообщение принято. Ответ ещё не отправлен.</p>
                  ) : null}
                  {ticket.status !== "closed" ? (
                    <form action={replySupportTicketAction} className="account-form support-reply-form">
                      <input name="csrf" type="hidden" value={view.csrfToken} />
                      <input name="ticketId" type="hidden" value={ticket.id} />
                      <label>
                        <span>Ответить</span>
                        <textarea maxLength={8_000} minLength={2} name="message" required rows={3} />
                      </label>
                      <SubmitButton className="button button--quiet" pendingText="Отправляем…">
                        <SendReplyIcon />
                        Отправить ответ
                      </SubmitButton>
                    </form>
                  ) : null}
                </div>
              </details>
            ))}
          </div>
        ) : (
          <div className="account-empty-state">
            <TicketIcon height={30} width={30} />
            <h2>Обращений пока нет</h2>
            <p>Созданное обращение получит номер и появится здесь вместе с ответами.</p>
          </div>
        )}
      </section>

      <aside className="account-critical-note">
        <LockIcon />
        <div>
          <strong>Не отправляйте секреты</strong>
          <p>
            Поддержке не нужны парольная фраза, recovery-коды, passkey,
            identity token, полный VPN-ключ или банковские коды.
          </p>
        </div>
      </aside>
    </>
  );
}
