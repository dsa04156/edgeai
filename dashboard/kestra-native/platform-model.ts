import yaml from "js-yaml";
import type { CanvasEdge, CanvasNode } from "./CanvasBoard.vue";

export const unifiedImage = "10.254.192.47:5000/fastdds-unified:v1.0";
export const templates: Record<string, string> = {
  discovery: "Discovery Server",
  sensor_pub: "Sensor Pub",
  alarm_service: "Alarm Service",
  alarm_monitor: "Alarm Monitor",
  camera_pub: "Camera Pub",
  fps_display: "FPS Display",
};
export type DeploymentNode = CanvasNode & {
  template: string;
  port: number;
  image: string;
  replicas: number;
  deployment: string;
  script: string;
  device: string;
  pipeline: string;
  rtsp: string;
  selector: Record<string, string>;
};
export type DeploymentDraft = {
  name: string;
  profile: string;
  discoveryIp: string;
  nodes: DeploymentNode[];
  edges: CanvasEdge[];
};
const dnsName = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
export const sanitizeName = (name: string) =>
  name.toLowerCase().replace(/[^a-z0-9]/g, "-");
export function deploymentNode(
  template: string,
  id: string,
  x = 60,
  y = 60,
): DeploymentNode {
  if (!templates[template] && template !== "app")
    throw new Error("알 수 없는 DDS 템플릿입니다.");
  return {
    id,
    x,
    y,
    label: templates[template] || "App",
    subtitle: template,
    inputs: ["DDS"],
    outputs: ["DDS"],
    template,
    port: 11811,
    image:
      template === "discovery" ? "eprosima/vulcanexus:humble" : unifiedImage,
    replicas: 1,
    deployment: "",
    script: template === "discovery" ? "" : `${template}.py`,
    device: "dev01",
    pipeline: "pipe01",
    rtsp: "",
    selector: {},
  };
}
export function usesBuildImage(image: string, repository?: string) {
  return [unifiedImage.slice(0, unifiedImage.lastIndexOf(":")), repository]
    .filter(Boolean)
    .some(
      (repo) =>
        image === repo ||
        image.startsWith(repo + ":") ||
        image.startsWith(repo + "@"),
    );
}
export function deploymentYaml(
  draft: DeploymentDraft,
  imageOverride = "",
  repository?: string,
) {
  const name = sanitizeName(draft.name);
  if (!dnsName.test(name))
    throw new Error(
      "앱 이름은 영문 소문자·숫자·하이픈 63자 이내로 입력하세요.",
    );
  if (!draft.nodes.length) throw new Error("배포할 노드를 추가하세요.");
  const profiles: Record<string, string> = {
    dds_shm: "/app/dds_shm.xml",
    dds_tcp: "/app/dds_tcp.xml",
    dds_udp: "/app/dds_udp.xml",
    dds_discovery: "/app/dds_udp.xml",
  };
  if (!profiles[draft.profile]) throw new Error("DDS 프로필을 선택하세요.");
  if (draft.nodes.filter((n) => n.template === "discovery").length > 1)
    throw new Error("Discovery Server는 하나만 추가할 수 있습니다.");
  const port = Number(
    draft.nodes.find((n) => n.template === "discovery")?.port ?? 11811,
  );
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error("Discovery 포트는 1~65535 정수여야 합니다.");
  const discovery = `${name}-discovery`,
    hasDiscovery = draft.nodes.some((n) => n.template === "discovery");
  const manifests: Array<{
    apiVersion: string;
    kind: string;
    metadata: { name: string; annotations?: Record<string, string> };
    spec: Record<string, unknown>;
  }> = [];
  const identities = new Set<string>();
  for (const node of draft.nodes) {
    if (
      !Number.isInteger(Number(node.replicas)) ||
      Number(node.replicas) < 0 ||
      Number(node.replicas) > 50
    )
      throw new Error("Replicas는 0~50의 정수여야 합니다.");
    if (!node.image.trim())
      throw new Error(`${node.label}: 이미지를 입력하세요.`);
    const annotations: Record<string, string> = {
      "ui.canvas/id": node.id,
      "ui.canvas/x": String(Math.round(node.x)),
      "ui.canvas/y": String(Math.round(node.y)),
      "ui.canvas/template": node.template,
      "ui.canvas/pipeline": node.pipeline,
      "ui.canvas/label": node.label,
      "ui.canvas/port": String(node.port),
    };
    const device = sanitizeName(node.device),
      pipe = sanitizeName(node.pipeline || "pipe01");
    const suffix: Record<string, string> = {
      discovery: "discovery",
      sensor_pub: `sensor-${device}`,
      alarm_service: `alarm-svc-${device}`,
      alarm_monitor: `alarm-mon-${device}`,
      camera_pub: `camera-${device}`,
      fps_display: `fps-${pipe}`,
    };
    const deployment = sanitizeName(
      node.deployment ||
        `${name}-${suffix[node.template] || node.id.replaceAll("_", "-")}`,
    );
    if (!dnsName.test(deployment) || !deployment.startsWith(name + "-"))
      throw new Error(
        "Deployment 이름은 앱 이름 접두사로 시작하는 63자 이내 이름이어야 합니다.",
      );
    if (identities.has(deployment))
      throw new Error(
        "Deployment 이름이 중복됩니다. 노드 설정에서 이름을 구분하세요.",
      );
    identities.add(deployment);
    const pod: Record<string, unknown> = {
      hostNetwork: true,
      hostIPC: true,
      dnsPolicy: "ClusterFirstWithHostNet",
      ...(Object.keys(node.selector).length
        ? { nodeSelector: node.selector }
        : {}),
      tolerations: [
        {
          key: "environment",
          operator: "Equal",
          value: "edge",
          effect: "NoSchedule",
        },
      ],
    };
    if (node.template === "discovery") {
      manifests.push({
        apiVersion: "v1",
        kind: "Service",
        metadata: { name: discovery },
        spec: {
          selector: { app: discovery },
          ports: ["UDP", "TCP"].map((protocol) => ({
            name: `dds-${protocol.toLowerCase()}`,
            protocol,
            port,
            targetPort: port,
          })),
        },
      });
      pod.containers = [
        {
          name: "server",
          image: node.image,
          imagePullPolicy: "Always",
          command: [
            "/bin/bash",
            "-c",
            `source /opt/vulcanexus/humble/setup.bash && fastdds discovery -i 0 -p ${port}`,
          ],
        },
      ];
    } else {
      const args: Record<string, string[]> = {
        sensor_pub: ["sensor_pub.py", "--sensor_id", device],
        alarm_service: ["alarm_service.py"],
        alarm_monitor: ["alarm_monitor.py"],
        camera_pub: [
          "camera_pub.py",
          "--topic",
          `${pipe}_video_shared`,
          "--rtsp",
          node.rtsp,
          "--camera_id",
          device,
        ],
        fps_display: ["fps_display.py", "--topic", `${pipe}_video_shared`],
      };
      const scriptArgs = args[node.template] || [node.script];
      if (!scriptArgs[0])
        throw new Error(`${node.label}: Python 스크립트를 입력하세요.`);
      const env = [
        {
          name: "FASTRTPS_DEFAULT_PROFILES_FILE",
          value: profiles[draft.profile],
        },
      ];
      if (hasDiscovery)
        env.push({
          name: "ROS_DISCOVERY_SERVER",
          value: `${(draft.discoveryIp.trim() || discovery).replaceAll(",", ".")}:${port}`,
        });
      pod.containers = [
        {
          name: "app",
          image:
            imageOverride && usesBuildImage(node.image, repository)
              ? imageOverride
              : node.image,
          imagePullPolicy: "Always",
          args: ["python3", "-u", ...scriptArgs],
          env,
        },
      ];
    }
    const label = node.template === "discovery" ? discovery : deployment;
    manifests.push({
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: deployment, annotations },
      spec: {
        replicas: Number(node.replicas),
        selector: { matchLabels: { app: label } },
        template: { metadata: { labels: { app: label } }, spec: pod },
      },
    });
  }
  manifests[0].metadata.annotations = {
    ...manifests[0].metadata.annotations,
    "ui.canvas/app-name": name,
    "ui.canvas/dds-profile": draft.profile,
    "ui.canvas/discovery-ip": draft.discoveryIp,
    "ui.canvas/edges": JSON.stringify(draft.edges),
  };
  return manifests
    .map((manifest) => yaml.dump(manifest, { lineWidth: -1, noRefs: true }))
    .join("\n---\n");
}

