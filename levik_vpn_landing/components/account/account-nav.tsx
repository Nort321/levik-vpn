import Link from "next/link";

import {
  AccountDeviceIcon,
  AccountShieldIcon,
  BrowserSessionIcon,
  IdentityIcon,
  PasskeyIcon,
  RecoveryIcon,
  TicketIcon,
} from "@/components/account/account-icons";

const links = [
  {
    href: "/dashboard/account-security",
    label: "Обзор защиты",
    icon: <AccountShieldIcon />,
  },
  {
    href: "/dashboard/identities",
    label: "Способы входа",
    icon: <IdentityIcon />,
  },
  {
    href: "/dashboard/passkeys",
    label: "Passkeys",
    icon: <PasskeyIcon />,
  },
  {
    href: "/dashboard/recovery",
    label: "Recovery-коды",
    icon: <RecoveryIcon />,
  },
  {
    href: "/dashboard/sessions",
    label: "Сеансы",
    icon: <BrowserSessionIcon />,
  },
  {
    href: "/dashboard/devices",
    label: "Устройства",
    icon: <AccountDeviceIcon />,
  },
  {
    href: "/dashboard/support",
    label: "Поддержка",
    icon: <TicketIcon />,
  },
] as const;

export function AccountNav({ current }: { current: string }) {
  return (
    <nav aria-label="Управление Levik Account" className="account-nav">
      {links.map((link) => (
        <Link
          aria-current={current === link.href ? "page" : undefined}
          className={current === link.href ? "account-nav__link is-current" : "account-nav__link"}
          href={link.href}
          key={link.href}
        >
          {link.icon}
          <span>{link.label}</span>
        </Link>
      ))}
    </nav>
  );
}
