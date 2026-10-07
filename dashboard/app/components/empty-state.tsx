import type { ReactNode } from "react";

export function EmptyState({ title, description, action }: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return <div className="empty-state">
    <span className="empty-state-mark" aria-hidden="true">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="4" width="18" height="16" rx="3" /><path d="M3 10h18M7 7h.01M10 7h.01M9 15h6" />
      </svg>
    </span>
    <strong>{title}</strong>
    <p>{description}</p>
    {action && <div className="empty-state-action">{action}</div>}
  </div>;
}
