"""Fail clearly before a full build if the required upstream packages cannot be read."""
import base64
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

actor, token = os.environ.get("GIT_ACTOR"), os.environ.get("GIT_TOKEN")
if not actor or not token:
    sys.exit("Set GIT_ACTOR and GIT_TOKEN with access to the required GitHub Packages.")
authorization = "Basic " + base64.b64encode(f"{actor}:{token}".encode()).decode()
packages = {
    "hyperceiler-expansion:1.0.0": "com/sevtinge/hyperceiler/hyperceiler-expansion/1.0.0/hyperceiler-expansion-1.0.0.pom",
    "fan.miuix:appcompat:1.0.10.1": "fan/miuix/appcompat/1.0.10.1/appcompat-1.0.10.1.pom",
}
failed = False
for name, path in packages.items():
    request = Request("https://maven.pkg.github.com/ReChronoRain/HyperCeiler/" + path,
                      headers={"Authorization": authorization}, method="HEAD")
    try:
        with urlopen(request, timeout=30) as response:
            print(f"{name}: HTTP {response.status}")
    except HTTPError as error:
        print(f"{name}: HTTP {error.code}; this credential cannot fetch a required package")
        failed = True
    except URLError:
        print(f"{name}: package server could not be reached")
        failed = True
sys.exit(1 if failed else 0)
