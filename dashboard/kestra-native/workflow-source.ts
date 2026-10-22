import type { components } from "./live-api-schema";

export type WorkflowSourceDelivery =
  components["schemas"]["WorkflowSourceDelivery"];
type Source = Record<string, unknown>;

export function selectionKey(value: Source) {
  return value.kind === "SENSOR" && value.deviceId && value.property
    ? `SENSOR:${value.deviceId}:${value.property}`
    : value.kind === "CONTINUOUS_VD" && value.vdId && value.outputId
      ? `CONTINUOUS_VD:${value.vdId}:${value.outputId}`
      : "";
}
/** Absent or unknown delivery is the backward-compatible LATEST polling. */
export function sourceDelivery(value: Source): WorkflowSourceDelivery {
  return value.delivery === "ALL_OBSERVATIONS" ? "ALL_OBSERVATIONS" : "LATEST";
}
/** LATEST stays omitted so existing workflow versions keep their exact parameters. */
export function withDelivery(value: Source, delivery: WorkflowSourceDelivery) {
  const { delivery: _previous, ...rest } = value;
  void _previous;
  return delivery === "ALL_OBSERVATIONS" ? { ...rest, delivery } : rest;
}
/** The server rejects ALL_OBSERVATIONS for string sensor measurements (409 WORKFLOW_SOURCE_UNSUPPORTED). */
export function deliveryUnsupported(
  item: { selection: Source; type: string } | undefined,
  delivery: WorkflowSourceDelivery,
) {
  return (
    delivery === "ALL_OBSERVATIONS" &&
    item?.selection.kind === "SENSOR" &&
    item.type === "string"
  );
}
