// Without this, every top-level definition here — including `generate` — lands in
// the empty package and compiles to typegen$package.class at the jar root.
// Members of the empty package cannot be imported from a named package, so no
// downstream build (QuackMPP's Mill build compiles into `build`/`millbuild`)
// could ever call the library entry point this file exists to expose.
package typegen

import Console.print
import scala.io.Source._
import p4.v1.p4runtime.*
import p4.v1.p4runtime.GetForwardingPipelineConfigRequest.ResponseType.ALL
import p4.v1.p4runtime.FieldMatch.FieldMatchType
import com.google.protobuf.ByteString
import p4.v1.p4runtime.DigestEntry.Config
import p4.config.v1.p4types.P4DataTypeSpec.TypeSpec.Bool
import p4.v1.p4runtime.P4RuntimeGrpc.P4RuntimeStub
import io.grpc.stub.StreamObserver
import io.grpc.CallOptions
import concurrent.ExecutionContext.Implicits.global
import p4.config.v1.p4info.P4Info
import scala.util.Success
import scala.util.Failure
import java.io.FileInputStream
import java.io.InputStreamReader
import com.google.protobuf.TextFormat
import p4.config.v1.p4info.P4InfoProto
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import p4.config.v1.p4info.{Action => P4InfoAction, _}
import p4.config.v1.p4info.MatchField.MatchType.EXACT
import p4.config.v1.p4info.MatchField.MatchType.LPM
import p4.config.v1.p4info.MatchField.MatchType.OPTIONAL
import p4.config.v1.p4info.MatchField.MatchType.RANGE
import p4.config.v1.p4info.MatchField.MatchType.TERNARY
import p4.config.v1.p4info.Action.Param
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Paths


// === Helper functions ===
/** Traverse: applies `op` to each element, short-circuiting on the first Left.
  * Previously `RIO[Unit, _]`-based, but nothing here is effectful, concurrent or
  * resourceful — it is a pure fallible transformation, which is what Either is.
  */
def mapM[A,B](as : Seq[A], op : A => Either[String, B]) : Either[String, Seq[B]] =
  val m_as = as.map(op)
  m_as.foldLeft[Either[String, Seq[B]]](Right(Seq[B]()))((m_res, m_e) => for {
    res <- m_res
    e <- m_e
  } yield (res.:+(e)))

def genImports() : Either[String, String] = Right(
  "import p4rtype.{Exact, LPM, Optional, Range, Ternary, P4RTypeRuntimeObserver}\n" +
  "import com.google.protobuf.ByteString\n" +
  "import p4.v1.p4runtime.FieldMatch\n" +
  "import p4.v1.p4runtime.FieldMatch.FieldMatchType\n" +
  "import p4.v1.p4runtime.TableAction\n" +
  "import p4.v1.p4runtime.Action\n" +
  "import p4.v1.p4runtime.P4RuntimeGrpc.P4RuntimeStub\n" +
  "import p4.v1.p4runtime.TableEntry\n" +
  "import io.grpc.ManagedChannelBuilder\n" +
  "import p4.v1.p4runtime.StreamMessageRequest\n" +
  "import p4.v1.p4runtime.MasterArbitrationUpdate\n" +
  "import p4.v1.p4runtime.Uint128\n" +
  "import io.grpc.CallOptions\n" +
  "import p4.v1.p4runtime.StreamMessageResponse\n" +
  "import p4.v1.p4runtime.Action.Param"
)

// === Match types ===
def genMatchFieldArg(mf : MatchField) : Either[String, String] =
  mf.`match`.matchType match
    case None => Left("Failure: Match field has no type.")
    case Some(tp) =>
      tp match
        case EXACT    => Right("\"" + mf.name + "\", Exact")
        case LPM      => Right("Option[(\"" + mf.name + "\", LPM)]")
        case RANGE    => Right("Option[(\"" + mf.name + "\", Range)]")
        case TERNARY  => Right("Option[(\"" + mf.name + "\", Ternary)]")
        case OPTIONAL => Right("Option[(\"" + mf.name + "\", Optional)]")
        case _ => Left("Failure: Invalid match field type")

def genMatchFieldArgs(mfs : Seq[MatchField]) : Either[String, String] = for {
  matchFieldArgs <- mapM(mfs, genMatchFieldArg)
} yield {
  if mfs.size > 0 then
    "(" + matchFieldArgs.reduce((a1, a2) => "(" + a1 + "), (" + a2 + ")") + ")"
  else
    "Unit"
}

