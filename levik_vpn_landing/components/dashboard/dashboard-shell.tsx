import type { ReactNode } from "react";
import Link from "next/link";
import { logoutAction } from "@/lib/web/actions";
import { logoutAccountAction } from "@/lib/web/account-actions";
import { Brand } from "@/components/brand";
import {
  AccountDeviceIcon,
  AccountShieldIcon,
  IdentityIcon,
} from "@/components/account/account-icons";
import {
  ConnectIcon,
  DashboardIcon,
  LogoutIcon,
  OrdersIcon,
  PlansIcon,
  ReferralIcon,
  SettingsIcon,
  SubscriptionIcon,
  SupportIcon,
  UpdateIcon,
} from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import type { DashboardView } from "@/components/view-types";

type DashboardShellProps = {
  children: ReactNode;
  viewer: DashboardView["viewer"];
  auth:
    | { kind: "legacy"; csrfToken: string }
    | { kind: "account"; csrfToken: string; sessionId: string };
};

const baseNavigation = [
  { href: "/dashboard", label: "Обзор", icon: <DashboardIcon /> },
  {
    href: "/dashboard/subscriptions",
    label: "Подписки",
    icon: <SubscriptionIcon />,
  },
  { href: "/dashboard/plans", label: "Тарифы", icon: <PlansIcon /> },
  { href: "/dashboard/orders", label: "Заказы", icon: <OrdersIcon /> },
  { href: "/dashboard/connect", label: "Подключение", icon: <ConnectIcon /> },
  { href: "/dashboard/referrals", label: "Рефералы", icon: <ReferralIcon /> },
  { href: "/dashboard/account-security", label: "Levik Account", icon: <SettingsIcon /> },
  { href: "/dashboard/support", label: "Поддержка", icon: <SupportIcon /> },
] as const;

const accountNavigation = [
  { href: "/dashboard", label: "Обзор", icon: <DashboardIcon /> },
  {
    href: "/dashboard/subscriptions",
    label: "Подписки",
    icon: <SubscriptionIcon />,
  },
  { href: "/dashboard/plans", label: "Тарифы", icon: <PlansIcon /> },
  { href: "/dashboard/orders", label: "Заказы", icon: <OrdersIcon /> },
  { href: "/dashboard/connect", label: "Подключение", icon: <ConnectIcon /> },
  {
    href: "/dashboard/account-security",
    label: "Levik Account",
    icon: <AccountShieldIcon />,
  },
  {
    href: "/dashboard/identities",
    label: "Способы входа",
    icon: <IdentityIcon />,
  },
  {
    href: "/dashboard/devices",
    label: "Устройства",
    icon: <AccountDeviceIcon />,
  },
  { href: "/dashboard/support", label: "Поддержка", icon: <SupportIcon /> },
] as const;

export function DashboardShell({
  children,
  viewer,
  auth,
}: DashboardShellProps) {
  const legacyNavigation = viewer.isAdmin
    ? [
        ...baseNavigation,
        { href: "/dashboard/updates", label: "Обновления APK (Admin)", icon: <UpdateIcon /> },
      ]
    : baseNavigation;
  const navigation = auth.kind === "account" ? accountNavigation : legacyNavigation;

  return (
    <div className="dashboard-shell">
      <aside className="dashboard-sidebar">
        <Brand compact href="/dashboard" />
        <nav aria-label="Личный кабинет" className="dashboard-nav">
          {navigation.map((item) => (
            <Link href={item.href} key={item.href}>
              {item.icon}
              <span>{item.label}</span>
            </Link>
          ))}
        </nav>
        <div className="dashboard-sidebar__account">
          {viewer.photoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              alt=""
              className="avatar avatar--photo"
              height={39}
              loading="lazy"
              referrerPolicy="no-referrer"
              src={viewer.photoUrl}
              width={39}
            />
          ) : (
            <span className="avatar" aria-hidden="true">
              {viewer.displayName.slice(0, 1).toLocaleUpperCase("ru")}
            </span>
          )}
          <div>
            <strong>{viewer.displayName}</strong>
            <span>
              {viewer.telegramUsername
                ? `@${viewer.telegramUsername.replace(/^@/, "")}`
                : "Levik Account"}
            </span>
          </div>
        </div>
        <form action={auth.kind === "account" ? logoutAccountAction : logoutAction}>
          <input name="csrf" type="hidden" value={auth.csrfToken} />
          {auth.kind === "account" ? (
            <input name="sessionId" type="hidden" value={auth.sessionId} />
          ) : null}
          <SubmitButton
            className="button button--quiet button--wide dashboard-sidebar__logout"
            pendingText="Выходим…"
          >
            <LogoutIcon />
            Выйти
          </SubmitButton>
        </form>
      </aside>

      <div className="dashboard-stage">
        <header className="dashboard-mobile-header">
          <Brand compact href="/dashboard" />
          <Link className="button button--quiet button--compact" href="/dashboard/account-security">
            <SettingsIcon />
            <span className="sr-only">Безопасность аккаунта</span>
          </Link>
        </header>
        <nav aria-label="Разделы кабинета" className="dashboard-mobile-nav">
          {navigation.map((item) => (
            <Link href={item.href} key={item.href}>
              {item.icon}
              <span>{item.label}</span>
            </Link>
          ))}
        </nav>
        <main className="dashboard-main" id="main-content">
          {children}
        </main>
      </div>
    </div>
  );
}
