/* A test fixture, not a dataplane anyone would deploy.
 *
 * It exists to exercise the match kinds no other committed p4info uses.
 * Before this file, every fixture in the repo (quackmpp_exchange, config1,
 * config1_lb, config2, config2_nat, config2_new) used only EXACT and LPM — so
 * typegen's TERNARY, RANGE and OPTIONAL emission branches had never once run,
 * in generation or in compilation. They emit Scala *source*, so a typo in any
 * of them produces generated code that does not compile, and nothing in the
 * repo would have noticed.
 *
 * Two further things it pins that quackmpp.p4 cannot:
 *
 *  - A **multi-field key**. typegen folds several match fields into a flat
 *    tuple, (f1, f2, f3, f4, f5), in p4info order — the shape a controller has
 *    to write. It was NOT flat until this fixture was added: the type was
 *    emitted left-nested, ((((f1, f2), f3), f4), f5), while toProto
 *    destructured a flat tuple, so any table with three or more match fields
 *    died with scala.MatchError. See ARCHITECTURE.md §2.
 *  - A table that **requires a nonzero priority**. P4Runtime fixes priority to
 *    0 for exact-only tables and demands nonzero once a ternary/range/optional
 *    field is present, so this is the other side of the rule quackmpp.p4's
 *    exchange table exercises (ARCHITECTURE.md §7 gap 6).
 *
 * Regenerate from the repo root with:
 *   container/p4rt.sh gen        -> P4R-Type/src/test/resources/matchkinds.p4info.json
 *   container/p4rt.sh gen-types  -> P4R-Type/src/test/scala/matchkinds.scala
 */

#include <core.p4>
#include <v1model.p4>

const bit<16> TYPE_IPV4 = 0x0800;

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

header ipv4_t {
    bit<8>  version_ihl;
    bit<8>  diffserv;
    bit<16> totalLen;
    bit<16> identification;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<32> srcAddr;
    bit<32> dstAddr;
}

struct metadata {
    bit<16> bucket;
}

struct headers {
    ethernet_t ethernet;
    ipv4_t     ipv4;
}

parser ParserImpl(packet_in packet,
                  out headers hdr,
                  inout metadata meta,
                  inout standard_metadata_t standard_metadata) {
    state start {
        packet.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            TYPE_IPV4: parse_ipv4;
            default:   accept;
        }
    }
    state parse_ipv4 {
        packet.extract(hdr.ipv4);
        meta.bucket = (bit<16>) hdr.ipv4.identification;
        transition accept;
    }
}

control verifyChecksum(inout headers hdr, inout metadata meta) {
    apply { }
}

/* Named `MatchKinds` so p4c emits `MatchKinds.acl` and `MatchKinds.forward`. */
control MatchKinds(inout headers hdr,
                   inout metadata meta,
                   inout standard_metadata_t standard_metadata) {

    action drop() {
        mark_to_drop(standard_metadata);
    }

    action forward(bit<9> port, bit<48> dstAddr) {
        standard_metadata.egress_spec = port;
        hdr.ethernet.dstAddr = dstAddr;
    }

    /* All five P4Runtime match kinds on one table.
     *
     * The generated tuple mixes bare and Option arms: exact fields are
     * mandatory in P4Runtime and the rest may be omitted, so typegen emits
     * ("name", Exact) bare and everything else as Option[...]. That is decided
     * by match kind alone, not by position — the mix would be the same in any
     * order.
     *
     * The exact field is placed last only so the fixture does not accidentally
     * match the single-field shape at its head. */
    table acl {
        key = {
            hdr.ipv4.srcAddr  : ternary;
            hdr.ipv4.totalLen : range;
            hdr.ipv4.protocol : optional;
            hdr.ipv4.dstAddr  : lpm;
            meta.bucket       : exact;
        }
        actions = {
            forward;
            drop;
            NoAction;
        }
        size = 1024;
        default_action = NoAction();
    }

    apply {
        if (hdr.ipv4.isValid()) {
            acl.apply();
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
        packet.emit(hdr.ipv4);
    }
}

V1Switch(
    ParserImpl(),
    verifyChecksum(),
    MatchKinds(),
    egressImpl(),
    computeChecksum(),
    DeparserImpl()
) main;
