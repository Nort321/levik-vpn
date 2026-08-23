import "server-only";

import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

export type AppUpdateRecord = {
  id: string;
  versionCode: number;
  versionName: string;
  minSupportedVersionCode: number;
  fileName: string;
  downloadUrl: string;
  fileSize: number;
  sha256: string;
  titleRu: string;
  titleEn: string;
  changelogRu: string;
  changelogEn: string | null;
  forceUpdate: boolean;
  isActive: boolean;
  createdByUserKey: string;
  createdAt: Date;
};

type AppUpdateRow = {
  id: string;
  version_code: number;
  version_name: string;
  min_supported_version_code: number;
  file_name: string;
  download_url: string;
  file_size: string | number;
  sha256: string;
  title_ru: string;
  title_en: string;
  changelog_ru: string;
  changelog_en: string | null;
  force_update: boolean;
  is_active: boolean;
  created_by_user_key: string;
  created_at: Date;
};

export function isAdminUser(userKey?: string | null): boolean {
  if (!userKey) return false;
  const environment = getEnvironment();
  return (
    environment.FEATURE_ADMIN_UPDATES_ENABLED &&
    environment.adminUserKeys.has(userKey)
  );
}

function mapRow(row: AppUpdateRow): AppUpdateRecord {
  return {
    id: row.id,
    versionCode: row.version_code,
    versionName: row.version_name,
    minSupportedVersionCode: row.min_supported_version_code,
    fileName: row.file_name,
    downloadUrl: row.download_url,
    fileSize: Number(row.file_size),
    sha256: row.sha256,
    titleRu: row.title_ru,
    titleEn: row.title_en,
    changelogRu: row.changelog_ru,
    changelogEn: row.changelog_en,
    forceUpdate: row.force_update,
    isActive: row.is_active,
    createdByUserKey: row.created_by_user_key,
    createdAt: row.created_at,
  };
}

export async function getActiveAppUpdate(): Promise<AppUpdateRecord | null> {
  const result = await query<AppUpdateRow>(
    `
      SELECT *
      FROM app_updates
      WHERE is_active = TRUE
      ORDER BY version_code DESC
      LIMIT 1
    `,
  );
  return result.rows[0] ? mapRow(result.rows[0]) : null;
}

export async function listAppUpdates(): Promise<AppUpdateRecord[]> {
  const result = await query<AppUpdateRow>(
    `
      SELECT *
      FROM app_updates
      ORDER BY version_code DESC
    `,
  );
  return result.rows.map(mapRow);
}

export type CreateAppUpdateInput = {
  versionCode: number;
  versionName: string;
  minSupportedVersionCode?: number;
  fileName: string;
  downloadUrl: string;
  fileSize: number;
  sha256: string;
  titleRu?: string;
  titleEn?: string;
  changelogRu: string;
  changelogEn?: string | null;
  forceUpdate?: boolean;
  createdByUserKey: string;
};

export async function createAppUpdate(
  input: CreateAppUpdateInput,
): Promise<AppUpdateRecord> {
  return withTransaction(async (client) => {
    await client.query(
      "UPDATE app_updates SET is_active = FALSE WHERE is_active = TRUE",
    );

    const result = await client.query<AppUpdateRow>(
      `
        INSERT INTO app_updates (
          version_code,
          version_name,
          min_supported_version_code,
          file_name,
          download_url,
          file_size,
          sha256,
          title_ru,
          title_en,
          changelog_ru,
          changelog_en,
          force_update,
          is_active,
          created_by_user_key
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, TRUE, $13)
        RETURNING *
      `,
      [
        input.versionCode,
        input.versionName.trim(),
        input.minSupportedVersionCode ?? 1,
        input.fileName.trim(),
        input.downloadUrl.trim(),
        input.fileSize,
        input.sha256.trim().toLowerCase(),
        input.titleRu?.trim() ||
          `Обновление LevikVPN ${input.versionName.trim()}`,
        input.titleEn?.trim() ||
          `LevikVPN ${input.versionName.trim()} Update`,
        input.changelogRu.trim(),
        input.changelogEn?.trim() || null,
        Boolean(input.forceUpdate),
        input.createdByUserKey,
      ],
    );

    return mapRow(result.rows[0]);
  });
}

export async function setActiveAppUpdate(id: string): Promise<boolean> {
  return withTransaction(async (client) => {
    await client.query("UPDATE app_updates SET is_active = FALSE");
    const result = await client.query(
      "UPDATE app_updates SET is_active = TRUE WHERE id = $1 RETURNING id",
      [id],
    );
    return (result.rowCount ?? 0) > 0;
  });
}

export async function deleteAppUpdate(id: string): Promise<boolean> {
  const result = await query(
    "DELETE FROM app_updates WHERE id = $1 RETURNING id",
    [id],
  );
  return (result.rowCount ?? 0) > 0;
}
