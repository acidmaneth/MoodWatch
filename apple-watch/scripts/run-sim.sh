#!/usr/bin/env bash
# Builds Moods for a watchOS simulator, installs it and launches it.
#
# Usage (from anywhere):
#   apple-watch/scripts/run-sim.sh            # generate, build, boot, install, launch
#   apple-watch/scripts/run-sim.sh build      # generate + build only
#   apple-watch/scripts/run-sim.sh boot       # find or create the simulator and boot it
#   apple-watch/scripts/run-sim.sh install    # install the last build
#   apple-watch/scripts/run-sim.sh launch     # (re)launch the installed app; extra args go to the app,
#                                             # e.g. launch -screen collection (see App/DebugLaunch.swift)
#   apple-watch/scripts/run-sim.sh shot face  # screenshot to apple-watch/build/screenshots/face.png
#   apple-watch/scripts/run-sim.sh udid       # print the simulator's UDID
#   apple-watch/scripts/run-sim.sh logs       # stream the app's logs
#
# Uses DEVELOPER_DIR (default /Applications/Xcode.app/Contents/Developer), so `sudo xcode-select`
# isn't needed. Set SIM_UDID to use a specific simulator.
set -euo pipefail

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT/build"
DERIVED="$BUILD_DIR/DerivedData"
APP="$DERIVED/Build/Products/Debug-watchsimulator/Moods.app"
BUNDLE_ID="com.moodwatch.app.watch"
SIM_NAME="MoodWatch Sim"
PREFERRED_TYPES="Apple Watch Series 12 (46mm)|Apple Watch Series 11 (46mm)|Apple Watch Series 10 (46mm)|Apple Watch Ultra 4 (49mm)|Apple Watch Ultra 3 (49mm)|Apple Watch Ultra 2 (49mm)"

die() { echo "error: $*" >&2; exit 1; }

[[ -d "$DEVELOPER_DIR" ]] || die "Xcode not found at $DEVELOPER_DIR (install Xcode or set DEVELOPER_DIR)"

# Prints the UDID of an available watchOS simulator, preferring the newest runtime and
# PREFERRED_TYPES in order; prints nothing if there is none.
find_udid() {
  xcrun simctl list devices available -j | PREFERRED="$PREFERRED_TYPES" SIM_NAME="$SIM_NAME" python3 -c '
import json, os, re, sys
devices = json.load(sys.stdin)["devices"]
def version(runtime):
    m = re.search(r"watchOS-(\d+)-(\d+)", runtime)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)
watch = sorted((r for r in devices if "watchOS" in r), key=version, reverse=True)
names = os.environ["PREFERRED"].split("|") + [os.environ["SIM_NAME"]]
for name in names:
    for runtime in watch:
        for d in devices[runtime]:
            if d["name"] == name and d.get("isAvailable", True):
                print(d["udid"]); sys.exit()
for runtime in watch:
    for d in devices[runtime]:
        if "Apple Watch" in d["name"] and d.get("isAvailable", True):
            print(d["udid"]); sys.exit()
'
}

# Creates "MoodWatch Sim" on the newest watchOS runtime and prints its UDID.
create_sim() {
  local ids
  ids="$(xcrun simctl list runtimes available -j | PREFERRED="$PREFERRED_TYPES" python3 -c '
import json, os, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r.get("platform") == "watchOS" and r.get("isAvailable", True)]
if not runtimes: sys.exit()
runtime = max(runtimes, key=lambda r: [int(x) for x in r["version"].split(".")])
types = runtime.get("supportedDeviceTypes", [])
for name in os.environ["PREFERRED"].split("|"):
    for t in types:
        if t["name"] == name:
            print(runtime["identifier"], t["identifier"]); sys.exit()
watches = [t for t in types if "Apple Watch" in t["name"]]
if watches: print(runtime["identifier"], watches[-1]["identifier"])
')"
  [[ -n "$ids" ]] || die "no watchOS simulator runtime installed. Run: xcodebuild -downloadPlatform watchOS"
  local runtime="${ids%% *}" type="${ids##* }"
  echo "Creating '$SIM_NAME' ($type, $runtime)" >&2
  xcrun simctl create "$SIM_NAME" "$type" "$runtime"
}

udid() {
  if [[ -n "${SIM_UDID:-}" ]]; then echo "$SIM_UDID"; return; fi
  local id
  id="$(find_udid)"
  [[ -n "$id" ]] || id="$(create_sim)"
  echo "$id"
}

boot() {
  local id="$1"
  xcrun simctl bootstatus "$id" -b >/dev/null
  # Simulator.app moved in Xcode 27 (DeviceHub.app); the app runs headless without either.
  local viewer
  for viewer in "$DEVELOPER_DIR/Applications/Simulator.app" "$DEVELOPER_DIR/../Applications/DeviceHub.app"; do
    if [[ -d "$viewer" ]]; then
      open -a "$viewer" --args -CurrentDeviceUDID "$id"
      return
    fi
  done
}

generate() {
  if command -v xcodegen >/dev/null; then
    (cd "$ROOT" && xcodegen generate --quiet)
  elif [[ ! -d "$ROOT/MoodWatch.xcodeproj" ]]; then
    die "xcodegen not found. Run: brew install xcodegen"
  fi
}

build() {
  local id="$1"
  generate
  echo "Building for simulator ${id}…"
  xcodebuild \
    -project "$ROOT/MoodWatch.xcodeproj" \
    -scheme MoodWatch \
    -configuration Debug \
    -destination "platform=watchOS Simulator,id=$id" \
    -derivedDataPath "$DERIVED" \
    -quiet \
    build
  echo "Built $APP"
}

install_app() {
  [[ -d "$APP" ]] || die "no build at $APP; run: $0 build"
  xcrun simctl install "$1" "$APP"
}

launch() {
  local id="$1"
  shift
  xcrun simctl terminate "$id" "$BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$id" "$BUNDLE_ID" "$@"
}

shot() {
  local name="${2:-screenshot}"
  mkdir -p "$BUILD_DIR/screenshots"
  xcrun simctl io "$1" screenshot "$BUILD_DIR/screenshots/$name.png"
  echo "$BUILD_DIR/screenshots/$name.png"
}

cmd="${1:-run}"
case "$cmd" in
  run)
    id="$(udid)"
    boot "$id"
    build "$id"
    install_app "$id"
    shift || true
    launch "$id" "$@"
    ;;
  build) build "$(udid)" ;;
  boot) boot "$(udid)" ;;
  install) install_app "$(udid)" ;;
  launch) shift; launch "$(udid)" "$@" ;;
  shot) shot "$(udid)" "${2:-}" ;;
  udid) udid ;;
  logs) xcrun simctl spawn "$(udid)" log stream --level debug --predicate 'process == "Moods" OR process == "MoodsWidget"' ;;
  *) die "unknown command '$cmd' (run, build, boot, install, launch, shot <name>, udid, logs)" ;;
esac
