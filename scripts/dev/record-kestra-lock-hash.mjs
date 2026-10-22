#!/usr/bin/env node
// Run only right after a successful `npm ci` in the pinned Kestra ui/ directory.
// Kestra v2.0.6 ui/scripts/ensure-sdk.mjs writes node_modules/.ensure-sdk-lock-hash only after its own
// `npm install`, so predev would reinstall a tree `npm ci` just installed exactly from package-lock.json.
// This writes the same digest that hashInputs(["package-lock.json"]) computes there (relative path + contents);
// predev still compares it with the current lockfile and reinstalls on any mismatch.
import {createHash} from "node:crypto"
import {existsSync, readFileSync, writeFileSync} from "node:fs"
import path from "node:path"

const uiRoot = path.resolve(process.argv[2] ?? ".")
const lockFile = path.join(uiRoot, "package-lock.json")
const nodeModules = path.join(uiRoot, "node_modules")
if (!existsSync(lockFile) || !existsSync(nodeModules)) {
    console.error(`[kestra-lock-hash] package-lock.json or node_modules is missing in ${uiRoot}`)
    process.exit(1)
}
const digest = createHash("sha256").update("package-lock.json").update(readFileSync(lockFile)).digest("hex")
writeFileSync(path.join(nodeModules, ".ensure-sdk-lock-hash"), digest)
