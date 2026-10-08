import type { components } from "./api-schema";
import { parse } from "lossless-json";

type Profile = components["schemas"]["ProfileVersion"];

export async function readProfilePage(response: Response): Promise<{ items: Profile[]; nextOffset: number | null }> {
  const page = parse(await response.text()) as { items: Profile[]; nextOffset: number | null };
  return { items: page.items, nextOffset: page.nextOffset === null ? null : Number(page.nextOffset) };
}

export function nextProfileVersion(profile: Profile, known: Profile[]): string {
  const [major, minor, patch] = profile.version.split(".");
  let nextPatch = BigInt(patch) + BigInt(1);
  const versions = known.filter(p => p.kind === profile.kind && p.key === profile.key);
  while (versions.some(p => p.version === `${major}.${minor}.${nextPatch}`)) nextPatch++;
  const version = `${major}.${minor}.${nextPatch}`;
  return version.length <= 32 ? version : "";
}
