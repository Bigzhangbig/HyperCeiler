"""Inspect the exact published dependencies required by the A14 compatibility build."""
import base64
from io import BytesIO
import os
from pathlib import Path
import subprocess
import tomllib
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree
from zipfile import ZipFile

authorization = "Basic " + base64.b64encode(
    f"{os.environ['GIT_ACTOR']}:{os.environ['GIT_TOKEN']}".encode()).decode()
base = "https://maven.pkg.github.com/ReChronoRain/HyperCeiler/"
out = Path("tests/preferences/build/upstream")
out.mkdir(parents=True, exist_ok=True)


def fetch(path):
    try:
        with urlopen(Request(base + path, headers={"Authorization": authorization}), timeout=30) as response:
            return response.read()
    except HTTPError as error:
        print(f"Dependency lookup {path}: HTTP {error.code}")
        return None


metadata = fetch("fan/miuix/haptic/maven-metadata.xml")
if metadata:
    print("Published haptic versions:", [v.text for v in ElementTree.fromstring(metadata).findall("./versioning/versions/version")])
for name, path in {
    "core": "fan/miuix/core/1.0.10.1/core-1.0.10.1.aar",
    "appcompat": "fan/miuix/appcompat/1.0.10.1/appcompat-1.0.10.1.aar",
    "expansion": "com/sevtinge/hyperceiler/hyperceiler-expansion/1.0.0/hyperceiler-expansion-1.0.0.aar",
}.items():
    data = fetch(path)
    if data is None:
        raise SystemExit(f"Cannot inspect required artifact: {name}")
    with ZipFile(BytesIO(data)) as aar:
        classes = aar.read("classes.jar")
    jar = out / f"{name}.jar"
    jar.write_bytes(classes)
    with ZipFile(BytesIO(classes)) as archive:
        print(f"Haptic classes provided by {name}:", [n for n in archive.namelist() if "haptic" in n.lower()])
        if name == "core":
            required = {"fan/view/HapticCompat.class", "fan/view/HapticFeedbackConstants.class",
                        "fan/utils/HapticFeedbackCompat.class"}
            assert required <= set(archive.namelist()), "Pinned MIUIX core must provide existing haptic support"
result = subprocess.run(["javap", "-classpath", str(out / "expansion.jar"), "-p", "-c",
                         "com.sevtinge.hyperceiler.expansion.utils.ShellSafeUtils"],
                        text=True, capture_output=True, check=True)
print("Published expansion shell validator bytecode:")
print(result.stdout)
with open("gradle/libs.versions.toml", "rb") as catalog:
    paranoid_version = tomllib.load(catalog)["versions"]["lsparanoid"]
with urlopen(f"https://repo.maven.apache.org/maven2/org/lsposed/lsparanoid/core/{paranoid_version}/core-{paranoid_version}.jar", timeout=30) as response:
    (out / "lsparanoid-core.jar").write_bytes(response.read())
