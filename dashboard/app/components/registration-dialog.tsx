"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";

export function RegistrationDialog({ open, title, busy = false, onClose, children }: {
  open: boolean; title: string; busy?: boolean; onClose: () => void; children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    if (!open) return;
    const dialog = ref.current!;
    const previous = document.activeElement;
    const bodyOverflow = document.body.style.overflow;
    const rootOverflow = document.documentElement.style.overflow;
    dialog.showModal();
    document.body.style.overflow = "hidden";
    document.documentElement.style.overflow = "hidden";
    const input = Array.from(dialog.querySelectorAll<HTMLElement>('input:not(:disabled):not([readonly]):not([type="hidden"]), select:not(:disabled), textarea:not(:disabled)')).find(element => element.getClientRects().length > 0);
    (input ?? dialog.querySelector<HTMLElement>("h2"))?.focus({ preventScroll: true });
    return () => {
      dialog.close();
      document.body.style.overflow = bodyOverflow;
      document.documentElement.style.overflow = rootOverflow;
      if (previous instanceof HTMLElement && previous.isConnected && !previous.matches(":disabled") &&
          (document.activeElement === document.body || dialog.contains(document.activeElement))) previous.focus({ preventScroll: true });
    };
  }, [open]);
  return <dialog ref={ref} className="registration-dialog" aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}
    onClose={event => { if (!event.currentTarget.open && open) onClose(); }}>
    <header className="registration-dialog-header"><h2 id={titleId} tabIndex={-1}>{title}</h2><button type="button" disabled={busy} onClick={onClose} aria-label={`${title} 닫기`}>닫기</button></header>
    <div className="registration-dialog-body">{children}</div>
    <footer className="registration-dialog-footer"><button type="button" disabled={busy} onClick={onClose}>취소</button></footer>
  </dialog>;
}
