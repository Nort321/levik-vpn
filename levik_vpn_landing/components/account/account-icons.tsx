import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

function Icon({ children, ...props }: IconProps) {
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

export function AccountShieldIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path
        d="M12 2.8 20 6.6v5.6c0 4.5-3 7.4-8 9-5-1.6-8-4.5-8-9V6.6l8-3.8Z"
        stroke="currentColor"
        strokeLinejoin="round"
        strokeWidth="1.8"
      />
      <circle cx="12" cy="10" r="2.4" stroke="currentColor" strokeWidth="1.7" />
      <path d="M8.5 16c.8-1.8 2-2.7 3.5-2.7s2.7.9 3.5 2.7" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function IdentityIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect height="16" rx="3" stroke="currentColor" strokeWidth="1.8" width="20" x="2" y="4" />
      <circle cx="8" cy="11" r="2.3" stroke="currentColor" strokeWidth="1.7" />
      <path d="M4.8 17c.7-2 1.8-3 3.2-3s2.5 1 3.2 3M14 9h5M14 13h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function PasskeyIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <circle cx="8.5" cy="10" r="4.5" stroke="currentColor" strokeWidth="1.8" />
      <path d="m12.5 12.2 8 0v3h-2.4v2.4h-3v-2.4h-2.6" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M8.5 8.2v3.6M6.7 10h3.6" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function RecoveryIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M20 8.5V4l-2 2a8 8 0 1 0 1.3 10" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M9 11.5h6M9 15h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function BrowserSessionIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect height="15" rx="2.5" stroke="currentColor" strokeWidth="1.8" width="20" x="2" y="3" />
      <path d="M2 7h20M8 22h8M10 18v4M14 18v4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
      <path d="M5.5 5h.01M8 5h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="2" />
    </Icon>
  );
}

export function AccountDeviceIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect height="20" rx="3" stroke="currentColor" strokeWidth="1.8" width="13" x="5.5" y="2" />
      <path d="M9.5 5h5M10 18.5h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function TicketIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4 4h16v5a3 3 0 0 0 0 6v5H4v-5a3 3 0 0 0 0-6V4Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M9 8h6M9 12h6M9 16h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function DeleteAccountIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4 7h16M9 3h6l1 4H8l1-4ZM6.5 7l1 14h9l1-14" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="m10 11 4 5M14 11l-4 5" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function GoogleIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" height="20" viewBox="0 0 24 24" width="20" {...props}>
      <path d="M21.6 12.2c0-.7-.1-1.4-.2-2.1H12v4h5.4a4.6 4.6 0 0 1-2 3v2.6h3.3c1.9-1.8 2.9-4.4 2.9-7.5Z" fill="#4285F4" />
      <path d="M12 22c2.7 0 5-.9 6.7-2.3l-3.3-2.6c-.9.6-2.1 1-3.4 1-2.6 0-4.9-1.8-5.7-4.2H2.9v2.7A10 10 0 0 0 12 22Z" fill="#34A853" />
      <path d="M6.3 13.9A6 6 0 0 1 6 12c0-.7.1-1.3.3-1.9V7.4H2.9A10 10 0 0 0 2 12c0 1.6.4 3.2 1 4.6l3.3-2.7Z" fill="#FBBC05" />
      <path d="M12 5.9c1.5 0 2.8.5 3.9 1.5l2.9-2.9A9.8 9.8 0 0 0 12 2a10 10 0 0 0-9.1 5.4l3.4 2.7A6 6 0 0 1 12 5.9Z" fill="#EA4335" />
    </svg>
  );
}

export function SendReplyIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="m3 4 18 8-18 8 3-8-3-8Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.8" />
      <path d="M6 12h9" stroke="currentColor" strokeLinecap="round" strokeWidth="1.7" />
    </Icon>
  );
}

export function CopyIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect height="13" rx="2" stroke="currentColor" strokeWidth="1.8" width="13" x="8" y="8" />
      <path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" />
    </Icon>
  );
}
