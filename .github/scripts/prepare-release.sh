#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=8a44414cd5b74b7d1c7a258ccf5a86a5f6e0294b
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/a3f86a50d382/MythicLib-dist-1.7.1.jar?ref=$ref" > "libs/MythicLib-dist-1.7.1.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/14850d745437/MMOCore-1.13.1.jar?ref=$ref" > "libs/MMOCore-1.13.1.jar"
sha256sum --check .github/dependencies.sha256
