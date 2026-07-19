package matchkinds

import p4rtype.{Exact, LPM, Optional, Range, Ternary, P4RTypeRuntimeObserver}
import com.google.protobuf.ByteString
import p4.v1.p4runtime.FieldMatch
import p4.v1.p4runtime.FieldMatch.FieldMatchType
import p4.v1.p4runtime.TableAction
import p4.v1.p4runtime.Action
import p4.v1.p4runtime.P4RuntimeGrpc.P4RuntimeStub
import p4.v1.p4runtime.TableEntry
import io.grpc.ManagedChannelBuilder
import p4.v1.p4runtime.StreamMessageRequest
import p4.v1.p4runtime.MasterArbitrationUpdate
import p4.v1.p4runtime.Uint128
import io.grpc.CallOptions
import p4.v1.p4runtime.StreamMessageResponse
import p4.v1.p4runtime.Action.Param

type TableMatchFields[TN] =
  TN match
    case "MatchKinds.acl" => (Option[("hdr.ipv4.srcAddr", Ternary)], Option[("hdr.ipv4.totalLen", Range)], Option[("hdr.ipv4.protocol", Optional)], Option[("hdr.ipv4.dstAddr", LPM)], ("meta.bucket", Exact)) | "*"
    case "*" => "*"
type ActionName = "NoAction" | "MatchKinds.drop" | "MatchKinds.forward" | "*"

type TableAction[TN] <: ActionName =
  TN match
    case "MatchKinds.acl" => "MatchKinds.forward" | "MatchKinds.drop" | "NoAction" | "*"
    case "*" => "*"

type ActionParams[AN] =
  AN match
    case "NoAction" => Unit
    case "MatchKinds.drop" => Unit
    case "MatchKinds.forward" => (("port", ByteString), ("dstAddr", ByteString))
    case "*" => "*"

class Chan (deviceId : Int, socket : P4RuntimeStub, channel : io.grpc.ManagedChannel) extends p4rtype.Chan[TableMatchFields, TableAction, ActionParams](deviceId, socket, channel):
  override def toProto(te : p4rtype.TableEntry[TableMatchFields, TableAction, ActionParams, _, _]) : TableEntry =
    val tableId =
      te.table match
        case "*" => 0
        case "MatchKinds.acl" => 48546767

    val matchFields =
      (te.table, te.matches) match
        case ("*", _) => Seq.empty
        case (_, _ : "*") => Seq.empty
        case ("MatchKinds.acl", (t0, t1, t2, t3, (_, t4))) => t0.asInstanceOf[Option[("hdr.ipv4.srcAddr", Ternary)]].map((_, t) => p4rtype.matchFieldToProto(1, t)).toSeq ++ t1.asInstanceOf[Option[("hdr.ipv4.totalLen", Range)]].map((_, t) => p4rtype.matchFieldToProto(2, t)).toSeq ++ t2.asInstanceOf[Option[("hdr.ipv4.protocol", Optional)]].map((_, t) => p4rtype.matchFieldToProto(3, t)).toSeq ++ t3.asInstanceOf[Option[("hdr.ipv4.dstAddr", LPM)]].map((_, t) => p4rtype.matchFieldToProto(4, t)).toSeq ++ Seq(p4rtype.matchFieldToProto(5, t4.asInstanceOf[Exact]))

    val actionId =
      te.action match
        case "*" => 0
        case "NoAction" => 21257015
        case "MatchKinds.drop" => 25296330
        case "MatchKinds.forward" => 32757467

    val params =
      (te.action, te.params) match
        case ("*", _) => Seq.empty
        case ("NoAction", _) => Seq.empty
        case ("MatchKinds.drop", _) => Seq.empty
        case ("MatchKinds.forward", (("port", p0), ("dstAddr", p1)) : (("port", ByteString), ("dstAddr", ByteString))) => Seq(Param(paramId = 1, value = p4rtype.canonical(p0))) ++ Seq(Param(paramId = 2, value = p4rtype.canonical(p1)))

    TableEntry(
    tableId = tableId,
    `match` = matchFields,
    action =
      if actionId != 0 then
        Some(TableAction(
          `type` = TableAction.Type.Action(
            value = Action(
              actionId = actionId,
              params = params
            )
          )
        ))
      else
        None
  )

  override def fromProto[TM[_], TA[_], TP[_], XN <: String, XA <: TA[XN]](te : TableEntry): p4rtype.TableEntry[TM, TA, TP, XN, XA] =
    val actionId = te.action.get.`type`.action.get.actionId
    val teParams = te.action.get.`type`.action.get.params

    val table =
      te.tableId match
        case 48546767 => "MatchKinds.acl"
        case 0 => "*"
    val matches =
      te.tableId match
        case 48546767 => (te.`match`.find(_.fieldId == 1).map(fm => ("hdr.ipv4.srcAddr", Ternary(fm.fieldMatchType.ternary.get.value, fm.fieldMatchType.ternary.get.mask))), te.`match`.find(_.fieldId == 2).map(fm => ("hdr.ipv4.totalLen", Range(fm.fieldMatchType.range.get.low, fm.fieldMatchType.range.get.high))), te.`match`.find(_.fieldId == 3).map(fm => ("hdr.ipv4.protocol", Optional(fm.fieldMatchType.optional.get.value))), te.`match`.find(_.fieldId == 4).map(fm => ("hdr.ipv4.dstAddr", LPM(fm.fieldMatchType.lpm.get.value, fm.fieldMatchType.lpm.get.prefixLen))), te.`match`.find(_.fieldId == 5).map(fm => ("meta.bucket", Exact(fm.fieldMatchType.exact.get.value))).get)
        case 0 => "*"
    val action =
      actionId match
        case 21257015 => "NoAction"
        case 25296330 => "MatchKinds.drop"
        case 32757467 => "MatchKinds.forward"
        case 0 => "*"
    val params =
      actionId match
        case 21257015 => ()
        case 25296330 => ()
        case 32757467 => (teParams.find(_.paramId == 1).map(pm => ("port", pm.value)).get, teParams.find(_.paramId == 2).map(pm => ("dstAddr", pm.value)).get)
        case 0 => "*"
    val myTable : XN = table.asInstanceOf[XN]
    val myAction : TA[myTable.type] = action.asInstanceOf[TA[myTable.type]]
    p4rtype.TableEntry[TM, TA, TP](
      table = myTable,
      matches = matches.asInstanceOf[TM[myTable.type]],
      action = myAction,
      params = params.asInstanceOf[TP[myAction.type]],
      1
    ).asInstanceOf[p4rtype.TableEntry[TM, TA, TP, XN, XA]]

/** Connect to a P4Runtime server.
  * @param id The device ID, which is assigned by the controller (i.e. the caller), and should be unique for each controller.
  * @param ip IP address of the target device.
  * @param port Port number of the target device.
  * @return A `Chan` object used by the other P4R-Type API functions for communication.
  */
def connect(id : Int, ip : String, port : Int) : Chan =
  val channel = ManagedChannelBuilder.forAddress(ip, port).usePlaintext().build()
  val request = StreamMessageRequest(
      StreamMessageRequest.Update.Arbitration(
        value = MasterArbitrationUpdate(
          deviceId = id,
          electionId = Some(Uint128(high=0,low=1)),
        )
      )
    )
  val stub = P4RuntimeStub.newStub(channel, CallOptions.DEFAULT)
  val response_obs = new P4RTypeRuntimeObserver[StreamMessageResponse](StreamMessageResponse())
  val request_obs = stub.streamChannel(response_obs)
  request_obs.onNext(request)
  Chan(id, stub, channel)


