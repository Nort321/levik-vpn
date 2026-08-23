import { AlertIcon, CheckIcon } from "@/components/icons";

const notices: Readonly<Record<string, string>> = {
  completed: "Устройство подтверждено. Вернитесь в приложение Levik VPN.",
  device_removed: "Доступ устройства отозван.",
  identity_removed: "Способ входа отвязан.",
  identity_linked: "Способ входа привязан.",
  passkey_registered: "Passkey добавлен.",
  passkey_removed: "Passkey отозван.",
  passkey_renamed: "Название passkey обновлено.",
  password_saved: "Парольная identity обновлена.",
  recovery_generated: "Новый комплект recovery-кодов создан.",
  reply_sent: "Ответ отправлен в поддержку.",
  session_removed: "Сеанс завершён.",
  ticket_created: "Обращение создано. Ответ появится на этой странице.",
};

const errors: Readonly<Record<string, string>> = {
  expired: "Запрос уже истёк. Создайте новый запрос в приложении.",
  identity_conflict:
    "Эта identity уже связана с другим аккаунтом. Автоматическое объединение запрещено.",
  invalid_credentials: "Levik ID или пароль не подошли.",
  invalid_recovery_code: "Recovery-код не подошёл или уже был использован.",
  last_identity:
    "Нельзя удалить последний способ входа. Сначала добавьте другой способ восстановления.",
  rate_limited: "Слишком много попыток. Подождите и попробуйте позже.",
  reauthentication_required:
    "Для этого действия нужно заново подтвердить личность.",
  request_failed: "Не удалось выполнить запрос. Проверьте данные и попробуйте снова.",
  temporarily_unavailable:
    "Levik Account временно недоступен. Ваши данные не изменены — попробуйте позже.",
};

export function ActionFeedback({
  error,
  notice,
}: {
  error?: string;
  notice?: string;
}) {
  const errorMessage = error ? errors[error] : undefined;
  const noticeMessage = notice ? notices[notice] : undefined;
  if (!errorMessage && !noticeMessage) return null;

  return (
    <div
      className={`account-feedback ${errorMessage ? "account-feedback--error" : "account-feedback--success"}`}
      role={errorMessage ? "alert" : "status"}
    >
      {errorMessage ? <AlertIcon /> : <CheckIcon />}
      <span>{errorMessage ?? noticeMessage}</span>
    </div>
  );
}
