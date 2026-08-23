import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { requireSession } from "@/lib/server/browser-auth";
import {
  getActiveAppUpdate,
  isAdminUser,
  listAppUpdates,
} from "@/lib/server/app-updates";
import { UpdatesManager } from "@/components/dashboard/updates-manager";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Управление обновлениями APK — Админ-панель",
  robots: {
    index: false,
    follow: false,
    nocache: true,
  },
};

export default async function AdminUpdatesPage() {
  const session = await requireSession();
  if (!isAdminUser(session.userKey)) {
    redirect("/dashboard");
  }

  const [activeUpdate, allUpdates] = await Promise.all([
    getActiveAppUpdate(),
    listAppUpdates(),
  ]);

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Администратор</span>
          <h1>Управление OTA-обновлениями Android</h1>
          <p>
            Загружайте новые версии APK прямо через веб-интерфейс. Хэш SHA-256 и манифест для клиентов создаются автоматически.
          </p>
        </div>
      </header>

      <UpdatesManager
        activeUpdate={activeUpdate}
        adminUserKey={session.userKey}
        initialUpdates={allUpdates}
      />
    </>
  );
}
