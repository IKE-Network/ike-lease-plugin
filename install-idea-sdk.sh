#!/bin/bash
# install-idea-sdk.sh — one-time per machine: put the handful of IntelliJ
# jars this plugin compiles against into the local Maven repository.
#
# Why not the JetBrains Maven repository? Its published artifacts are a
# far thinner slice than the jars the IDE actually ships — the platform
# `core` artifact does not contain Project, Disposable, or
# StartupActivity — and the bucket serves no directory listing, so the
# right combination cannot be enumerated. The installed IDE is the one
# source that certainly matches the IDE the plugin will run in.
#
# Why not system-scoped dependencies? Maven 4 has retired that scope.
#
# Coordinates are synthetic and local-only: com.jetbrains.intellij.local.
# Nothing here is ever published.
#
# Usage: ./install-idea-sdk.sh [/path/to/IntelliJ IDEA.app/Contents]
set -euo pipefail

IDEA_HOME="${1:-/Applications/IntelliJ IDEA.app/Contents}"
[ -d "$IDEA_HOME/lib" ] || IDEA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents"
if [ ! -d "$IDEA_HOME/lib" ]; then
    echo "IntelliJ IDEA not found. Pass its Contents directory:" >&2
    echo "  $0 '/Applications/IntelliJ IDEA.app/Contents'" >&2
    exit 1
fi

# Build number, e.g. 262.9437.185 — the plugin is compiled against
# exactly the IDE it will run in, so read it from that IDE's own
# build.txt (`IU-262.9437.185`). Deriving it from the JetBrains caches
# directory instead picks whichever IDE version sorts first, which on a
# machine with several installed is the wrong one.
BUILD="$(sed -E 's/^[A-Z]+-//; s/[^0-9.].*$//' \
    "$IDEA_HOME/Resources/build.txt" 2>/dev/null | head -1)"
[ -n "$BUILD" ] || BUILD="local"

JARS=(
    intellij.platform.core.jar          # Project, ApplicationManager, Service, StartupActivity
    intellij.platform.projectModel.jar  # ProjectManager, ProjectManagerListener
    intellij.platform.ide.jar           # Messages
    intellij.platform.ide.core.jar      # NotificationGroupManager, NotificationType
    util-8.jar                          # Disposable
    util.jar                            # ComponentManager, AreaInstance
    util_rt.jar                         # Pair
    annotations.jar                     # org.jetbrains.annotations.NotNull
)

echo "IntelliJ:  $IDEA_HOME"
echo "build:     $BUILD"
for jar in "${JARS[@]}"; do
    path="$IDEA_HOME/lib/$jar"
    if [ ! -f "$path" ]; then
        echo "  MISSING: $jar" >&2
        exit 1
    fi
    artifact="${jar%.jar}"
    mvn -q install:install-file \
        -Dfile="$path" \
        -DgroupId=com.jetbrains.intellij.local \
        -DartifactId="$artifact" \
        -Dversion="$BUILD" \
        -Dpackaging=jar
    echo "  installed: com.jetbrains.intellij.local:$artifact:$BUILD"
done

echo
echo "Now build with:  mvn -Didea.build=$BUILD package"