export function importDeployment(content: string): DeploymentDraft {
  if (new TextEncoder().encode(content).length > 1_000_000)
    throw new Error("YAML 파일은 1MB 이하여야 합니다.");
  const docs = yaml.loadAll(content) as Array<{
    kind?: string;
    metadata?: { name?: string; annotations?: Record<string, string> };
    spec?: {
      replicas?: number;
      template?: {
        spec?: {
          nodeSelector?: Record<string, string>;
          containers?: {
            args?: unknown[];
            image?: string;
            command?: string[];
          }[];
        };
      };
    };
  } | null>;
  const saved =
    docs.find((doc) => doc?.metadata?.annotations?.["ui.canvas/app-name"])
      ?.metadata?.annotations || {};
  const nodes: DeploymentNode[] = [];
  for (const doc of docs) {
    if (doc?.kind !== "Deployment") continue;
    const meta = doc.metadata || {},
      annotation = meta.annotations || {},
      spec = doc.spec || {},
      pod = spec.template?.spec || {},
      container = pod.containers?.[0] || {};
    const args = Array.isArray(container.args) ? container.args : [],
      script =
        args.find(
          (arg: unknown): arg is string =>
            typeof arg === "string" && arg.endsWith(".py"),
        ) || "";
    const inferred = meta.name?.includes("discovery")
      ? "discovery"
      : Object.keys(templates).find((key) => script === key + ".py") || "app";
    const template = annotation["ui.canvas/template"] || inferred;
    const node = deploymentNode(
      template,
      annotation["ui.canvas/id"] || `import-${nodes.length}`,
      Number(annotation["ui.canvas/x"] ?? 60),
      Number(annotation["ui.canvas/y"] ?? 60 + nodes.length * 220),
    );
    const argument = (flag: string) => {
      const index = args.indexOf(flag);
      return index < 0 ? "" : String(args[index + 1] || "");
    };
    Object.assign(node, {
      label: annotation["ui.canvas/label"] || templates[template] || meta.name,
      deployment: meta.name,
      image: container.image,
      replicas: spec.replicas ?? 1,
      port: Number(
        annotation["ui.canvas/port"] ||
          /-p\s+(\d+)/.exec((container.command || []).join(" "))?.[1] ||
          11811,
      ),
      script,
      device: argument("--camera_id") || argument("--sensor_id") || "dev01",
      pipeline:
        annotation["ui.canvas/pipeline"] ||
        argument("--topic").replace(/_video_shared$/, "") ||
        "pipe01",
      rtsp: argument("--rtsp"),
      selector: pod.nodeSelector || {},
    });
    if (
      ![node.x, node.y].every(
        (value) => Number.isFinite(value) && value >= 0,
      ) ||
      typeof node.image !== "string" ||
      !node.image
    )
      throw new Error("노드 좌표와 이미지를 확인하세요.");
    if (Object.values(node.selector).some((value) => typeof value !== "string"))
      throw new Error("노드 선택자는 문자열 값이어야 합니다.");
    nodes.push(node);
  }
  if (!nodes.length || nodes.length > 128)
    throw new Error("Deployment가 1~128개인 YAML을 입력하세요.");
  if (new Set(nodes.map((n) => n.id)).size !== nodes.length)
    throw new Error("중복된 노드 ID입니다.");
  const edges = JSON.parse(saved["ui.canvas/edges"] || "[]");
  if (!Array.isArray(edges) || edges.length > 512)
    throw new Error("잘못된 연결 정보입니다.");
  return {
    name:
      saved["ui.canvas/app-name"] || String(nodes[0].deployment).split("-")[0],
    profile: saved["ui.canvas/dds-profile"] || "dds_discovery",
    discoveryIp: saved["ui.canvas/discovery-ip"] || "",
    nodes,
    edges: edges
      .filter(
        (edge) =>
          edge &&
          nodes.some((n) => n.id === edge.source) &&
          nodes.some((n) => n.id === edge.target),
      )
      .map((edge) => ({
        id: String(edge.id),
        source: edge.source,
        target: edge.target,
        sourcePort: "DDS",
        targetPort: "DDS",
      })),
  };
}
