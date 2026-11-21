#!/usr/bin/env bash
# Assemble the Android runtime reference that sync-runtime-from-references.sh
# expects, using only publicly downloadable inputs (no game files):
#   official.apk  - ModinMobileSTS/Sts2MobileLauncher release importer APK (FMOD 2.03.06 libs)
#   ekyso.apk     - Ekyso/StS2-Launcher 0.2.0 APK (custom Godot/Mono natives, original BCL)
#   templates.tpz - Godot 4.5.1-stable mono export templates (godot-lib AARs)
#   fmod-addons.zip - utopia-rise/fmod-gdextension addons.zip (FMOD Godot plugin AAR)
#   crypto.jar    - godotengine/godot 4.5.1-stable mono crypto Java wrapper
# Mirrors iunius612/StS2-Launcher_Mod_Manager scripts/setup-deps.sh.
# Usage: prepare-public-runtime-reference.sh <download-dir> <output-dir>
set -euo pipefail
DL="$(cd "$1" && pwd -P)"
OUT="$2"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

rm -rf "$OUT"
mkdir -p "$OUT/android/assets/dotnet_bcl" "$OUT/android/gradle/wrapper" "$OUT/fmod/arm64"
OUT="$(cd "$OUT" && pwd -P)"
REF="$OUT/android"

unzip -q "$DL/ekyso.apk" 'lib/arm64-v8a/*' 'assets/dotnet_bcl/*' -d "$WORK/ekyso"
unzip -q "$DL/official.apk" 'lib/arm64-v8a/*' 'assets/dotnet_bcl/*.pdb' -d "$WORK/official"
unzip -q "$DL/templates.tpz" 'templates/android_source.zip' -d "$WORK/tpz"
unzip -q "$WORK/tpz/templates/android_source.zip" 'libs/*' -d "$WORK/android_source"

# BCL: Ekyso original (MonoMod.Utils.dll must be the unpatched original) + pdbs upstream ships.
cp -f "$WORK/ekyso/assets/dotnet_bcl/"* "$REF/assets/dotnet_bcl/"
cp -f "$WORK/official/assets/dotnet_bcl/"*.pdb "$REF/assets/dotnet_bcl/"

for variant in debug release; do
  dst="$REF/libs/$variant"
  mkdir -p "$dst/arm64-v8a"
  for so in "$WORK/ekyso/lib/arm64-v8a/"*.so; do
    case "$(basename "$so")" in
      libgodot_android.so|libc++_shared.so) continue ;;
    esac
    cp -f "$so" "$dst/arm64-v8a/"
  done
  aar="$WORK/android_source/libs/$variant/godot-lib.template_$variant.aar"
  [[ -f "$aar" ]] || { echo "Missing $aar" >&2; find "$WORK/android_source" -name '*.aar' >&2; exit 1; }
  cp -f "$aar" "$dst/"
  # Swap in Ekyso's custom libgodot_android.so (Mono-enabled build used by the port).
  python3 - "$dst/godot-lib.template_$variant.aar" "$WORK/ekyso/lib/arm64-v8a/libgodot_android.so" <<'PY'
import os, sys, zipfile
aar, so = sys.argv[1:]
entry = "jni/arm64-v8a/libgodot_android.so"
tmp = aar + ".new"
with zipfile.ZipFile(aar) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
    names = src.namelist()
    if entry not in names:
        raise SystemExit(f"{entry} not in {aar}")
    for item in src.infolist():
        if item.filename != entry:
            dst.writestr(item, src.read(item.filename))
    dst.write(so, entry)
os.replace(tmp, aar)
print("patched", os.path.basename(aar))
PY
done

# FMOD Godot plugin AAR (contains libs/fmod.jar, patched later with the repo shim).
mkdir -p "$WORK/fmod"
unzip -q "$DL/fmod-addons.zip" -d "$WORK/fmod"
echo "AARs in fmod addons:"; find "$WORK/fmod" -name '*.aar' -print
fmod_aar="$(find "$WORK/fmod" -name '*.aar' -path '*android*' | grep -i release | head -n1 || true)"
[[ -n "$fmod_aar" ]] || { echo "No release FMOD plugin AAR found" >&2; exit 1; }
python3 -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); assert 'libs/fmod.jar' in z.namelist(), z.namelist()" "$fmod_aar"
cp -f "$fmod_aar" "$OUT/fmod/fmod-release.aar"
cp -f "$fmod_aar" "$REF/libs/debug/fmod-release.aar"
cp -f "$fmod_aar" "$REF/libs/release/fmod-release.aar"

# FMOD 2.03.06 engine + bridge exactly as shipped by upstream.
for name in libfmod.so libfmodstudio.so libGodotFmod.android.template_release.arm64.so; do
  cp -f "$WORK/official/lib/arm64-v8a/$name" "$OUT/fmod/arm64/$name"
done

cp -f "$DL/crypto.jar" "$OUT/libSystem.Security.Cryptography.Native.Android.jar"
cp -f "$ROOT/android/gradle/wrapper/gradle-wrapper.jar" "$REF/gradle/wrapper/gradle-wrapper.jar"

echo "Runtime reference ready at $OUT"
find "$OUT" -type f \( -name '*.so' -o -name '*.aar' -o -name '*.jar' \) -exec sha256sum {} + | sed "s#$OUT/##" | sort -k2
