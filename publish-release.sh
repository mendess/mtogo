#!/bin/bash

set -ex

next_code=$(grep -oE 'versionCode = [0-9]+' ./app/build.gradle.kts |
    cut -d= -f2 |
    awk '{ print $1 + 1 }')

next_version=$(grep -oE 'versionName = "[^"]+"' ./app/build.gradle.kts |
    cut -d= -f2 |
    cut -d'"' -f2 |
    awk -F '.' '{ print $1 "." $2 "." ($3 + 1) }')

sed -E -i "s/versionCode = [0-9]+/versionCode = $next_code/" ./app/build.gradle.kts
sed -E -i "s/versionName = \"[^\"]+/versionName = \"$next_version/" ./app/build.gradle.kts

./gradlew assembleRelease

cp -v ./app/build/outputs/apk/release/app-release.apk /tmp/mtogo.apk

spark rsync av /tmp/mtogo.apk pendrellvale:vault/basement/blind-eternities/music/mtogo

rm -v /tmp/mtogo.apk

git add ./app/build.gradle.kts
git commit -m "Release $next_version"
git push
