import type { Metadata } from "next";
import type { ReactNode } from "react";
import { redirect } from "next/navigation";
import { getOptionalSession } from "@/lib/server/browser-auth";
import { getOptionalAccountOverview } from "@/lib/web/account-actions";
import { getDashboardView } from "@/lib/web/view-models";
import { DashboardShell } from "@/components/dashboard/dashboard-shell";

export const metadata: Metadata = {
  title: "Личный кабинет",
  alternates: {
    canonical: "/dashboard",
  },
  robots: {
    index: false,
    follow: false,
    nocache: true,
  },
};

export default async function DashboardLayout({
  children,
}: Readonly<{ children: ReactNode }>) {
  const account = await getOptionalAccountOverview();
  if (account) {
    const currentSession = account.sessions.find((session) => session.current);
    if (!currentSession) redirect("/login");
    return (
      <DashboardShell
        auth={{
          kind: "account",
          csrfToken: account.csrfToken,
          sessionId: currentSession.id,
        }}
        viewer={{ displayName: account.account.displayName }}
      >
        {children}
      </DashboardShell>
    );
  }

  const legacySession = await getOptionalSession(false);
  if (legacySession) {
    const view = await getDashboardView();
    return (
      <DashboardShell
        auth={{ kind: "legacy", csrfToken: view.csrfToken }}
        viewer={view.viewer}
      >
        {children}
      </DashboardShell>
    );
  }

  redirect("/login");
}
