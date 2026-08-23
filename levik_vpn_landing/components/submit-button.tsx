"use client";

import type { ReactNode } from "react";
import { useFormStatus } from "react-dom";

type SubmitButtonProps = {
  children: ReactNode;
  className?: string;
  pendingText?: string;
};

export function SubmitButton({
  children,
  className = "button button--primary",
  pendingText = "Подождите…",
}: SubmitButtonProps) {
  const { pending } = useFormStatus();

  return (
    <button className={className} disabled={pending} type="submit">
      {pending ? <span aria-hidden="true" className="button-spinner" /> : null}
      {pending ? pendingText : children}
    </button>
  );
}
