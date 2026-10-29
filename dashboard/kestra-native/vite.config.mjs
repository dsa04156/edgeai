// The original Kestra application remains the frontend entry point (dev: 13083 Vite, build: server.mjs).
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { createOverviewMiddleware } from "./overview.ts";
import { createDeviceRegistrationMiddleware } from "./device-registration.ts";
import { apiOrigins, createShellApiMiddleware } from "./shell-api.ts";
import upstreamConfig from "../../output/kestra-native/upstream/ui/vite.config.js";

const nativeRoot = path.dirname(fileURLToPath(import.meta.url));
const sourceRoot = fileURLToPath(
  new URL("../../output/kestra-native/upstream/ui", import.meta.url),
);
// Use the pinned upstream Vite, not whichever copy happens to sit in dashboard/node_modules.
const { defineConfig, mergeConfig } = await import(
  new URL(
    "../../output/kestra-native/upstream/ui/node_modules/vite/dist/node/index.js",
    import.meta.url,
  ).href
);
const overviewFile = path.join(nativeRoot, "OperationsDashboard.vue");
const defaultOutDir = fileURLToPath(
  new URL("../../output/kestra-native/dist", import.meta.url),
);

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
  const definitions = () => ({
    ...originalDefinitions,
    main: readFileSync(path.join(nativeRoot, "overview.yaml"), "utf8"),
  });
  const build = env.command === "build";
  return mergeConfig(upstreamConfig(env), {
    root: sourceRoot,
    // Production serves the app under /ui/ (server.mjs); dev keeps upstream's base.
    base: build ? "/ui/" : undefined,
    build: {
      outDir: process.env.EDGEAI_KESTRA_OUT_DIR ?? defaultOutDir,
      emptyOutDir: true,
    },
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
      // hawkeye-video.ts imports the scene catalog from the replay image's sources.
      fs: { allow: [sourceRoot, path.dirname(nativeRoot), path.join(nativeRoot, "../../hawkeye-runtime/video-replay")] },
    },
    plugins: [
      {
        name: "edgeai-native-overview",
        enforce: "pre",
        transformIndexHtml(html) {
          // Kestra's webserver StaticFilter normally injects the UI path; server.mjs serves /ui/.
          return build
            ? html.replace(
                'window.KESTRA_UI_PATH = "./";',
                'window.KESTRA_UI_PATH = "/ui/";',
              )
            : html;
        },
        generateBundle() {
          if (!build) return;
          // The release has no upstream Git checkout, so dashboard definitions ship with the bundle.
          this.emitFile({
            type: "asset",
            fileName: "edgeai-shell.json",
            source: JSON.stringify({ definitions: definitions() }),
          });
        },
        transform(code, id) {
          if (id.endsWith("/src/main.ts"))
            return (
              // Federation/PWA packaging is off, so no sw.js exists to register.
              code.replace("void registerServiceWorker()", "") +
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
                  ["scheduling-profiles", "SchedulingProfilesPage.vue"],
                  ["resource-profiles", "ResourceProfilesPage.vue"],
                  ["inference", "InferencePage.vue"],
                  ["scenarios", "ScenarioPage.vue"],
                  ["hawkeye", "HawkeyePage.vue", "scenarios/hawkeye"],
                  ["audit", "AuditPage.vue"],
                  ["platform-service", "PlatformServicePage.vue"],
                ]
                  .map(
                    ([route, component, routePath = route]) =>
                      `{name: "edgeai/${route}", path: "/:tenant?/${routePath}", component: () => import(${JSON.stringify(path.join(nativeRoot, component))})}`,
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
              ["scheduling-profiles", "성능 프로파일", "SpeedometerMedium"],
              ["resource-profiles", "자원 프로파일", "ChipIcon"],
              ["inference", "모델 추론", "ImageSearchOutline"],
              // Before "scenarios": the top bar picks the first item whose path prefixes /scenarios/hawkeye.
              ["hawkeye", "창고 지게차 안전 점검", "ForkliftIcon"],
              ["scenarios", "GPU/NPU 이미지 비교", "CompareHorizontal"],
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
            return code
              .replace(
                'import {computed} from "vue"',
                'import {computed} from "vue"\nimport ImageSearchOutline from "vue-material-design-icons/ImageSearchOutline.vue"\nimport CompareHorizontal from "vue-material-design-icons/CompareHorizontal.vue"\nimport ForkliftIcon from "vue-material-design-icons/Forklift.vue"\nimport SpeedometerMedium from "vue-material-design-icons/SpeedometerMedium.vue"\nimport ChipIcon from "vue-material-design-icons/Chip.vue"',
              )
              .replace(
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
          const origins = apiOrigins(process.env);
          server.middlewares.use(createDeviceRegistrationMiddleware(origins));
          server.middlewares.use(createOverviewMiddleware(origins));
          server.middlewares.use(createShellApiMiddleware({ definitions }));
        },
      },
    ],
  });
});
