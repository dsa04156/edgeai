// Preview only: the original Kestra application remains the frontend entry point.
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { defineConfig, mergeConfig } from "vite";
import { createOverviewMiddleware } from "./overview.ts";
import { createDeviceRegistrationMiddleware } from "./device-registration.ts";
import upstreamConfig from "../../output/kestra-native/upstream/ui/vite.config.js";

const nativeRoot = path.dirname(fileURLToPath(import.meta.url));
const sourceRoot = fileURLToPath(
  new URL("../../output/kestra-native/upstream/ui", import.meta.url),
);
const overviewFile = path.join(nativeRoot, "OperationsDashboard.vue");
const config = {
  version: "2.0.6",
  uuid: "edgeai-native-preview",
  isBasicAuthInitialized: true,
  isAnonymousUsageEnabled: false,
  isUiAnonymousUsageEnabled: false,
  isCustomDashboardsEnabled: false,
  isTemplateEnabled: false,
  isAiEnabled: false,
  isTaskRunEnabled: false,
  environment: { name: "EdgeAI · 운영 콘솔", color: "#631bf3" },
};

export default defineConfig(async (env) => {
  const originalDefinitions = Object.fromEntries(
    ["flow", "namespace"].map((name) => [
      name,
      execFileSync(
        "git",
        [
          "show",
          `HEAD:core/src/main/resources/dashboards/default_${name}_definition.yaml`,
        ],
        { cwd: sourceRoot, encoding: "utf8" },
      ),
    ]),
  );
  return mergeConfig(upstreamConfig(env), {
    root: sourceRoot,
    resolve: {
      alias: [
        { find: "kestra-original", replacement: path.join(sourceRoot, "src") },
        {
          find: /^vue-router$/,
          replacement: path.join(
            sourceRoot,
            "node_modules/vue-router/dist/vue-router.js",
          ),
        },
        {
          find: /^@kestra-io\/design-system$/,
          replacement: path.join(
            sourceRoot,
            "packages/design-system/src/index.ts",
          ),
        },
        {
          find: "@kestra-io/topology/flow-yaml-utils",
          replacement: path.join(
            sourceRoot,
            "packages/topology/src/utils/flowYamlUtils.ts",
          ),
        },
      ],
    },
    server: {
      host: "0.0.0.0",
      port: 13083,
      strictPort: true,
      fs: { allow: [sourceRoot, path.dirname(nativeRoot)] },
    },
    plugins: [
      {
        name: "edgeai-native-overview",
        enforce: "pre",
        transform(code, id) {
          if (id.endsWith("/src/main.ts"))
            return (
              code +
              `\nimport ${JSON.stringify(path.join(nativeRoot, "console.scss"))};\n`
            );
          if (id.endsWith("/src/override/components/LeftMenu.vue"))
            return code
              .replace(/<ProductTourItem[^>]*\/>/, "<NativeShellFooter />")
              .replace(/<AdminItem[^>]*\/>/, "")
              .replace(/<Auth[^>]*\/>/, "")
              .replace(
                '<script setup lang="ts">',
                `<script setup lang="ts">\nimport NativeShellFooter from ${JSON.stringify(path.join(nativeRoot, "ShellFooter.vue"))};`,
              );
          if (id.endsWith("/src/override/components/layout/DefaultLayout.vue"))
            return code
              .replace(/<ContextDrawer[^>]*\/>/, "")
              .replace(/<SurveyDialog[\s\S]*?\/>/, "");
          if (id.endsWith("/src/stores/api.ts"))
            return code.replaceAll("https://api.kestra.io", "/preview-meta");
          if (id.endsWith("/src/routes/routes.ts"))
            return code
              .replace(
                'import("../components/dashboard/Dashboard.vue")',
                `import(${JSON.stringify(overviewFile)})`,
              )
              .replace(
                "//Flows",
                [
                  ["devices", "DeviceInventory.vue"],
                  ["data-store", "DataStorePage.vue"],
                  ["profiles", "ProfilesPage.vue"],
                  ["virtual-devices", "VirtualDevicesPage.vue"],
                  ["workflows", "WorkflowsPage.vue"],
                  ["runs", "RunsPage.vue"],
                  ["audit", "AuditPage.vue"],
                  ["platform-service", "PlatformServicePage.vue"],
                ]
                  .map(
                    ([route, component]) =>
                      `{name: "edgeai/${route}", path: "/:tenant?/${route}", component: () => import(${JSON.stringify(path.join(nativeRoot, component))})}`,
                  )
                  .join(",\n") + ",\n    //Flows",
              );
          if (id.endsWith("/src/override/components/useLeftMenu.ts")) {
            const entries = [
              ["home", "운영 현황", "ChartLineVariant"],
              ["devices", "장치 관리", "Monitor"],
              ["virtual-devices", "가상 장치", "LayersTripleOutline"],
              ["data-store", "데이터 저장소", "LayersTripleOutline"],
              ["workflows", "워크플로", "FileTreeOutline"],
              ["runs", "실행 · 작업 · 결과", "PlayOutline"],
              ["profiles", "실행 모듈", "PuzzleOutline"],
              ["audit", "감사 기록", "ShieldCheckOutline"],
              ["platform-service", "DDS 배포", "RocketLaunchOutline"],
            ];
            const items = entries
              .map(
                ([id, title, icon]) =>
                  `{id: "edgeai-${id}", title: ${JSON.stringify(title)}, routes: routeStartWith(${JSON.stringify(id === "home" ? id : "edgeai/" + id)}), href: {name: ${JSON.stringify(id === "home" ? id : "edgeai/" + id)}}, icon: {element: ${icon}}}`,
              )
              .join(",\n");
            return code.replace(
              /const generated = \[[\s\S]*?\n        \]\n\n        flatten/,
              `const generated = [{id: "workspace", title: "EdgeAI", child: [${items}]}]\n\n        flatten`,
            );
          }
          if (id.endsWith("/src/components/layout/AppTopNavBar.vue"))
            return code
              .replace(':showDockToggle="true"', ':showDockToggle="false"')
              .replace(/<GlobalSearch[^>]*\/>/, "");
          if (id.endsWith("/src/components/dashboard/components/Header.vue"))
            return code.replaceAll(
              'v-if="isAllowedFlow"',
              'v-if="isAllowedFlow && !props.dashboard?.edgeaiReadOnly"',
            );
          if (id.endsWith("/src/components/dashboard/sections/Sections.vue"))
            return code.replace(
              'v-if="isExportableChart(chart.type)"',
              "v-if=\"isExportableChart(chart.type) && !String(chart.data?.type).startsWith('edgeai.')\"",
            );
          if (id.endsWith("/src/components/dashboard/sections/Table.vue"))
            return code
              .replace(
                "clickable: true,",
                'clickable: !props.chart.data?.type?.startsWith("edgeai."),',
              )
              .replace(
                "onClick: () => navigateToStateFilter(row[key].toString()),",
                'onClick: props.chart.data?.type?.startsWith("edgeai.") ? undefined : () => navigateToStateFilter(row[key].toString()),',
              )
              .replace(
                ':minWidth="ENTITY_LINK_FIELDS.includes(value.field) ? 140 : undefined"',
                ':minWidth="value.minWidth ?? (ENTITY_LINK_FIELDS.includes(value.field) ? 140 : undefined)"',
              );
        },
        configureServer(server) {
          server.middlewares.use(
            createDeviceRegistrationMiddleware({
              baseUrl: "http://127.0.0.1:13080",
              managementUrl:
                process.env.EDGEAI_API_BASE_URL ?? "http://127.0.0.1:18080",
              workflowUrl:
                process.env.EDGEAI_WORKFLOW_API_BASE_URL ??
                "http://127.0.0.1:18082",
            }),
          );
          server.middlewares.use(
            createOverviewMiddleware({ baseUrl: "http://127.0.0.1:13080" }),
          );
          server.middlewares.use((request, response, next) => {
            // This cookie only unlocks the isolated original frontend, never the EdgeAI backend.
            response.setHeader(
              "Set-Cookie",
              "kestraBasicAuthenticated=true; Path=/; SameSite=Lax",
            );
            const url = new URL(request.url, "http://preview.local");
            const pathname = url.pathname;
            if (
              !pathname.startsWith("/api/") &&
              !pathname.startsWith("/preview-meta/") &&
              !pathname.startsWith("/swagger/")
            )
              return next();
            let body;
            if (request.method !== "GET") {
              response.statusCode = 405;
              body = { message: "현재 단계는 조회만 지원합니다." };
            } else if (pathname.startsWith("/preview-meta/"))
              body = pathname.endsWith("/feeds")
                ? { feeds: [], version: "2.0.6" }
                : { content: "", metadata: {}, results: [] };
            else if (pathname === "/swagger/kestra.yml")
              body = {
                openapi: "3.0.3",
                info: { title: "EdgeAI", version: "1" },
                paths: {},
              };
            else if (pathname.endsWith("/configs/login"))
              body = { isBasicAuthInitialized: true };
            else if (pathname.endsWith("/configs")) body = config;
            else if (pathname.endsWith("/dashboards/defaults/definitions"))
              body = {
                ...originalDefinitions,
                main: readFileSync(
                  path.join(nativeRoot, "overview.yaml"),
                  "utf8",
                ),
              };
            else if (
              pathname.includes("/plugins") ||
              pathname.endsWith("/namespaces") ||
              pathname.endsWith("/usages/all")
            )
              body = [];
            else if (pathname.endsWith("/flows/search"))
              body = { results: [], total: 0 };
            else {
              response.statusCode = 501;
              body = { message: "지원하지 않는 API입니다." };
            }
            response.setHeader(
              "Content-Type",
              "application/json; charset=utf-8",
            );
            response.setHeader("Cache-Control", "no-store");
            response.end(JSON.stringify(body));
          });
        },
      },
    ],
  });
});
