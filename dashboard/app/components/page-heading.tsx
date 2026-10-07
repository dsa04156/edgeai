import type { ReactNode } from "react";
export function PageHeading({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return <div className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p className="intro">{description}</p></div>{action && <div className="page-actions">{action}</div>}</div>;
}
