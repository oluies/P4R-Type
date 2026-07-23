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
#   ./p4rt.sh gen-types  regenerate the committed Scala types from that fixture
#   ./p4rt.sh gen-vm     ...with the p4c the mininet VM ships (1.2.4.x)
#   ./p4rt.sh test       up + run Bmv2WireSuite against it
#   ./p4rt.sh pipeline-test  up + push a pipeline + insert/read a table entry
#
# Override the runtime with RUNTIME=docker|container.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
P4DIR="$HERE/../P4R-Type/examples/src/main/p4"
PROJ="$HERE/../P4R-Type"

BMV2_IMAGE="p4lang/behavioral-model:latest"
# PINNED, and this is the version the committed fixtures were generated with, so
# `gen` reproduces them byte-for-byte and CI (which pins the same tag) stays
# green. (`gen-types` is unaffected by this pin: it runs sbt on the host and
# regenerates the Scala types from the already-committed fixtures, touching no
# container.) The same tag is pinned in compose.yaml; move all three together.
# `latest` was here until 2026-07-21, when it shipped a build whose
# p4c-bm2-ss could not load libboost_iostreams.so.1.83.0 — a fixture check must
# not ride a moving tag. To re-check against a newer p4c, bump this deliberately
# and regenerate the fixtures if the output changed.
P4C_IMAGE="p4lang/p4c:1.2.5.15"
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
    # Writes the two generated fixtures directly, one per .p4; that is what the
    # tests and CI read, so generating a second copy next to the .p4 only creates
    # something to drift. (The sibling legacy_actionprofile.p4info.json is
    # hand-written, has no .p4, and must not be regenerated here.) This same
    # (source, fixture) set is duplicated in ci.yml's PAIRS and compose.yaml's
    # p4c service; keep the three in sync when adding a .p4.
    #
    # quackmpp.p4    — the spec 003 shape: EXACT on `bucket`, action params.
    # matchkinds.p4  — TERNARY/RANGE/OPTIONAL/LPM/EXACT on one table. Nothing
    #                  else in the repo uses the middle three, so without it
    #                  typegen's emission for them is never exercised.
    run_rm -v "$PROJ:/proj" -w /proj/examples/src/main/p4 "$P4C_IMAGE" \
      sh -c 'p4c --target bmv2 --arch v1model \
               --p4runtime-files /proj/src/test/resources/quackmpp_exchange.p4info.json \
               -o /tmp quackmpp.p4'
    echo "refreshed src/test/resources/quackmpp_exchange.p4info.json"
    run_rm -v "$PROJ:/proj" -w /proj/examples/src/main/p4 "$P4C_IMAGE" \
      sh -c 'p4c --target bmv2 --arch v1model \
               --p4runtime-files /proj/src/test/resources/matchkinds.p4info.json \
               -o /tmp matchkinds.p4'
    echo "refreshed src/test/resources/matchkinds.p4info.json"
    echo "now run: $0 gen-types"
    ;;

  gen-types)
    # Regenerates the committed types from the committed fixtures. typegen writes
    # the file itself (third arg) rather than printing to stdout — redirecting
    # sbt's stdout puts its log lines and ANSI escapes in the file, and every
    # attempt to filter those out has been a bug.
    ( cd "$PROJ" && sbt -batch "runMain typegen.parseP4info \
        src/test/resources/quackmpp_exchange.p4info.json quackmpp \
        src/test/scala/quackmpp_exchange.scala" )
    echo "regenerated P4R-Type/src/test/scala/quackmpp_exchange.scala"
    ( cd "$PROJ" && sbt -batch "runMain typegen.parseP4info \
        src/test/resources/matchkinds.p4info.json matchkinds \
        src/test/scala/matchkinds.scala" )
    echo "regenerated P4R-Type/src/test/scala/matchkinds.scala"
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

  pipeline-test)
    # The full loop: install a pipeline, insert a table entry, read it back.
    # Needs the compiled dataplane (quackmpp.json), which p4c writes next to the
    # .p4 — build it if it is not there.
    if [ ! -f "$P4DIR/quackmpp.json" ]; then
      echo "compiling quackmpp.p4 for bmv2..."
      run_rm -v "$P4DIR:/w" -w /w "$P4C_IMAGE" \
        sh -c 'p4c --target bmv2 --arch v1model -o /w /w/quackmpp.p4'
    fi
    "$0" up
    ( cd "$PROJ" && sbt shutdown >/dev/null 2>&1 || true )
    ( cd "$PROJ" && P4RT_BMV2="localhost:$PORT" P4RT_BMV2_JSON="$P4DIR/quackmpp.json" \
        sbt "testOnly *Bmv2PipelineSuite" )
    ;;

  *)
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
