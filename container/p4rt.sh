#!/usr/bin/env bash
# P4 tooling on either container runtime.
#
# compose.yaml covers Docker. Apple's `container` has no compose support
# (`container compose` -> "Plugin 'container-compose' not found"), so this
# script is the portable path: same commands, either runtime.
#
#   ./p4rt.sh up        start bmv2 on localhost:9559 (waits until it answers)
#   ./p4rt.sh down       stop it
#   ./p4rt.sh gen        regenerate the p4info fixture with current p4c
#   ./p4rt.sh gen-vm     ...with the p4c the mininet VM ships (1.2.4.x)
#   ./p4rt.sh test       up + run Bmv2WireSuite against it
#
# Override the runtime with RUNTIME=docker|container.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
P4DIR="$HERE/../P4R-Type/examples/src/main/p4"
PROJ="$HERE/../P4R-Type"

BMV2_IMAGE="p4lang/behavioral-model:latest"
P4C_IMAGE="p4lang/p4c:latest"
# vm/ installs p4lang-p4c 1.2.4.2 from the home:p4lang OBS repo; 1.2.4.3 is the
# closest tag p4lang publishes as an image.
P4C_VM_IMAGE="p4lang/p4c:1.2.4.3"
NAME="p4rt-bmv2"
PORT=9559

# --- runtime detection ------------------------------------------------------
RUNTIME="${RUNTIME:-}"
if [ -z "$RUNTIME" ]; then
  if command -v container >/dev/null 2>&1; then RUNTIME=container
  elif command -v docker  >/dev/null 2>&1; then RUNTIME=docker
  else echo "need either apple 'container' or 'docker' on PATH" >&2; exit 1
  fi
fi

# Apple's daemon is not started automatically, and is not restarted after a
# reboot. Every subcommand otherwise fails with an opaque XPC error that reads
# like a broken install.
if [ "$RUNTIME" = container ]; then
  container system status >/dev/null 2>&1 || container system start >/dev/null
fi

# p4lang publishes linux/amd64 only, so the platform must be explicit on an
# arm64 host. Emulated there: the first p4c start took ~3m36s on an M-series Mac.
PLATFORM_ARG=(--platform linux/amd64)

run_rm() { "$RUNTIME" run --rm "${PLATFORM_ARG[@]}" "$@"; }

wait_for_port() {
  # `-d`/`up` returns as soon as the VM is up, which is well before the service
  # inside is accepting connections. Poll; do not sleep and hope.
  for _ in $(seq 1 60); do
    nc -z localhost "$PORT" 2>/dev/null && return 0
    sleep 1
  done
  echo "bmv2 never opened localhost:$PORT — check '$RUNTIME logs $NAME'" >&2
  return 1
}

case "${1:-}" in
  up)
    "$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
    # The `--` before --grpc-server-addr is load-bearing: it is a
    # target-specific option, and without the separator bmv2 prints usage and
    # exits — while the published port still answers, so a port check lies.
    "$RUNTIME" run -d --name "$NAME" "${PLATFORM_ARG[@]}" -p "$PORT:$PORT" \
      "$BMV2_IMAGE" simple_switch_grpc --no-p4 -- --grpc-server-addr "0.0.0.0:$PORT"
    wait_for_port
    # Connect on localhost, NOT the IP `container ls` prints — that address is
    # not routable from the macOS host and changes across restarts.
    echo "bmv2 up: localhost:$PORT   (runtime: $RUNTIME)"
    ;;

  down)
    "$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
    echo "bmv2 down"
    ;;

  gen)
    run_rm -v "$P4DIR:/w" -w /w "$P4C_IMAGE" \
      sh -c 'p4c --target bmv2 --arch v1model --p4runtime-files /w/quackmpp.p4info.json -o /w /w/quackmpp.p4'
    cp "$P4DIR/quackmpp.p4info.json" "$PROJ/src/test/resources/quackmpp_exchange.p4info.json"
    echo "refreshed src/test/resources/quackmpp_exchange.p4info.json"
    echo "now regenerate the types:  sbt \"runMain typegen.parseP4info src/test/resources/quackmpp_exchange.p4info.json quackmpp\""
    ;;

  gen-vm)
    run_rm -v "$P4DIR:/w" -w /w "$P4C_VM_IMAGE" \
      sh -c 'p4c --target bmv2 --arch v1model --p4runtime-files /w/quackmpp.vm-era.p4info.json -o /tmp /w/quackmpp.p4'
    echo "wrote $P4DIR/quackmpp.vm-era.p4info.json using the VM's p4c line"
    echo "expected difference vs the committed fixture: no initialDefaultAction."
    echo "typegen ignores that field, so the generated types are identical — verified."
    ;;

  test)
    "$0" up
    ( cd "$PROJ" && sbt shutdown >/dev/null 2>&1 || true )
    # sbt's server outlives your shell and Test/fork inherits its environment at
    # start, so P4RT_BMV2 must be set for a *fresh* server — hence the shutdown.
    ( cd "$PROJ" && P4RT_BMV2="localhost:$PORT" sbt "testOnly *Bmv2WireSuite" )
    ;;

  *)
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
