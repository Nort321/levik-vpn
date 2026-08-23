import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

function IconFrame({ children, ...props }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      height="20"
      viewBox="0 0 24 24"
      width="20"
      {...props}
    >
      {children}
    </svg>
  );
}
export function PulseIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M3 12h4l2.2-6 4.1 12 2.2-6H21" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function RouteIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="5" cy="6" r="2" stroke="currentColor" strokeWidth="1.8" />
      <circle cx="19" cy="18" r="2" stroke="currentColor" strokeWidth="1.8" />
      <path d="M7 6h5a3 3 0 0 1 0 6h-1a3 3 0 0 0 0 6h6" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function DiagnosticIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M8 3v4l-4.7 9.1A3.4 3.4 0 0 0 6.3 21h11.4a3.4 3.4 0 0 0 3-4.9L16 7V3M7 3h10M6.5 14h11" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M9 17h.01M13 18h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="2.2" />
    </IconFrame>
  );
}

export function ArrowUpRightIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M7 17 17 7M9 7h8v8" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function ShieldCheckIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M12 3 20 7v5.5c0 4.2-2.9 7-8 8.5-5.1-1.5-8-4.3-8-8.5V7l8-4Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="m8.5 12 2.2 2.2 4.8-5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function TelegramIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m21 4-3.1 15.1c-.2 1-1.3 1.4-2.1.8l-4.7-3.5-2.4 2.3c-.3.3-.8.1-.8-.3l.2-3.6L18.4 6c.4-.4-.1-.9-.6-.6L5 13.5l-3.1-1c-1.1-.4-1.2-1.9-.1-2.3L19.3 3c.9-.4 1.9.2 1.7 1Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.7" />
    </IconFrame>
  );
}

export function SignalIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M4 15c4.8-4.3 11.2-4.3 16 0M7.5 18.2c2.7-2.2 6.3-2.2 9 0M11.9 21h.2M2 11.4c6-5.8 14-5.8 20 0" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function SupportIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M5 13v-2a7 7 0 0 1 14 0v2M5 13H3.8A1.8 1.8 0 0 0 2 14.8v2.4A1.8 1.8 0 0 0 3.8 19H5v-6Zm14 0h1.2a1.8 1.8 0 0 1 1.8 1.8v2.4a1.8 1.8 0 0 1-1.8 1.8H19v-6ZM19 19c-1 1.3-2.8 2-5.3 2" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function BoltIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m13 2-9 12h7l-1 8 10-13h-7V2Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function DashboardIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" width="7" x="3" y="3" />
      <rect height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" width="7" x="14" y="3" />
      <rect height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" width="7" x="3" y="14" />
      <rect height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" width="7" x="14" y="14" />
    </IconFrame>
  );
}

export function SubscriptionIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="18" rx="3" stroke="currentColor" strokeWidth="1.8" width="14" x="5" y="3" />
      <path d="M9 8h6M9 12h6M9 16h3" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function PlansIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M4 8.5 12 3l8 5.5-8 5.5-8-5.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="m4 13 8 5.5 8-5.5M7 16.1v3.1L12 22l5-2.8v-3.1" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function OrdersIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M9 8h6M9 12h6" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function ConnectIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M8.5 15.5 6 18a2.8 2.8 0 0 1-4-4l4-4a2.8 2.8 0 0 1 4 0M15.5 8.5 18 6a2.8 2.8 0 0 1 4 4l-4 4a2.8 2.8 0 0 1-4 0M8 16l8-8" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function ReferralIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="9" cy="8" r="3" stroke="currentColor" strokeWidth="1.8" />
      <path d="M3 20v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2M16 5h5M18.5 2.5v5" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
      <path d="M16.5 12.5H21v4.5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function SettingsIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M12 3.5 14 5l2.5-.3.8 2.4 2.2 1.3-1 2.3 1 2.3-2.2 1.3-.8 2.4-2.5-.3-2 1.5-2-1.5-2.5.3-.8-2.4L4.5 13l1-2.3-1-2.3 2.2-1.3.8-2.4L10 5l2-1.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.6" />
      <circle cx="12" cy="10.7" r="2.5" stroke="currentColor" strokeWidth="1.7" />
    </IconFrame>
  );
}

export function LogoutIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M10 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5M14 8l4 4-4 4M8 12h10" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function DeviceIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="20" rx="3" stroke="currentColor" strokeWidth="1.8" width="13" x="5.5" y="2" />
      <path d="M10 18h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function CalendarIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="17" rx="3" stroke="currentColor" strokeWidth="1.8" width="18" x="3" y="4" />
      <path d="M8 2v4M16 2v4M3 9h18M8 13h.01M12 13h.01M16 13h.01M8 17h.01M12 17h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function GaugeIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M4.2 18a9 9 0 1 1 15.6 0" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
      <path d="m12 13 4-4M7 17h10" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
      <circle cx="12" cy="13" fill="currentColor" r="1.5" />
    </IconFrame>
  );
}

