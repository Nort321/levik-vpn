"use client";

import { useState, useRef } from "react";
import type { AppUpdateRecord } from "@/lib/server/app-updates";
import {
  UpdateIcon,
  ShieldCheckIcon,
  ArrowUpRightIcon,
  CheckIcon,
  CopyIcon,
  DownloadIcon,
} from "@/components/icons";

type UpdatesManagerProps = {
  initialUpdates: AppUpdateRecord[];
  activeUpdate: AppUpdateRecord | null;
  adminUserKey: string;
};

type ClientAppUpdateRecord = Omit<AppUpdateRecord, "createdAt"> & {
  createdAt: Date | string;
};

const MAX_APK_BYTES = 350 * 1024 * 1024;
const UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
const UPLOAD_RETRY_COUNT = 3;

function encodeUploadMetadata(value: Record<string, unknown>): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isAppUpdateRecord(value: unknown): value is ClientAppUpdateRecord {
  if (!isRecord(value)) return false;
  return (
    typeof value.id === "string" &&
    typeof value.versionCode === "number" &&
    typeof value.versionName === "string" &&
    typeof value.minSupportedVersionCode === "number" &&
    typeof value.fileName === "string" &&
    typeof value.downloadUrl === "string" &&
    typeof value.fileSize === "number" &&
    typeof value.sha256 === "string" &&
    typeof value.titleRu === "string" &&
    typeof value.titleEn === "string" &&
    typeof value.changelogRu === "string" &&
    (typeof value.changelogEn === "string" || value.changelogEn === null) &&
    typeof value.forceUpdate === "boolean" &&
    typeof value.isActive === "boolean" &&
    typeof value.createdByUserKey === "string" &&
    (typeof value.createdAt === "string" || value.createdAt instanceof Date)
  );
}

function responseError(value: unknown, fallback: string): string {
  return isRecord(value) && typeof value.error === "string"
    ? value.error
    : fallback;
}

function sendUploadChunk({
  file,
  metadata,
  uploadId,
  offset,
  onProgress,
}: {
  file: File;
  metadata: string;
  uploadId: string;
  offset: number;
  onProgress: (uploadedBytes: number) => void;
}): Promise<unknown> {
  const chunk = file.slice(offset, Math.min(offset + UPLOAD_CHUNK_BYTES, file.size));
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.timeout = 5 * 60 * 1000;
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(offset + event.loaded);
    };
    xhr.onload = () => {
      let response: unknown;
      try {
        response = JSON.parse(xhr.responseText);
      } catch {
        reject(new Error(`Сервер вернул некорректный ответ (${xhr.status || "нет статуса"})`));
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(response);
      } else {
        reject(new Error(responseError(response, `Ошибка загрузки (${xhr.status})`)));
      }
    };
    xhr.onerror = () => reject(new Error("Соединение прервано во время загрузки"));
    xhr.ontimeout = () => reject(new Error("Превышено время ожидания части APK"));
    xhr.open("POST", "/api/admin/updates/upload");
    xhr.setRequestHeader("Content-Type", "application/vnd.android.package-archive");
    xhr.setRequestHeader("X-Levik-Update-Metadata", metadata);
    xhr.setRequestHeader("X-Levik-Upload-Id", uploadId);
    xhr.setRequestHeader("X-Levik-Chunk-Offset", String(offset));
    xhr.send(chunk);
  });
}

