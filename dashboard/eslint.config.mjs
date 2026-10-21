// Native console lint: the pinned Kestra UI's Vue/TypeScript parsers and its semantic SFC rules (not its formatting).
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";

const upstream = createRequire(new URL("../output/kestra-native/upstream/ui/package.json", import.meta.url));
const load = async (name) => (await import(pathToFileURL(upstream.resolve(name)).href)).default;
const pluginVue = await load("eslint-plugin-vue");
const tsParser = await load("@typescript-eslint/parser");

export default [
  ...pluginVue.configs["flat/base"],
  { files: ["**/*.{ts,mjs}"], languageOptions: { parser: tsParser } },
  {
    files: ["**/*.vue"],
    languageOptions: { parserOptions: { parser: tsParser, extraFileExtensions: [".vue"] } },
    rules: {
      "vue/block-lang": ["error", { script: { lang: "ts" } }],
      "vue/component-api-style": ["error", ["script-setup"]],
      "vue/this-in-template": "error",
      "vue/block-order": ["error", { order: ["template", "script", "style"] }],
      "vue/attribute-hyphenation": ["error", "never"],
    },
  },
];
