#!/usr/bin/env python3
"""Cut the scene clips listed in scenes.json from the original YouTube files.

Run from anywhere: `prepare-scenes.py` renders every scene into output/hawkeye-scenes/ and checks the
result against the digests in scenes.json; `prepare-scenes.py --record` writes the new frame counts and
digests into scenes.json instead (after a deliberate change of a scene's recipe).
"""
import hashlib
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CATALOG = HERE / "scenes.json"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def video_filter(scene, output):
    # Frame ranges are half-open source frame numbers; delogo works in source pixels before scaling.
    parts = [f"between(n,{start},{end - 1})" for start, end in scene["frameRanges"]]
    chain = [f"select='{'+'.join(parts)}'", "setpts=N/FRAME_RATE/TB"]
    chain += [f"delogo=x={box['x']}:y={box['y']}:w={box['w']}:h={box['h']}" for box in scene["preprocess"]["delogo"]]
    chain += [f"fps={output['fps']}", f"scale={output['width']}:{output['height']}"]
    return ",".join(chain)


def count_frames(path):
    result = subprocess.run(
        ["rtk", "proxy", "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
         "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(path)],
        check=True, capture_output=True, text=True)
    return int(result.stdout.strip())


def main():
    record = sys.argv[1:] == ["--record"]
    if sys.argv[1:] not in ([], ["--record"]):
        sys.exit("usage: prepare-scenes.py [--record]")
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    output = catalog["output"]
    out_dir = ROOT / output["directory"]
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, source in catalog["sources"].items():
        if sha256(ROOT / source["file"]) != source["sha256"]:
            sys.exit(f"source {name} does not match its SHA-256: {source['file']}")
    mismatches = []
    for scene in catalog["scenes"]:
        source = catalog["sources"][scene["source"]]
        target = out_dir / scene["file"]
        subprocess.run(
            ["rtk", "proxy", "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(ROOT / source["file"]),
             "-map", "0:v:0", "-an", "-vf", video_filter(scene, output),
             "-c:v", "libx264", "-profile:v", "baseline", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
             "-threads", "4", "-map_metadata", "-1", "-movflags", "+faststart", str(target)],
            check=True)
        frames, digest = count_frames(target), sha256(target)
        print(f"{scene['id']}: {frames} frames, {digest}")
        if record:
            scene["frames"], scene["sha256"] = frames, digest
            scene["durationSeconds"] = round(frames / output["fps"], 6)
        elif (frames, digest) != (scene["frames"], scene["sha256"]):
            mismatches.append(scene["id"])
    if record:
        CATALOG.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif mismatches:
        sys.exit(f"rendered clips differ from scenes.json: {', '.join(mismatches)}")


if __name__ == "__main__":
    main()
