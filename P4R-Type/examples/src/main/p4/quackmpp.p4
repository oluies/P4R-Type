/* QuackMPP exchange fabric — minimal v1model dataplane.
 *
 * This exists to produce a p4info the way QuackMPP actually will — by running
 * real p4c — rather than by hand-writing JSON in the shape p4c is believed to
 * emit. (That guess was close but wrong: p4c also emits `initialDefaultAction`.)
 *
 * Regenerate the fixture from the repo root with:
 *   container/p4rt.sh gen
 * which writes straight to P4R-Type/src/test/resources/quackmpp_exchange.p4info.json
 * and then prints the sbt command that regenerates the Scala types.
 * Do not write a p4info next to this file: nothing reads it, and CI compares
 * against the fixture.
 *
 * The shape that matters for spec 003 is the `exchange` table: an EXACT match
 * on a field named `bucket`, plus an action with a couple of parameters. The
 * generated Scala types make renaming `bucket` a compile error in the
 * controller — see QuackMppTypegenSuite.
 */

#include <core.p4>
#include <v1model.p4>

const bit<16> TYPE_QUACK = 0x1234;

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

/* The exchange header: which bucket this row hashes to. */
header quack_t {
    bit<16> bucket;
    bit<16> seq;
}

struct quack_meta_t {
    bit<16> bucket;
}

struct metadata {
    quack_meta_t quack;
}

struct headers {
    ethernet_t ethernet;
    quack_t    quack;
}

parser ParserImpl(packet_in packet,
                  out headers hdr,
                  inout metadata meta,
                  inout standard_metadata_t standard_metadata) {
    state start {
        packet.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            TYPE_QUACK: parse_quack;
            default:    accept;
        }
    }
    state parse_quack {
        packet.extract(hdr.quack);
        meta.quack.bucket = hdr.quack.bucket;
        transition accept;
    }
}

control verifyChecksum(inout headers hdr, inout metadata meta) {
    apply { }
}

/* Named `QuackMPP` so p4c emits the table as `QuackMPP.exchange` and the
 * actions as `QuackMPP.set_worker` / `QuackMPP.drop`. */
control QuackMPP(inout headers hdr,
                 inout metadata meta,
                 inout standard_metadata_t standard_metadata) {

    action drop() {
        mark_to_drop(standard_metadata);
    }

    action set_worker(bit<32> worker_id, bit<9> port) {
        standard_metadata.egress_spec = port;
        hdr.quack.seq = (bit<16>) worker_id;
    }

    table exchange {
        key = {
            meta.quack.bucket : exact;
        }
        actions = {
            set_worker;
            drop;
            NoAction;
        }
        size = 1024;
        default_action = NoAction();
    }

    apply {
        if (hdr.quack.isValid()) {
            exchange.apply();
        }
    }
}

control egressImpl(inout headers hdr,
                   inout metadata meta,
                   inout standard_metadata_t standard_metadata) {
    apply { }
}

control computeChecksum(inout headers hdr, inout metadata meta) {
    apply { }
}

control DeparserImpl(packet_out packet, in headers hdr) {
    apply {
        packet.emit(hdr.ethernet);
        packet.emit(hdr.quack);
    }
}

V1Switch(
    ParserImpl(),
    verifyChecksum(),
    QuackMPP(),
    egressImpl(),
    computeChecksum(),
    DeparserImpl()
) main;