def genTableMatchFields(tables : Seq[Table]) : Either[String, String] = for {
  tableMatchFields <- mapM[Table,(String, Seq[MatchField])](tables, t => {
    t.preamble.fold
      (ifEmpty = Left("Failure: Table has empty preamble."))
      (preamble => Right((preamble.name, t.matchFields)))
  })
  cases <- mapM(tableMatchFields, (tn, mfs) => for {
    args <- genMatchFieldArgs(mfs)
  } yield {
    "    case \"" + tn + "\" => " + args + " | \"*\""
  })
} yield {
  "type TableMatchFields[TN] =\n  TN match\n"
  + {
    if cases.size > 0 then
      cases.reduce((c1, c2) => c1 + "\n" + c2) + "\n"
    else
      ""
  }
  + "    case \"*\" => \"*\""
}

def genActions(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  actionNames <- mapM[P4InfoAction,String](actions, action => {
    action.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => Right("\"" + preamble.name + "\""))
  })
} yield {
  if actionNames.size > 0 then
    actionNames.reduce((s1, s2) => s1 + " | " + s2)
  else
    ""
}

def genActionName(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  actions <- genActions(actions)
} yield {
  "type ActionName = " + actions + " | \"*\"\n"
}

def genTableAction(tables : Seq[Table], actions : Seq[P4InfoAction]) : Either[String, String] = for {
  tableActions <- mapM[Table,(String, Seq[ActionRef])](tables, t => {
    t.preamble.fold
      (ifEmpty = Left("Failure: Table has empty preamble."))
      (preamble => Right(preamble.name, t.actionRefs))
  })
  actionIdNames <- mapM[P4InfoAction, (Int, String)](actions, a => {
    a.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => Right(preamble.id, preamble.name))
  })
  matchActionCases <- mapM(tableActions, (tn, ars) => {
    for {
      actionNames <- mapM(ars, ar => {
        actionIdNames.find((aid, an) => aid == ar.id) match
          case None => Left("Failure: Table has invaild action reference.")
          case Some((_, an)) => Right("\"" + an + "\"")
      })
    } yield {
      if actionNames.size > 0 then
        "    case \"" + tn + "\" => " + actionNames.reduce((an1, an2) => an1 + " | " + an2) + " | \"*\""
      else
        "    case \"" + tn + "\" => \"*\""
    }
  })
} yield {
  "type TableAction[TN] <: ActionName =\n  TN match\n"
  + matchActionCases.reduce((c1, c2) => c1 + "\n" + c2) + "\n"
  + "    case \"*\" => \"*\"\n"
}

def genActionParams(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  actionParams <- mapM[P4InfoAction, String](actions, a => {
    a.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => Right({
        val paramTypes = a.params.map(p => "(\"" + p.name + "\", ByteString)")
        if paramTypes.size > 0 then
          "    case \"" + preamble.name + "\" => (" + paramTypes.reduce((p1, p2) => p1 + ", " + p2) + ")"
        else
          "    case \"" + preamble.name + "\" => Unit"
      }))
  })
} yield {
  "type ActionParams[AN] =\n  AN match\n"
  + {
    if actionParams.size > 0 then
      actionParams.reduce((p1, p2) => p1 + "\n" + p2) + "\n"
    else
      "\n"
  }
  + "    case \"*\" => \"*\"\n"
}

def genMatchTypes(p4info : P4Info) : Either[String, String] = for {
  tableMatchFields <- genTableMatchFields(p4info.tables)
  actionName <- genActionName(p4info.actions)
  tableAction <- genTableAction(p4info.tables, p4info.actions)
  actionParams <- genActionParams(p4info.actions)
} yield {
  tableMatchFields + "\n" + actionName + "\n" + tableAction + "\n" + actionParams
}

// === Channel ===
def genTableToProto(tables : Seq[Table]) : Either[String, String] = for {
  tableCases <- mapM[Table, String](tables, t => {
    t.preamble.fold
      (ifEmpty = Left("Failure: Table has empty preamble."))
      (preamble => Right("        case \"" + preamble.name + "\" => " + preamble.id))
  })
} yield {
  "    val tableId =\n" +
  "      te.table match\n" +
  "        case \"*\" => 0\n" +
  {
    if tableCases.size > 0 then
      tableCases.reduce((c1, c2) => c1 + "\n" + c2) + "\n"
    else
      "\n"
  }
}