export function UpdatesManager({
  initialUpdates,
  activeUpdate,
}: UpdatesManagerProps) {
  const [updates, setUpdates] = useState<ClientAppUpdateRecord[]>(initialUpdates);
  const [active, setActive] = useState<ClientAppUpdateRecord | null>(activeUpdate);

  // Form states
  const [file, setFile] = useState<File | null>(null);
  const [versionCode, setVersionCode] = useState<string>(
    active ? String(active.versionCode + 1) : "12",
  );
  const [versionName, setVersionName] = useState<string>(
    active ? incrementVersionName(active.versionName) : "1.3.1",
  );
  const [minSupportedVersionCode] = useState<string>("1");
  const [changelogRu, setChangelogRu] = useState<string>(
    "• Улучшена стабильность подключения\n• Исправлены мелкие ошибки",
  );
  const [changelogEn, setChangelogEn] = useState<string>("");
  const [forceUpdate, setForceUpdate] = useState<boolean>(false);

  // Upload progress & loading states
  const [isUploading, setIsUploading] = useState<boolean>(false);
  const [uploadProgress, setUploadProgress] = useState<number>(0);
  const [uploadedMb, setUploadedMb] = useState<string>("0");
  const [totalMb, setTotalMb] = useState<string>("0");
  const [statusMessage, setStatusMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [copiedHash, setCopiedHash] = useState<boolean>(false);

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  function incrementVersionName(current: string): string {
    const parts = current.split(".");
    if (parts.length === 3) {
      const patch = parseInt(parts[2], 10);
      if (!isNaN(patch)) {
        return `${parts[0]}.${parts[1]}.${patch + 1}`;
      }
    }
    return `${current}.1`;
  }

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0];
    if (selected) {
      if (!selected.name.endsWith(".apk")) {
        setStatusMessage({ type: "error", text: "Пожалуйста, выберите файл с расширением .apk" });
        return;
      }
      setFile(selected);
      setStatusMessage(null);
    }
  }

  function handleDrop(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) {
      if (!dropped.name.endsWith(".apk")) {
        setStatusMessage({ type: "error", text: "Пожалуйста, выберите файл с расширением .apk" });
        return;
      }
      setFile(dropped);
      setStatusMessage(null);
    }
  }

  async function handleUpload(e: React.FormEvent) {
    e.preventDefault();
    if (!file) {
      setStatusMessage({ type: "error", text: "Выберите файл APK для загрузки" });
      return;
    }
    if (file.size > MAX_APK_BYTES) {
      setStatusMessage({ type: "error", text: "APK превышает допустимый размер 350 МБ" });
      return;
    }

    const vCode = parseInt(versionCode, 10);
    if (isNaN(vCode) || vCode <= 0) {
      setStatusMessage({ type: "error", text: "Укажите корректный Version Code (целое число)" });
      return;
    }

    if (!versionName.trim()) {
      setStatusMessage({ type: "error", text: "Укажите название версии (Version Name)" });
      return;
    }

    if (!changelogRu.trim()) {
      setStatusMessage({ type: "error", text: "Укажите список изменений на русском" });
      return;
    }

    setIsUploading(true);
    setUploadProgress(0);
    setStatusMessage(null);

    const metadata = encodeUploadMetadata({
      versionCode: vCode,
      versionName: versionName.trim(),
      minSupportedVersionCode: Number.parseInt(minSupportedVersionCode, 10) || 1,
      changelogRu: changelogRu.trim(),
      changelogEn: changelogEn.trim(),
      forceUpdate,
      totalSize: file.size,
    });
    const uploadId = crypto.randomUUID();
    let offset = 0;

    try {
      let uploadedRecord: ClientAppUpdateRecord | null = null;
      while (offset < file.size) {
        let response: unknown = null;
        let lastError: Error | null = null;
        for (let attempt = 0; attempt < UPLOAD_RETRY_COUNT; attempt += 1) {
          try {
            response = await sendUploadChunk({
              file,
              metadata,
              uploadId,
              offset,
              onProgress: (uploadedBytes) => {
                setUploadedMb((uploadedBytes / (1024 * 1024)).toFixed(1));
                setTotalMb((file.size / (1024 * 1024)).toFixed(1));
                setUploadProgress(Math.round((uploadedBytes / file.size) * 100));
              },
            });
            lastError = null;
            break;
          } catch (error) {
            lastError = error instanceof Error ? error : new Error("Ошибка загрузки части APK");
            if (attempt + 1 < UPLOAD_RETRY_COUNT) {
              await new Promise((resolve) => setTimeout(resolve, 1_000 * (attempt + 1)));
            }
          }
        }
        if (lastError) throw lastError;
        if (!isRecord(response) || response.ok !== true || typeof response.nextOffset !== "number") {
          throw new Error("Сервер вернул некорректное смещение загрузки");
        }
        if (response.nextOffset <= offset || response.nextOffset > file.size) {
          throw new Error("Сервер вернул недопустимое смещение загрузки");
        }
        offset = response.nextOffset;
        setUploadProgress(Math.round((offset / file.size) * 100));
        if (response.complete === true) {
          if (!isAppUpdateRecord(response.update)) {
            throw new Error("Сервер не вернул опубликованный релиз");
          }
          uploadedRecord = response.update;
        }
      }

      if (!uploadedRecord) throw new Error("Загрузка завершилась без публикации релиза");
      setStatusMessage({
        type: "success",
        text: `Версия ${versionName} (код ${versionCode}) успешно загружена и опубликована!`,
      });
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
      setActive(uploadedRecord);
      setUpdates((prev) => [
        uploadedRecord,
        ...prev.map((update) => ({ ...update, isActive: false })),
      ]);
      setVersionCode(String(vCode + 1));
      setVersionName(incrementVersionName(versionName));
    } catch (error) {
      setStatusMessage({
        type: "error",
        text: error instanceof Error ? error.message : "Не удалось загрузить APK",
      });
    } finally {
      setIsUploading(false);
    }
  }

  async function handleSetActive(id: string) {
    try {
      const res = await fetch("/api/admin/updates/manage", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "set_active", id }),
      });
      const data: unknown = await res.json();
      if (isRecord(data) && data.ok === true) {
        setUpdates((prev) =>
          prev.map((u) => ({
            ...u,
            isActive: u.id === id,
          })),
        );
        const newActive = updates.find((u) => u.id === id) || null;
        if (newActive) setActive({ ...newActive, isActive: true });
        setStatusMessage({ type: "success", text: "Активная версия обновлена!" });
      } else {
        setStatusMessage({ type: "error", text: responseError(data, "Ошибка при активации") });
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Ошибка запроса";
      setStatusMessage({ type: "error", text: msg });
    }
  }

  async function handleDelete(id: string, name: string) {
    if (!confirm(`Удалить релиз ${name}?`)) return;
    try {
      const res = await fetch("/api/admin/updates/manage", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "delete", id }),
      });
      const data: unknown = await res.json();
      if (isRecord(data) && data.ok === true) {
        setUpdates((prev) => prev.filter((u) => u.id !== id));
        if (active?.id === id) {
          const next = updates.find((u) => u.id !== id) || null;
          setActive(next);
        }
        setStatusMessage({ type: "success", text: "Релиз удален." });
      } else {
        setStatusMessage({ type: "error", text: responseError(data, "Ошибка при удалении") });
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Ошибка запроса";
      setStatusMessage({ type: "error", text: msg });
    }
  }

  function copyToClipboard(text: string) {
    void navigator.clipboard.writeText(text).then(() => {
      setCopiedHash(true);
      setTimeout(() => setCopiedHash(false), 2500);
    }).catch(() => {
      setStatusMessage({ type: "error", text: "Не удалось скопировать значение" });
    });
  }

  function formatSize(bytes: number): string {
    if (bytes <= 0) return "0 MB";
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(1)} МБ`;
  }

  return (
    <div className="updates-manager">
      {statusMessage && (
        <div
          className={`dashboard-notice dashboard-notice--${
            statusMessage.type === "success" ? "positive" : "warning"
          }`}
          style={{ marginBottom: "24px" }}
        >
          <strong>{statusMessage.type === "success" ? "Успешно" : "Внимание"}</strong>
          <span>{statusMessage.text}</span>
        </div>
      )}

      {/* ACTIVE RELEASE OVERVIEW */}
      <section className="dashboard-section dashboard-section--panel" style={{ marginBottom: "28px" }}>
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">Текущая версия для клиентов</span>
            <h2>Активное OTA-обновление</h2>
          </div>
          <a
            className="button button--quiet button--compact"
            href="/api/mobile/v1/update"
            rel="noopener noreferrer"
            target="_blank"
            title="Проверить ответ API"
          >
            <ArrowUpRightIcon />
            Тест API /update
          </a>
        </div>

        {active ? (
          <div className="active-update-card">
            <div className="active-update-card__header">
              <div>
                <span className="active-update-badge">
                  <ShieldCheckIcon /> v{active.versionName}
                </span>
                <span className="active-update-code">VersionCode: <b>{active.versionCode}</b></span>
                {active.forceUpdate && (
                  <span className="active-update-force">Обязательное обновление</span>
                )}
              </div>
              <a
                className="button button--primary button--compact"
                download
                href={active.downloadUrl}
              >
                <DownloadIcon />
                Скачать APK ({formatSize(active.fileSize)})
              </a>
            </div>

            <div className="active-update-details">
              <div className="active-update-field">
                <span className="label">SHA-256 Контрольная сумма:</span>
                <code className="sha-code">
                  {active.sha256}
                  <button
                    className="copy-btn"
                    onClick={() => copyToClipboard(active.sha256)}
                    type="button"
                  >
                    {copiedHash ? <CheckIcon /> : <CopyIcon />}
                  </button>
                </code>
              </div>
              <div className="active-update-field">
                <span className="label">Ссылка для скачивания:</span>
                <a className="link-url" href={active.downloadUrl} target="_blank">
                  {active.downloadUrl}
                </a>
              </div>
              <div className="active-update-changelog">
                <span className="label">Что нового (RU):</span>
                <pre>{active.changelogRu}</pre>
              </div>
            </div>
          </div>
        ) : (
          <p className="dashboard-section__copy">
            Активное обновление еще не загружено. Клиенты используют базовую версию 1.3.0.
          </p>
        )}
      </section>

      {/* UPLOAD FORM */}
      <section className="dashboard-section dashboard-section--panel" style={{ marginBottom: "28px" }}>
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">Публикация нового релиза</span>
            <h2>Загрузить новый APK файл</h2>
          </div>
          <UpdateIcon height={28} width={28} />
        </div>

        <form className="update-upload-form" onSubmit={(event) => { void handleUpload(event); }}>
          {/* DRAG & DROP ZONE */}
          <div
            className={`file-drop-zone ${file ? "file-drop-zone--has-file" : ""}`}
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={handleDrop}
          >
            <input
              accept=".apk,application/vnd.android.package-archive"
              disabled={isUploading}
              onChange={handleFileSelect}
              ref={fileInputRef}
              style={{ display: "none" }}
              type="file"
            />
            <UpdateIcon height={36} width={36} />
            {file ? (
              <div className="file-drop-info">
                <strong>{file.name}</strong>
                <span>{(file.size / (1024 * 1024)).toFixed(1)} МБ • Нажмите, чтобы заменить</span>
              </div>
            ) : (
              <div className="file-drop-info">
                <strong>Перетащите .apk файл сюда или нажмите для выбора</strong>
                <span>Принимаются файлы сборщика Android (.apk) до 350 МБ</span>
              </div>
            )}
          </div>

          <div className="form-grid-2">
            <div className="form-group">
              <label htmlFor="versionName">Название версии (Version Name)</label>
              <input
                disabled={isUploading}
                id="versionName"
                onChange={(e) => setVersionName(e.target.value)}
                placeholder="1.3.1"
                required
                type="text"
                value={versionName}
              />
              <small>Например: 1.3.1 или 1.4.0</small>
            </div>

            <div className="form-group">
              <label htmlFor="versionCode">Код версии (Version Code)</label>
              <input
                disabled={isUploading}
                id="versionCode"
                min="1"
                onChange={(e) => setVersionCode(e.target.value)}
                placeholder="12"
                required
                type="number"
                value={versionCode}
              />
              <small>Строго больше предыдущей версии (было {active?.versionCode ?? 11})</small>
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="changelogRu">Список изменений на русском (Что нового)</label>
            <textarea
              disabled={isUploading}
              id="changelogRu"
              onChange={(e) => setChangelogRu(e.target.value)}
              placeholder="• Добавлена новая функция..."
              required
              rows={4}
              value={changelogRu}
            />
          </div>

          <div className="form-group">
            <label htmlFor="changelogEn">Список изменений на английском (Optional)</label>
            <textarea
              disabled={isUploading}
              id="changelogEn"
              onChange={(e) => setChangelogEn(e.target.value)}
              placeholder="• New feature added..."
              rows={3}
              value={changelogEn}
            />
          </div>

          <div className="form-checkbox-group">
            <label className="checkbox-label">
              <input
                checked={forceUpdate}
                disabled={isUploading}
                onChange={(e) => setForceUpdate(e.target.checked)}
                type="checkbox"
              />
              <span>Принудительное обновление (Force Update) — блокировать старые версии приложения</span>
            </label>
          </div>

          {/* PROGRESS BAR */}
          {isUploading && (
            <div className="upload-progress-box">
              <div className="upload-progress-info">
                <span>Загрузка APK на сервер…</span>
                <strong>{uploadedMb} МБ / {totalMb} МБ ({uploadProgress}%)</strong>
              </div>
              <div className="upload-progress-bar">
                <div
                  className="upload-progress-fill"
                  style={{ width: `${uploadProgress}%` }}
                />
              </div>
            </div>
          )}

          <div className="form-actions">
            <button
              className="button button--primary button--wide"
              disabled={isUploading || !file}
              type="submit"
            >
              <UpdateIcon />
              {isUploading ? `Загрузка (${uploadProgress}%)…` : "Опубликовать обновление для всех"}
            </button>
          </div>
        </form>
      </section>

      {/* RELEASES HISTORY */}
      {updates.length > 0 && (
        <section className="dashboard-section dashboard-section--panel">
          <div className="dashboard-section__head">
            <div>
              <span className="card-kicker">История версий</span>
              <h2>Все загруженные релизы ({updates.length})</h2>
            </div>
          </div>

          <div className="table-responsive">
            <table className="updates-table">
              <thead>
                <tr>
                  <th>Версия</th>
                  <th>Код</th>
                  <th>Размер</th>
                  <th>Дата загрузки</th>
                  <th>Статус</th>
                  <th>Действия</th>
                </tr>
              </thead>
              <tbody>
                {updates.map((u) => (
                  <tr className={u.isActive ? "row--active" : ""} key={u.id}>
                    <td>
                      <strong>v{u.versionName}</strong>
                    </td>
                    <td>{u.versionCode}</td>
                    <td>{formatSize(u.fileSize)}</td>
                    <td>{new Date(u.createdAt).toLocaleDateString("ru-RU")}</td>
                    <td>
                      {u.isActive ? (
                        <span className="status-pill status-pill--active">Активна</span>
                      ) : (
                        <span className="status-pill">Архив</span>
                      )}
                    </td>
                    <td>
                      <div className="table-actions">
                        {!u.isActive && (
                          <button
                            className="button button--quiet button--compact"
                            onClick={() => { void handleSetActive(u.id); }}
                            type="button"
                          >
                            Сделать активной
                          </button>
                        )}
                        <a
                          className="button button--quiet button--compact"
                          download
                          href={u.downloadUrl}
                        >
                          <DownloadIcon />
                        </a>
                        <button
                          className="button button--quiet button--compact button--danger"
                          onClick={() => { void handleDelete(u.id, u.versionName); }}
                          title="Удалить"
                          type="button"
                        >
                          ✕
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
