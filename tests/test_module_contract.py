"""Packaging/entry regression gates; runtime preference tests live in preferences/."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / "library/hook"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DESCRIPTORS = HOOK / "src/main/resources/META-INF/xposed"


class ModernModuleContract(unittest.TestCase):
    def test_no_legacy_module_or_nsp_registration(self):
        app = ET.parse(HOOK / "src/main/AndroidManifest.xml").getroot().find("application")
        keys = {item.get(ANDROID + "name") for item in app.findall("meta-data")}
        self.assertFalse(keys & {"xposedmodule", "xposedminversion", "xposedsharedprefs", "xposedscope"})

    def test_api101_entry_is_kept_and_packaged(self):
        props = dict(line.split("=", 1) for line in
                     (DESCRIPTORS / "module.prop").read_text().splitlines()
                     if line.strip() and not line.startswith("#"))
        self.assertEqual("101", props["minApiVersion"])
        # API101's documented legacy-hook compatibility is required by this A14 backend.
        self.assertEqual("101", props["targetApiVersion"])
        entry = (DESCRIPTORS / "java_init.list").read_text().strip()
        self.assertEqual("com.sevtinge.hyperceiler.hook.ModernXposedInit", entry)
        self.assertIn(entry, (HOOK / "proguard-rules.pro").read_text())
        self.assertNotIn('"/META-INF/**"', (ROOT / "app/build.gradle.kts").read_text())
        self.assertFalse((HOOK / "src/main/assets/xposed_init").exists())

    def test_scope_preserves_a14_targets_with_modern_system_name(self):
        root = ET.parse(HOOK / "src/main/res/values/arrays.xml").getroot()
        original = next(a for a in root.findall("string-array") if a.get("name") == "xposed_scope")
        expected = {"system" if i.text == "android" else i.text for i in original.findall("item")}
        actual = set((DESCRIPTORS / "scope.list").read_text().split())
        self.assertEqual(expected, actual)

    def test_no_deprecated_preference_transport_in_production(self):
        for path in (HOOK / "src/main/java").rglob("*"):
            if path.suffix not in {".java", ".kt"}:
                continue
            text = path.read_text()
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertFalse("de.robv.android.xposed.XSharedPreferences" in text, "Legacy preference transport remains")
                self.assertFalse("MODE_WORLD_READABLE" in text, "World-readable preferences remain")
                self.assertFalse("makeWorldReadable()" in text, "World-readable preference helper remains")
        self.assertFalse("fixPermissionsAsync" in (HOOK / "src/main/java/com/sevtinge/hyperceiler/hook/module/base/tool/AppsTool.java").read_text())

    def test_deoptimization_uses_supplied_modern_api(self):
        sources = HOOK / "src/main/java"
        for path in sources.rglob("*"):
            if path.suffix not in {".java", ".kt"}:
                continue
            with self.subTest(path=path.relative_to(ROOT)):
                # API101 migration supplies deoptimization through its modern entry.
                # Do not depend on a nonstandard legacy bridge reflection extension.
                self.assertFalse('"deoptimizeMethod"' in path.read_text(),
                                 "Nonstandard reflective legacy deoptimization dependency remains")
        for relative in (
            "module/hook/systemframework/FlagSecure.java",
            "module/hook/systemframework/UseAndroidPackageInstaller.java",
            "utils/api/OldFunApis.kt",
        ):
            path = sources / "com/sevtinge/hyperceiler/hook" / relative
            with self.subTest(delegate=relative):
                self.assertTrue("PrefsUtils.deoptimizeMethod(" in path.read_text(),
                                "Hook must delegate deoptimization to the supplied modern API")


if __name__ == "__main__":
    unittest.main()
