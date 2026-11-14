#!/usr/bin/env bash
# Xcode build phase: copies commercially licensed fonts from the git-ignored private-fonts/
# folder into the bundle when they exist. Without them, MoodFont falls back to Fredoka.
set -euo pipefail

src="$SRCROOT/../private-fonts"
dest="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
font="Brice-SemiBoldSemiExpanded.otf"

if [[ -f "$src/$font" ]]; then
  cp "$src/$font" "$dest/$font"
else
  rm -f "$dest/$font"
fi