def genMatchFieldToProtoCase(table : String, mfs : Seq[MatchField]) : Either[String, String] = for {
  fieldsIndexed <- Right(mfs.zipWithIndex)
  caseVars <- mapM(fieldsIndexed, (mf,idx) => {
    mf.`match`.matchType match
      case None => Left("Failure: MatchField has no match type.")
      case Some(value) =>
        value match
          case EXACT => Right("(_, t" + idx + ")")
          case _ => Right("t" + idx)
  })
  caseResult <- mapM(fieldsIndexed, (mf,idx) => {
    mf.`match`.matchType match
      case None => Left("Failure: MatchField has no match type.")
      case Some(value) =>
        value match
          case EXACT => Right("Seq(p4rtype.matchFieldToProto(" + mf.id + ", t" + idx + ".asInstanceOf[Exact]))")
          case _ => for {
              caseVarType <- genMatchFieldArg(mf)
            } yield {
              "t" + idx + ".asInstanceOf[" + caseVarType + "].map((_, t) => p4rtype.matchFieldToProto(" + mf.id + ", t)).toSeq"
            }
  })
} yield {
  if caseVars.size > 0 && caseResult.size > 0 then
    "        case (\"" + table + "\", (" + caseVars.reduce((v1, v2) => v1 + ", " + v2) + ")) => " + caseResult.reduce((v1, v2) => v1 + " ++ " + v2)
  else
    "        case (\"" + table + "\", _) => Seq.empty"
}

def genMatchFieldsToProto(tables : Seq[Table]) : Either[String, String] = for {
  tableMatches <- mapM[Table, String](tables, t => {
    t.preamble.fold
      (ifEmpty = Left("Failure: Table has empty preamble."))
      (preamble => genMatchFieldToProtoCase(preamble.name, t.matchFields))
  })
} yield {
  "    val matchFields =\n" +
  "      (te.table, te.matches) match\n" +
  "        case (\"*\", _) => Seq.empty\n" +
  "        case (_, _ : \"*\") => Seq.empty\n" +
  {
    if tableMatches.size > 0 then
      tableMatches.reduce((s1, s2) => s1 + "\n" + s2) + "\n"
    else
      "\n"
  }
}

def genActionToProto(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  actionCases <- mapM(actions, a => {
    a.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => Right("        case \"" + preamble.name + "\" => " + preamble.id))
  })
} yield {
  "    val actionId =\n" +
  "      te.action match\n" +
  "        case \"*\" => 0\n" +
  {
    if actionCases.size > 0 then
      actionCases.reduce((s1, s2) => s1 + "\n" + s2) + "\n"
    else
      "\n"
  }
}

def genParamsToProto(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  paramCases <- mapM(actions, a => {
    a.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => {
        val paramsIndexed = a.params.zipWithIndex
        if paramsIndexed.size > 0 then
          val paramTypes = a.params
            .map(p => "(\"" + p.name + "\", ByteString)")
            .reduce((p1, p2) => p1 + ", " + p2)
          val paramCaseVars = paramsIndexed
            .map((p,idx) => "(\"" + p.name + "\", p" + idx + ")")
            .reduce((p1, p2) => p1 + ", " + p2)
          val paramCaseResult = paramsIndexed
            .map((p,idx) => "Seq(Param(paramId = " + p.id + ", value = p" + idx + "))")
            .reduce((p1, p2) => p1 + " ++ " + p2)
          Right("        case (\"" + preamble.name + "\", (" + paramCaseVars + ") : (" + paramTypes + ")) => " + paramCaseResult)
        else
          Right("        case (\"" + preamble.name + "\", _) => Seq.empty")
      })
  })
} yield {
  "    val params =\n" +
  "      (te.action, te.params) match\n" +
  "        case (\"*\", _) => Seq.empty\n" +
  {
    if paramCases.size > 0 then
      paramCases.reduce((s1, s2) => s1 + "\n" + s2) + "\n"
    else
      "\n"
  }
}