export function CreditCardIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="16" rx="3" stroke="currentColor" strokeWidth="1.8" width="20" x="2" y="4" />
      <path d="M2 9h20M6 15h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function SbpIcon(props: IconProps) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      height="20"
      viewBox="0 0 24 24"
      width="20"
      {...props}
    >
      <path d="M3 4h5.1l3.1 3.5-2.5 2.8L3 4Z" fill="#45C4D8" />
      <path d="M8.1 4h5.2l7.7 8.7-2.6 2.9L8.1 4Z" fill="#87CF3E" />
      <path d="m3 20 5.7-6.4 2.5 2.8L8.1 20H3Z" fill="#F05A67" />
      <path d="m8.7 13.6 2.5-2.9 2.6 2.9-2.6 2.8-2.5-2.8Z" fill="#F5B942" />
    </svg>
  );
}

export function CryptoIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.7" />
      <path
        d="M9 7.2h4.2a2.3 2.3 0 0 1 0 4.6H9m0 0h4.8a2.5 2.5 0 0 1 0 5H9m2-11.6v13.6m3-13.6v2m0 9.6v2M7 7.2h2m-2 9.6h2"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.7"
      />
    </IconFrame>
  );
}

export function ProxyIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="6" cy="12" r="2.5" stroke="currentColor" strokeWidth="1.8" />
      <circle cx="18" cy="6" r="2.5" stroke="currentColor" strokeWidth="1.8" />
      <circle cx="18" cy="18" r="2.5" stroke="currentColor" strokeWidth="1.8" />
      <path d="m8.2 10.9 7.6-3.8m-7.6 6 7.6 3.8" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m6 6 12 12M18 6 6 18" stroke="currentColor" strokeLinecap="round" strokeWidth="1.9" />
    </IconFrame>
  );
}

export function CheckIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m5 12.5 4.2 4.2L19.5 6.5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
    </IconFrame>
  );
}

export function RotateKeyIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M20 9a8 8 0 1 0 .2 5M20 4v5h-5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
      <circle cx="10" cy="13" r="2" stroke="currentColor" strokeWidth="1.7" />
      <path d="m11.8 11.8 4-4M15 8.5l1.5 1.5" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </IconFrame>
  );
}

export function RemoveDeviceIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="18" rx="3" stroke="currentColor" strokeWidth="1.8" width="12" x="3" y="3" />
      <path d="M7 17h4M17 9l5 5M22 9l-5 5" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function ClockIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.8" />
      <path d="M12 7v5l3.5 2" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function LockIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="11" rx="2.5" stroke="currentColor" strokeWidth="1.8" width="16" x="4" y="10" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function RefreshIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M20 7v5h-5M4 17v-5h5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M6.1 8.2A7 7 0 0 1 18.5 7M17.9 15.8A7 7 0 0 1 5.5 17" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function GlobeIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.8" />
      <path d="M3 12h18M12 3c2.4 2.5 3.5 5.5 3.5 9S14.4 18.5 12 21c-2.4-2.5-3.5-5.5-3.5-9S9.6 5.5 12 3Z" stroke="currentColor" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function PauseIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M8 5v14M16 5v14" stroke="currentColor" strokeLinecap="round" strokeWidth="2" />
    </IconFrame>
  );
}

export function PlayIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m8 5 11 7-11 7V5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function AlertIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m12 3 10 18H2L12 3Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M12 9v5M12 17.5h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="2" />
    </IconFrame>
  );
}

export function SessionIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M5 4h14a2 2 0 0 1 2 2v10H3V6a2 2 0 0 1 2-2Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M1.5 20h21M9 16v4M15 16v4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function MenuIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function CopyIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="13" rx="2.5" stroke="currentColor" strokeWidth="1.8" width="12" x="8" y="8" />
      <path d="M16 8V5.5A2.5 2.5 0 0 0 13.5 3h-9A2.5 2.5 0 0 0 2 5.5v9A2.5 2.5 0 0 0 4.5 17H8" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function NetworkScanIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
      <circle cx="12" cy="12" r="3.5" stroke="currentColor" strokeWidth="1.8" />
      <path d="M12 10.2v3.6M10.2 12h3.6" stroke="currentColor" strokeLinecap="round" strokeWidth="1.6" />
    </IconFrame>
  );
}

export function DnsIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <rect height="6" rx="2" stroke="currentColor" strokeWidth="1.7" width="18" x="3" y="3" />
      <rect height="6" rx="2" stroke="currentColor" strokeWidth="1.7" width="18" x="3" y="15" />
      <path d="M7 6h.01M7 18h.01M12 9v6M9 12h6" stroke="currentColor" strokeLinecap="round" strokeWidth="2" />
    </IconFrame>
  );
}

export function EyeIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M2.5 12s3.4-6 9.5-6 9.5 6 9.5 6-3.4 6-9.5 6-9.5-6-9.5-6Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <circle cx="12" cy="12" r="2.8" stroke="currentColor" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function NoteIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M6 3h9l4 4v14H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M14 3v5h5M8 12h7M8 16h5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
    </IconFrame>
  );
}

export function ChevronDownIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m6 9 6 6 6-6" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.9" />
    </IconFrame>
  );
}

export function UpdateIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5-5 5 5M12 5v12" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.9" />
    </IconFrame>
  );
}

export function DownloadIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.9" />
    </IconFrame>
  );
}
