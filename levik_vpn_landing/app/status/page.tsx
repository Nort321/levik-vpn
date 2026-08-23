import type { Metadata } from "next";

import { ServerStatusDashboard } from "@/components/status/server-status-dashboard";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { getOptionalSession } from "@/lib/server/browser-auth";
import { getStatusSnapshot } from "@/lib/server/remnawave-status";
import type { StatusSnapshot } from "@/lib/status";

export const metadata: Metadata = {
  title: "Статус серверов",
  description:
    "Актуальная доступность серверов Levik VPN по странам и интерактивная карта сети.",
  alternates: { canonical: "/status" },
};

export default async function StatusPage() {
  const [session, initialSnapshot] = await Promise.all([
    getOptionalSession(),
    getStatusSnapshot().catch(() => null as StatusSnapshot | null),
  ]);

  return (
    <div className="site-page status-page">
      <SiteHeader authenticated={Boolean(session)} />
      <main id="main-content">
        <ServerStatusDashboard initialSnapshot={initialSnapshot} />
      </main>
      <SiteFooter />
    </div>
  );
}