def genToProto(p4info : P4Info) : Either[String, String] = for {
  table <- genTableToProto(p4info.tables)
  matchFields <- genMatchFieldsToProto(p4info.tables)
  action <- genActionToProto(p4info.actions)
  params <- genParamsToProto(p4info.actions)
} yield {
  "  override def toProto(te : p4rtype.TableEntry[TableMatchFields, TableAction, ActionParams, _, _]) : TableEntry =\n" +
  table + "\n" +
  matchFields + "\n" +
  action + "\n" +
  params + "\n" +
  "    TableEntry(\n" +
  "    tableId = tableId,\n" +
  "    `match` = matchFields,\n" +
  "    action =\n" +
  "      if actionId != 0 then\n" +
  "        Some(TableAction(\n" +
  "          `type` = TableAction.Type.Action(\n" +
  "            value = Action(\n" +
  "              actionId = actionId,\n" +
  "              params = params\n" +
  "            )\n" +
  "          )\n" +
  "        ))\n" +
  "      else\n" +
  "        None\n" +
  "  )\n"
}

def genTableFromProto(tables : Seq[Table]) : Either[String, String] = for {
  tableCases <- mapM[Table, String](tables, t => {
    t.preamble.fold
      (ifEmpty = Left("Failure: Table has empty preamble."))
      (preamble => Right("        case " + preamble.id + " => \"" + preamble.name + "\""))
  })
} yield {
  "    val table =\n" +
  "      te.tableId match\n" +
  {
    if tableCases.size > 0 then
      tableCases.reduce((c1, c2) => c1 + "\n" + c2) + "\n"
    else
      "\n"
  } +
  "        case 0 => \"*\""
}

def genMatchFieldFromProtoCase(tableId : Int, mfs : Seq[MatchField]) : Either[String, String] = for {
  matchFields <- mapM(mfs, mf => {
    mf.`match`.matchType.get match
      case EXACT    => Right("te.`match`.find(_.fieldId == " + mf.id + ").map(fm => (\"" + mf.name + "\", Exact(fm.fieldMatchType.exact.get.value))).get")
      case LPM      => Right("te.`match`.find(_.fieldId == " + mf.id + ").map(fm => (\"" + mf.name + "\", LPM(fm.fieldMatchType.lpm.get.value, fm.fieldMatchType.lpm.get.prefixLen)))")
      case RANGE    => Right("te.`match`.find(_.fieldId == " + mf.id + ").map(fm => (\"" + mf.name + "\", Range(fm.fieldMatchType.range.get.low, fm.fieldMatchType.range.get.high)))")
      case TERNARY  => Right("te.`match`.find(_.fieldId == " + mf.id + ").map(fm => (\"" + mf.name + "\", Ternary(fm.fieldMatchType.ternary.get.value, fm.fieldMatchType.ternary.get.mask)))")
      case OPTIONAL => Right("te.`match`.find(_.fieldId == " + mf.id + ").map(fm => (\"" + mf.name + "\", Optional(fm.fieldMatchType.optional.get.value)))")
      case _ => Left("Failure: Unknown match type.")
  })
} yield {
  if matchFields.size > 0 then
    "        case " + tableId + " => (" + matchFields.reduce((s1, s2) => s1 + ", " + s2) + ")"
  else
    "        case " + tableId + " => ()"
}

def genMatchFieldsFromProto(tables : Seq[Table]) : Either[String, String] = for {
  tableMatches <- mapM[Table, String](tables, t => {
    t.preamble.fold
      (ifEmpty = Left("Failure: Table has empty preamble."))
      (preamble => genMatchFieldFromProtoCase(preamble.id, t.matchFields))
  })
} yield {
  "    val matches =\n" +
  "      te.tableId match\n" +
  {
    if tableMatches.size > 0 then
      tableMatches.reduce((s1, s2) => s1 + "\n" + s2) + "\n"
    else
      "\n"
  } +
  "        case 0 => \"*\""
}

def genActionFromProto(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  actionCases <- mapM(actions, a => {
    a.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => Right("        case " + preamble.id + " => \"" + preamble.name + "\""))
  })
} yield {
  "    val action =\n" +
  "      actionId match\n" +
  {
    if actionCases.size > 0 then
      actionCases.reduce((s1, s2) => s1 + "\n" + s2) + "\n"
    else
      "\n"
  } +
  "        case 0 => \"*\""
}

