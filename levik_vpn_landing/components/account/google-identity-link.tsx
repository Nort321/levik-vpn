"use client";

import Script from "next/script";
import { useCallback, useEffect, useRef, useState } from "react";

import { GoogleIcon } from "@/components/account/account-icons";
import {
  accountClientRequest,
  accountErrorMessage,
  isRecord,
  requiredString,
} from "@/components/login/account-api-client";

type GoogleConfiguration = {
  clientId: string;
  nonce: string;
};

type GoogleCredentialResponse = { credential?: string };

export function GoogleIdentityLink({
  csrfToken,
  cspNonce,
}: {
  csrfToken: string;
  cspNonce?: string;
}) {
  const [configuration, setConfiguration] = useState<GoogleConfiguration | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const button = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let active = true;
    accountClientRequest("/auth/providers")
      .then((value) => {
        if (!active || !isRecord(value) || value.ok !== true || !isRecord(value.providers)) {
          return;
        }
        const google = value.providers.google;
        if (!isRecord(google) || google.enabled !== true) return;
        setConfiguration({
          clientId: requiredString(google.clientId, 1, 512),
          nonce: requiredString(google.nonce, 16, 512),
        });
      })
      .catch((caught: unknown) => {
        if (active) setError(accountErrorMessage(caught));
      });
    return () => {
      active = false;
    };
  }, []);

  const linkCredential = useCallback(
    async (response: GoogleCredentialResponse) => {
      if (!configuration || !response.credential) {
        setError("Google не вернул подтверждение identity.");
        return;
      }
      setPending(true);
      setError(null);
      try {
        const result = await accountClientRequest("/identities", {
          method: "POST",
          body: {
            provider: "google",
            idToken: response.credential,
            nonce: configuration.nonce,
          },
          csrfToken,
        });
        if (!isRecord(result) || result.ok !== true) {
          throw new Error("Invalid identity response");
        }
        window.location.assign("/dashboard/identities?notice=identity_linked");
      } catch (caught) {
        setError(accountErrorMessage(caught));
        setPending(false);
      }
    },
    [configuration, csrfToken],
  );

  useEffect(() => {
    const google = window.google?.accounts.id;
    const target = button.current;
    if (!loaded || !google || !target || !configuration) return;
    target.replaceChildren();
    google.initialize({
      client_id: configuration.clientId,
      callback: (response) => void linkCredential(response),
      nonce: configuration.nonce,
      auto_select: false,
      cancel_on_tap_outside: true,
      use_fedcm_for_button: true,
    });
    google.renderButton(target, {
      type: "standard",
      theme: "filled_black",
      size: "large",
      shape: "pill",
      text: "continue_with",
      width: Math.min(320, Math.max(240, target.clientWidth)),
    });
    return () => google.cancel();
  }, [configuration, linkCredential, loaded]);

  return (
    <div className="identity-link-provider" aria-busy={pending}>
      <Script
        nonce={cspNonce}
        onError={() => setError("Не удалось загрузить безопасную форму Google.")}
        onLoad={() => setLoaded(true)}
        src="https://accounts.google.com/gsi/client"
        strategy="afterInteractive"
      />
      <span className="identity-link-provider__icon"><GoogleIcon /></span>
      <div>
        <strong>Google</strong>
        <p>Связь создаётся по стабильному Google ID. Email не используется для восстановления.</p>
      </div>
      {configuration ? (
        <div aria-label="Привязать Google" className={pending ? "is-disabled" : undefined} ref={button} />
      ) : (
        <span className="identity-link-provider__unavailable">Недоступно</span>
      )}
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </div>
  );
}
