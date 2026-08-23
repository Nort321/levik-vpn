"use client";

import { useEffect, useId, useRef, useState } from "react";

import { CheckIcon, ChevronDownIcon, ClockIcon } from "@/components/icons";

const EXPIRY_OPTIONS = [
  { value: 1, label: "1 день", number: "01" },
  { value: 7, label: "7 дней", number: "07" },
  { value: 14, label: "14 дней", number: "14" },
  { value: 30, label: "30 дней", number: "30" },
] as const;

type ExpiryAccordionProps = {
  value: number;
  onChange: (value: number) => void;
};

export function ExpiryAccordion({ value, onChange }: ExpiryAccordionProps) {
  const [expanded, setExpanded] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const groupName = useId();
  const panelId = `${groupName}-panel`;
  const selected = EXPIRY_OPTIONS.find((option) => option.value === value) ?? EXPIRY_OPTIONS[1];

  useEffect(() => {
    if (!expanded) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setExpanded(false);
    };
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () => document.removeEventListener("pointerdown", closeOnOutsidePointer);
  }, [expanded]);

  return (
    <div
      className={`notes-expiry${expanded ? " notes-expiry--expanded" : ""}`}
      ref={rootRef}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          setExpanded(false);
          triggerRef.current?.focus();
        }
      }}
    >
      <button
        aria-controls={panelId}
        aria-expanded={expanded}
        className="notes-expiry__trigger"
        onClick={() => setExpanded((current) => !current)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown") {
            event.preventDefault();
            setExpanded(true);
            window.setTimeout(() => panelRef.current?.querySelector<HTMLInputElement>("input:checked")?.focus());
          }
        }}
        ref={triggerRef}
        type="button"
      >
        <span className="notes-expiry__trigger-icon"><ClockIcon /></span>
        <span>
          <small>Срок хранения</small>
          <strong>{selected.label}</strong>
        </span>
        <ChevronDownIcon className="notes-expiry__chevron" />
      </button>
      <div
        aria-hidden={!expanded}
        className="notes-expiry__panel"
        id={panelId}
        inert={!expanded}
        ref={panelRef}
      >
        <fieldset aria-label="Выберите срок хранения">
          <legend className="sr-only">Срок хранения заметки</legend>
          {EXPIRY_OPTIONS.map((option) => (
            <label className="notes-expiry__option" key={option.value}>
              <input
                checked={value === option.value}
                name={groupName}
                onChange={() => {
                  onChange(option.value);
                  setExpanded(false);
                }}
                type="radio"
                value={option.value}
              />
              <span className="notes-expiry__number">{option.number}</span>
              <span className="notes-expiry__copy">
                <strong>{option.label}</strong>
              </span>
              <span className="notes-expiry__check"><CheckIcon /></span>
            </label>
          ))}
        </fieldset>
      </div>
    </div>
  );
}