def genParamsFromProtoCase(actionId : Int, prms : Seq[Param]) : Either[String, String] = for {
  params <- mapM(prms, p => Right(
      "teParams.find(_.paramId == " + p.id + ").map(pm => (\"" + p.name + "\", pm.value)).get"
  ))
} yield {
  if params.size > 0 then
    "        case " + actionId + " => (" + params.reduce((s1, s2) => s1 + ", " + s2) + ")"
  else
    "        case " + actionId + " => ()"
}

def genParamsFromProto(actions : Seq[P4InfoAction]) : Either[String, String] = for {
  actionMatches <- mapM(actions, a => {
    a.preamble.fold
      (ifEmpty = Left("Failure: Action has empty preamble."))
      (preamble => genParamsFromProtoCase(preamble.id, a.params))
  })
} yield {
  "    val params =\n" +
  "      actionId match\n" +
  {
    if actionMatches.size > 0 then
      actionMatches.reduce((s1, s2) => s1 + "\n" + s2) + "\n"
    else
      "\n"
  } +
  "        case 0 => \"*\""
}

def genFromProto(p4info : P4Info) : Either[String, String] = for {
  table <- genTableFromProto(p4info.tables)
  matchFields <- genMatchFieldsFromProto(p4info.tables)
  action <- genActionFromProto(p4info.actions)
  params <- genParamsFromProto(p4info.actions)
} yield {
  "  override def fromProto[TM[_], TA[_], TP[_], XN <: String, XA <: TA[XN]](te : TableEntry): p4rtype.TableEntry[TM, TA, TP, XN, XA] =\n" +
  "    val actionId = te.action.get.`type`.action.get.actionId\n" +
  "    val teParams = te.action.get.`type`.action.get.params\n\n" +
  table + "\n" +
  matchFields + "\n" +
  action + "\n" +
  params + "\n" +
  "    val myTable : XN = table.asInstanceOf[XN]\n" +
  "    val myAction : TA[myTable.type] = action.asInstanceOf[TA[myTable.type]]\n" +
  "    p4rtype.TableEntry[TM, TA, TP](\n" +
  "      table = myTable,\n" +
  "      matches = matches.asInstanceOf[TM[myTable.type]],\n" +
  "      action = myAction,\n" +
  "      params = params.asInstanceOf[TP[myAction.type]],\n" +
  "      1\n" +
  "    ).asInstanceOf[p4rtype.TableEntry[TM, TA, TP, XN, XA]]\n"
}

def genChannel(p4info : P4Info) : Either[String, String] = for {
  toProto <- genToProto(p4info)
  fromProto <- genFromProto(p4info)
} yield {
  "class Chan (deviceId : Int, socket : P4RuntimeStub, channel : io.grpc.ManagedChannel) extends p4rtype.Chan[TableMatchFields, TableAction, ActionParams](deviceId, socket, channel):\n" +
  toProto + "\n" +
  fromProto
}
// === `connect` API ===
def genConnect(p4info : P4Info) : Either[String, String] = Right(
  "/** Connect to a P4Runtime server.\n" +
  "  * @param id The device ID, which is assigned by the controller (i.e. the caller), and should be unique for each controller.\n" +
  "  * @param ip IP address of the target device.\n" +
  "  * @param port Port number of the target device.\n" +
  "  * @return A `Chan` object used by the other P4R-Type API functions for communication.\n" +
  "  */\n" +
  "def connect(id : Int, ip : String, port : Int) : Chan =\n" +
  "  val channel = ManagedChannelBuilder.forAddress(ip, port).usePlaintext().build()\n" +
  "  val request = StreamMessageRequest(\n" +
  "      StreamMessageRequest.Update.Arbitration(\n" +
  "        value = MasterArbitrationUpdate(\n" +
  "          deviceId = id,\n" +
  "          electionId = Some(Uint128(high=0,low=1)),\n" +
  "        )\n" +
  "      )\n" +
  "    )\n" +
  "  val stub = P4RuntimeStub.newStub(channel, CallOptions.DEFAULT)\n" +
  "  val response_obs = new P4RTypeRuntimeObserver[StreamMessageResponse](StreamMessageResponse())\n" +
  "  val request_obs = stub.streamChannel(response_obs)\n" +
  "  request_obs.onNext(request)\n" +
  "  Chan(id, stub, channel)\n\n"
)

