import { workflowsEnabled } from "../lib/features";
import { OperationsOverview } from "./components/operations-overview";
export const dynamic = "force-dynamic";
export default function Home() {
  return <main id="main-content" className="overview-page"><OperationsOverview workflows={workflowsEnabled()} /></main>;
}
