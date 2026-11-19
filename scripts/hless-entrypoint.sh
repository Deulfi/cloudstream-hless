#!/bin/sh
# Starts the JVM with a heap sized from the memory this container really has.
# The JVM's own container detection can see the host's memory instead (LXC), which
# would give a heap far bigger than the container, so look at /proc/meminfo and
# the cgroup limit ourselves and take the smaller.
#   HLESS_HEAP_MB       fixed heap in MB (overrides everything)
#   HLESS_HEAP_PERCENT  share of the memory for the heap (default 60)
mem_mb=$(awk '/^MemTotal:/ {print int($2 / 1024)}' /proc/meminfo 2>/dev/null)
for f in /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory/memory.limit_in_bytes; do
  limit=$(cat "$f" 2>/dev/null)
  case "$limit" in
    ''|*[!0-9]*) ;;
    *) limit_mb=$((limit / 1048576)); [ "$limit_mb" -gt 0 ] && { [ -z "$mem_mb" ] || [ "$limit_mb" -lt "$mem_mb" ]; } && mem_mb=$limit_mb ;;
  esac
done
heap=${HLESS_HEAP_MB:-$(( ${mem_mb:-512} * ${HLESS_HEAP_PERCENT:-60} / 100 ))}
[ "$heap" -lt 192 ] && heap=192

exec java --add-modules=jdk.incubator.foreign --enable-native-access=ALL-UNNAMED \
  -XX:TieredStopAtLevel=1 -Xms16m "-Xmx${heap}m" \
  -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError \
  -Dsun.net.inetaddr.ttl=600 -Djava.awt.headless=true \
  -Dcs.cloudstream.home="${HLESS_DATA:-/data}" \
  -cp "${HLESS_JAR:-/app/daemon.jar}" "$@"