def genP4Info(p4info : P4Info) : Either[String, String] =
  for {
    imports <- genImports()
    matchTypes <- genMatchTypes(p4info)
    channel <- genChannel(p4info)
    connect <- genConnect(p4info)
  } yield {
    imports + "\n\n" +
    matchTypes + "\n" +
    channel + "\n" +
    connect + "\n"
  }

/** Library entry point: p4info JSON in, Scala 3 source out.
  *
  * This is the API a build tool should call. `typegen` previously only existed
  * as a main that printed to stdout, which forced consumers (e.g. QuackMPP's
  * Mill build) to shell out and redirect. Callers that want the types on disk
  * can write the returned String wherever they like.
  *
  * @param p4infoJson the contents of a p4info file in the JSON form emitted by
  *                   `p4c --target bmv2 --arch v1model --p4runtime-files`
  * @param packageName the package to declare in the generated source
  * @return the generated Scala source, or a description of what went wrong
  */
/** Parses p4c's p4info JSON into the ScalaPB `P4Info` message.
  *
  * Uses protobuf-java's JsonFormat (the reference implementation of the protobuf
  * JSON mapping p4c emits) via a DynamicMessage built from ScalaPB's own
  * javaDescriptor, then re-parses the binary form into the Scala message. This
  * deliberately avoids scalapb-json4s, whose only 1.0-line release
  * (1.0.0-alpha.1) pins scalapb-runtime 1.0.0-alpha.1 and forced a cross-alpha
  * eviction override on the whole build. protobuf-java-util is a plain Java
  * artifact with no Scala cross-version and a stable release matching the
  * protobuf-java that scalapb-runtime already pulls. See UPGRADE.md §3.
  */
private def parseP4infoJson(p4infoJson : String) : Either[String, P4Info] =
  try
    val builder = com.google.protobuf.DynamicMessage.newBuilder(P4Info.javaDescriptor)
    com.google.protobuf.util.JsonFormat.parser().merge(p4infoJson, builder)
    Right(P4Info.parseFrom(builder.build().toByteArray))
  catch case e : Throwable => Left("Failure: could not parse p4info JSON: " + e.getMessage)

def generate(p4infoJson : String, packageName : String) : Either[String, String] =
  for {
    p4info <- parseP4infoJson(p4infoJson)
    output <- genP4Info(p4info)
  } yield "package " + packageName + "\n\n" + output

/** CLI wrapper around [[generate]].
  *
  * With two arguments it prints to stdout, as it always has (README documents
  * that). With an optional third it writes the file itself.
  *
  * The file mode exists because "print to stdout and redirect" is not actually
  * usable: sbt interleaves its own log lines into stdout and colourises them in
  * CI, so `sbt "runMain ..." > Foo.scala` yields a file with `[info]` lines and
  * ANSI escapes in it. `--error` does not help — the thin client still prints.
  * Every attempt to filter that back out has been a bug (see the drift check's
  * history), so the program writes the bytes instead of asking a shell to
  * salvage them.
  */
object parseP4info {
  def main(args : Array[String]) : Unit =
    if args.length < 2 || args.length > 3 then
      System.err.println(
        "usage: sbt \"runMain typegen.parseP4info <p4info.json> <package-name> [output.scala]\"\n" +
        "       with no output path the generated package is written to stdout"
      )
      System.exit(1)
    else
      val source =
        try Right(fromFile(System.getProperty("user.dir") + "/" + args(0)).mkString)
        catch case e : Throwable => Left("Failure: could not read " + args(0) + ": " + e.getMessage)

      source.flatMap(generate(_, args(1))) match
        case Left(err)  =>
          System.err.println(err)
          System.exit(1)
        case Right(out) =>
          if args.length == 2 then print(out)
          else
            // resolve, not Path.of(dir, arg): Path.of JOINS, so an absolute
            // output path would be silently rebased under the project directory
            // (/tmp/Foo.scala -> <P4R-Type>/tmp/Foo.scala). resolve returns the
            // argument unchanged when it is already absolute.
            val path = java.nio.file.Path.of(System.getProperty("user.dir")).resolve(args(2))
            try
              java.nio.file.Files.writeString(path, out)
              System.err.println("wrote " + path)
            catch case e : Throwable =>
              System.err.println(
                "Failure: could not write " + path + ": " +
                e.getClass.getSimpleName + ": " + e.getMessage
              )
              System.exit(1)
}