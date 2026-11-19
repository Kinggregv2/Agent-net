#!/usr/bin/env bash
# Builds agentnet.jar against the Minecraft 26.3 + Fabric files Modrinth already downloaded.
set -euo pipefail
cd "$(dirname "$0")"
MR="$APPDATA/ModrinthApp"
VERSION=0.11.0
# Compile against the Fabric API the AI instance actually runs.
FAPI="$(ls "$MR/profiles/AI/mods/"fabric-api-*.jar | head -1)"

rm -rf build && mkdir -p build/classes build/fapi
unzip -q -o -j "$FAPI" 'META-INF/jars/fabric-api-base*' 'META-INF/jars/fabric-command-api-v2*' \
  'META-INF/jars/fabric-lifecycle-events-v1*' 'META-INF/jars/fabric-rendering-v1*' \
  'META-INF/jars/fabric-key-mapping-api-v1*' 'META-INF/jars/fabric-message-api-v1*' -d build/fapi

CP="$MR/meta/versions/26.3-0.20.1/26.3-0.20.1.jar"
# Only the libraries the 26.3 Fabric version file lists (maven coords -> paths).
while IFS= read -r coord; do
  IFS=: read -r g a v c <<< "$coord"
  f="$MR/meta/libraries/${g//.//}/$a/$v/$a-$v${c:+-$c}.jar"
  [ -f "$f" ] && CP="$CP;$f"
done < <(grep -o '"name":"[^"]*"' "$MR/meta/versions/26.3-0.20.1/26.3-0.20.1.json" | cut -d'"' -f4 | sort -u)
for j in build/fapi/*.jar; do CP="$CP;$j"; done
# Xaero's Minimap: compile-time only, AgentNet runs without it.
CP="$CP;$(ls "$MR/profiles/AI/mods/"xaerominimap-*.jar | head -1)"
CP="$CP;$(ls "$MR/profiles/AI/mods/"xaeroworldmap-*.jar | head -1)"

javac --release 25 -nowarn -d build/classes -cp "$CP" $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" build/classes/fabric.mod.json
(cd build/classes && jar --create --file ../agentnet-$VERSION.jar .)
echo "Built build/agentnet-$VERSION.jar"
# The jar itself goes on GitHub too (the user, 2026-11-19): a copy at the top of the repo, replaced by every build.
cp "build/agentnet-$VERSION.jar" "agentnet-$VERSION.jar"

# Every successful build is a saved version: committed, and pushed when a GitHub remote ("origin") is set up.
if [ -d .git ]; then
  git add -A
  if ! git diff --cached --quiet; then
    git commit -q -m "build $(date '+%Y-%m-%d %H:%M')" && echo "Saved version $(git rev-parse --short HEAD)"
  fi
  if git remote | grep -q '^origin$'; then git push -q origin main 2>/dev/null && echo "Pushed to GitHub" || echo "Push to GitHub failed (still saved locally)"; fi
fi
