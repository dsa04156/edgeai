// Server-only runtime configuration; shared by pages and the API proxy.
export function workflowsEnabled(): boolean {
  return (process.env.EDGEAI_WORKFLOW_ENABLED ?? "true") === "true";
}
