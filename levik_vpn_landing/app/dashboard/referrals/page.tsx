import { redirect } from "next/navigation";

import { getDashboardView } from "@/lib/web/view-models";
import {
  CalendarIcon,
  CheckIcon,
  ReferralIcon,
  TelegramIcon,
} from "@/components/icons";

export default async function ReferralsPage() {
  const view = await getDashboardView();
  if (!view.referral) {
    redirect("/dashboard");
  }

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Реферальная программа</span>
          <h1>Делитесь доступом — получайте дни</h1>
          <p>
            Друг получает скидку на первую покупку, а ваша подписка становится
            длиннее после его успешной оплаты.
          </p>
        </div>
      </header>

      <section className="referral-hero">
        <div className="referral-hero__copy">
          <span className="referral-hero__icon">
            <ReferralIcon height={34} width={34} />
          </span>
          <span className="card-kicker">Ваша ссылка</span>
          <h2>Пригласить через Telegram</h2>
          <p>
            Персональная ссылка формируется на сервере и не показывается в коде
            страницы. Нажмите кнопку — откроется безопасное окно отправки.
          </p>
          <a
            className="button button--primary button--large"
            href={view.referral.sharePath}
            rel="noopener noreferrer"
            target="_blank"
          >
            <TelegramIcon />
            Поделиться с другом
          </a>
        </div>
        <dl className="referral-stats">
          <div>
            <dt>Приглашено друзей</dt>
            <dd>{view.referral.invitedCount}</dd>
          </div>
          <div>
            <dt>Получено дней</dt>
            <dd>+{view.referral.rewardedDays}</dd>
          </div>
        </dl>
      </section>

      <section className="dashboard-section">
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">Как это работает</span>
            <h2>Два шага до награды</h2>
          </div>
        </div>
        <div className="referral-steps">
          <article>
            <span>01</span>
            <h3>Друг открывает вашу ссылку</h3>
            <p>{view.referral.inviteeBenefitDescription}</p>
          </article>
          <article>
            <span>02</span>
            <h3>Оплата подтверждается</h3>
            <p>{view.referral.rewardDescription}</p>
          </article>
          <article>
            <CalendarIcon />
            <h3>Дни добавляются автоматически</h3>
            <p>Награда появится в кабинете после серверной проверки платежа.</p>
          </article>
        </div>
      </section>

      <aside className="security-note">
        <CheckIcon />
        <p>
          Награда начисляется только за реальную первую покупку приглашённого
          пользователя. Повторные и отменённые платежи не учитываются.
        </p>
      </aside>
    </>
  );
}
