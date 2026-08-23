import Image from "next/image";
import Link from "next/link";

type BrandProps = {
  compact?: boolean;
  href?: string;
};

export function Brand({ compact = false, href = "/" }: BrandProps) {
  return (
    <Link
      aria-label="Levik VPN — на главную"
      className={compact ? "brand brand--compact" : "brand"}
      href={href}
    >
      <Image
        alt=""
        className="brand__logo"
        height={52}
        priority
        src="/assets/levik-shield.png"
        width={52}
      />
    </Link>
  );
}
