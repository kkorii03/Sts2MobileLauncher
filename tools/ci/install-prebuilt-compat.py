#!/usr/bin/env python3
"""Install upstream-built compat artifacts from an official importer APK.

Used by CI, where no original game DLLs exist to compile port-mod. Copies the
bundled compat pack zips, the fallback STS2Mobile.dll and port_compat.pck into
android/assets/. If the full compat pack lacks the v0.111.1 stable/public
target, it is added as a metadata-only copy of v0.111.0, mirroring upstream
port-mod targets/active/v0.111.1/target.json (same ReferenceFlavor and
compile constants; only versions/channel/branch/SHA differ).
"""
import copy
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

V01111 = {
    "target_id": "v0.111.1",
    "display_name": "STS2 v0.111.1",
    "version": "v0.111.1",
    "versions": ["v0.111.1"],
    "channel": "stable",
    "source": "original_pc_reference_v0.111.1",
    "steam_branch": "public",
    "sts2_dll_sha256": "3fb1fa1bf0249087b706a5d0f062a60579e2f5b08df4ee477ddd6f2607f654d5",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def add_v01111(raw: bytes) -> bytes:
    src = zipfile.ZipFile(io.BytesIO(raw))
    names = src.namelist()
    manifest_name = next(n for n in names if n.endswith("compat_manifest.json"))
    prefix = manifest_name[: -len("compat_manifest.json")]
    manifest = json.loads(src.read(manifest_name))
    ids = [t["target_id"] for t in manifest["targets"]]
    if "v0.111.1" in ids:
        print("compat pack already has v0.111.1; unchanged")
        return raw
    base = next(t for t in manifest["targets"] if t["target_id"] == "v0.111.0")
    target = copy.deepcopy(base)
    target.update(V01111)
    target["artifacts"] = {
        "dll": "variants/v0.111.1/STS2Mobile.dll",
        "overlay_pck": "variants/v0.111.1/port_compat.pck",
    }
    target["notes"] = ["Fork CI: metadata-only v0.111.1 target reusing the v0.111.0 build, as upstream does."]
    manifest["targets"].append(target)

    files = {n: src.read(n) for n in names if not n.endswith("/")}
    for kind in ("dll", "overlay_pck"):
        files[prefix + target["artifacts"][kind]] = files[prefix + base["artifacts"][kind]]
    files[manifest_name] = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
    sums_name = prefix + "SHA256SUMS"
    if sums_name in files:
        lines = [
            f"{sha256(data)}  {name[len(prefix):]}"
            for name, data in sorted(files.items())
            if name != sums_name
        ]
        files[sums_name] = ("\n".join(lines) + "\n").encode()

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for name in sorted(files):
            dst.writestr(name, files[name])
    print(f"added v0.111.1 target; targets now: {ids + ['v0.111.1']}")
    return out.getvalue()


def main() -> None:
    apk_path, android_dir = Path(sys.argv[1]), Path(sys.argv[2])
    apk = zipfile.ZipFile(apk_path)
    packs = android_dir / "assets" / "compat_packs"
    packs.mkdir(parents=True, exist_ok=True)
    for old in packs.glob("*.zip"):
        old.unlink()
    for name in apk.namelist():
        if name.startswith("assets/compat_packs/") and name.endswith(".zip"):
            data = apk.read(name)
            if name.endswith("/sts2-android-compat.zip"):
                data = add_v01111(data)
            (packs / Path(name).name).write_bytes(data)
            print(f"{sha256(data)}  {Path(name).name}")
    for name, dest in (
        ("assets/dotnet_bcl/STS2Mobile.dll", android_dir / "assets/dotnet_bcl/STS2Mobile.dll"),
        ("assets/port_compat.pck", android_dir / "assets/port_compat.pck"),
    ):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(apk.read(name))
        print(f"{sha256(dest.read_bytes())}  {dest.name}")


if __name__ == "__main__":
    main()
