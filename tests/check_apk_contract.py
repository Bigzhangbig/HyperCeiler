"""Inspect actual build outputs; a source-only check cannot prove APK packaging."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from dex_compatibility import reject_legacy_dex

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
AAPT = SDK / "build-tools/36.0.0/aapt2"
DEXDUMP = SDK / "build-tools/36.0.0/dexdump"
APKS = sorted((ROOT / "app/build/outputs/apk").glob("**/*.apk"))
assert APKS, "No APKs found"
array = ET.parse(ROOT / "library/hook/src/main/res/values/arrays.xml").getroot()
expected_scope = {"system" if item.text == "android" else item.text
                  for item in array.find("string-array[@name='xposed_scope']").findall("item")}
variants = set()
for apk in APKS:
    variants.add(apk.parent.name)
    with zipfile.ZipFile(apk) as archive:
        props = archive.read("META-INF/xposed/module.prop").decode()
        assert "minApiVersion=102" in props and "targetApiVersion=102" in props, apk
        assert archive.read("META-INF/xposed/java_init.list").decode().strip() == "com.sevtinge.hyperceiler.hook.ModernXposedInit", apk
        assert set(archive.read("META-INF/xposed/scope.list").decode().split()) == expected_scope, apk
        assert "assets/xposed_init" not in archive.namelist(), apk
        # A descriptor left in META-INF is insufficient if R8 removed or renamed its class.
        entry_found = False
        with tempfile.TemporaryDirectory(prefix="hyperceiler-apk-") as unpacked:
            for name in archive.namelist():
                if not re.fullmatch(r"classes\d*\.dex", name):
                    continue
                dex = Path(unpacked) / name
                data = archive.read(name)
                reject_legacy_dex(data, f"{apk.name}/{name}")
                dex.write_bytes(data)
                dump = subprocess.check_output([str(DEXDUMP), str(dex)])
                if re.search(rb"Class descriptor\s*:\s*'Lcom/sevtinge/hyperceiler/hook/ModernXposedInit;'", dump):
                    entry_found = True
        assert entry_found, f"Modern module entry class missing from DEX: {apk}"
    manifest = subprocess.check_output([str(AAPT), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], text=True)
    assert "io.github.libxposed.service.XposedProvider" in manifest, apk
    assert "com.sevtinge.hyperceiler.XposedService" in manifest, apk
    assert "xposedsharedprefs" not in manifest and "xposedminversion" not in manifest, apk
    print("Verified module packaging:", apk.name)
assert {"debug", "release"} <= variants, f"Expected debug and optimized release APKs, found {variants}"
