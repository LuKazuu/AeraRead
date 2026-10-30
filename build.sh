#!/usr/bin/env bash
# Aera Read — local and CI APK build script.
#
#   ./build.sh                 interactive menu (in a terminal)
#   ./build.sh apk             build R8-optimized APK only -> ./AeraRead-1.2.apk
#   ./build.sh release [tag]   build APK, tag commit (default v1.2), publish if gh is installed
#   ./build.sh test            run unit tests
#   ./build.sh lint            run Android lint
#   ./build.sh clean           delete build outputs
#
# Extra Gradle flags (e.g. --no-daemon) may follow any command.
set -euo pipefail
cd "$(dirname "$0")"

GRADLEW=./gradlew
APK_OUT=AeraRead-1.2.apk

if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
  echo "build.sh: set JAVA_HOME to JDK 17+" >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
  echo "build.sh: set ANDROID_HOME or create local.properties with sdk.dir" >&2
  exit 1
fi

run_gradle() {
  echo "+ $GRADLEW $*"
  bash "$GRADLEW" ${TMPDIR:+-Djava.io.tmpdir="$TMPDIR"} --console=plain "$@"
}

build_apk() {
  bash "$GRADLEW" --stop >/dev/null 2>&1 || true
  run_gradle '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' assembleRelease "$@"
  cp app/build/outputs/apk/release/app-release.apk "$APK_OUT"
  echo "APK ready: $APK_OUT"
}

CMD=""
TAG=""

if [[ $# -eq 0 ]]; then
  if [[ -t 0 ]]; then
    echo "Build Aera Read:"
    echo "  1) APK only (local build)"
    echo "  2) Release (build APK + git tag)"
    read -r -p "Select [1-2] (default 1): " choice
    case "${choice:-1}" in
      2)
        CMD=release
        read -r -p "Release tag [v1.2]: " tag_input
        TAG="${tag_input:-v1.2}"
        ;;
      *)
        CMD=apk
        ;;
    esac
  else
    CMD=apk
  fi
elif [[ "$1" != -* ]]; then
  CMD=$1
  shift
  if [[ "$CMD" == "release" && $# -gt 0 && "$1" != -* ]]; then
    TAG=$1
    shift
  fi
else
    CMD=apk
fi

case "$CMD" in
  apk)
    build_apk "$@"
    ;;
  release)
    TAG="${TAG:-v1.2}"
    build_apk "$@"
    if git rev-parse --git-dir >/dev/null 2>&1; then
      git tag -f -a "$TAG" -m "Aera Read $TAG"
      echo "Tagged commit as $TAG"
      if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        gh release create "$TAG" "$APK_OUT" --title "Aera Read $TAG" --generate-notes
      else
        echo "Push tag to publish: git push origin $TAG"
      fi
    fi
    ;;
  test)
    bash "$GRADLEW" --stop >/dev/null 2>&1 || true
    run_gradle testDebugUnitTest "$@"
    ;;
  lint)
    bash "$GRADLEW" --stop >/dev/null 2>&1 || true
    run_gradle '-Dorg.gradle.jvmargs=-Xmx640m -XX:MaxMetaspaceSize=320m -Dfile.encoding=UTF-8' lintDebug "$@"
    ;;
  clean)
    run_gradle clean "$@"
    rm -f "$APK_OUT"
    ;;
  *)
    run_gradle "$CMD" "$@"
    ;;
esac
