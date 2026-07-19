package p4rtype

import p4.v1.p4runtime.FieldMatch
import p4.v1.p4runtime.TableAction
import com.google.protobuf.ByteString
import p4.v1.p4runtime.FieldMatch.FieldMatchType
import p4.v1.p4runtime.Action
import p4.v1.p4runtime.Action.Param
import p4.v1.p4runtime.P4RuntimeGrpc.P4RuntimeStub
import io.grpc.stub.StreamObserver
import p4.v1.p4runtime.ReadRequest
import p4.v1.p4runtime.ReadResponse
import p4.v1.p4runtime.Entity
import p4.v1.p4runtime.WriteRequest
import p4.v1.p4runtime.Update
import p4.v1.p4runtime.Uint128
import p4.v1.p4runtime.SetForwardingPipelineConfigRequest
import p4.v1.p4runtime.GetForwardingPipelineConfigRequest
import p4.v1.p4runtime.ForwardingPipelineConfig
import p4.config.v1.p4info.P4Info
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.SECONDS
import io.grpc.ManagedChannel

type Wildcard = "*"

/** The match-field values, canonicalised at construction.
  *
  * Each constructor is private and each companion `apply` runs its binary
  * strings through [[canonical]], so a value is canonical from the moment it
  * exists. This is what makes `Exact(bytes(0, 7)) == Exact(bytes(7))` and gives
  * P4Runtime read-write symmetry at the *API* level, not just on the wire: a
  * controller can diff a read-back entry against the entry it intended and get
  * an answer about the switch rather than about encoding length.
  *
  * `copy` is private as a consequence, so it cannot be used to reintroduce a
  * non-canonical value. Pattern matching is unaffected — `unapply` stays public.
  *
  * Action parameters get no such treatment: `typegen` emits them as bare
  * `ByteString`s inside tuples, with no type to hang this on, so they are
  * canonicalised in the generated `toProto` instead. See ARCHITECTURE.md §7.
  */
case class Exact private (v : ByteString)
object Exact:
  def apply(v : ByteString) : Exact = new Exact(canonical(v))

case class LPM private (v : ByteString, pl : Int)
object LPM:
  def apply(v : ByteString, pl : Int) : LPM = new LPM(canonical(v), pl)

case class Range private (l : ByteString, h : ByteString)
object Range:
  def apply(l : ByteString, h : ByteString) : Range = new Range(canonical(l), canonical(h))

case class Ternary private (v : ByteString, m : ByteString)
object Ternary:
  def apply(v : ByteString, m : ByteString) : Ternary = new Ternary(canonical(v), canonical(m))

case class Optional private (v : ByteString)
object Optional:
  def apply(v : ByteString) : Optional = new Optional(canonical(v))

type MatchFieldType = Exact | LPM | Range | Ternary | Optional

/** Converts a binary string to P4Runtime's canonical representation: "the
  * shortest string that fits the encoded integer value".
  *
  * P4Runtime represents P4 integers as `bytes`, and a value has many valid
  * encodings -- 0x00,0x63 and 0x63 are both a valid `bit<16>` 99, and a
  * receiver accepts either (the spec puts no maximum length on a received
  * string). But only the shortest has **read-write symmetry**: servers reply
  * with the canonical form, so a client that writes a longer one reads back
  * something different from what it wrote. The spec's table of valid
  * encodings marks `bit<16>` 99 as 0x00,0x63 -> symmetry "no"; 0x63 -> "yes".
  *
  * '''Where this is applied.''' At construction, by the [[Exact]] / [[LPM]] /
  * [[Range]] / [[Ternary]] / [[Optional]] companions, so match-field values are
  * canonical from the moment they exist and `Exact(bytes(0, 7))` ''is'' an
  * `Exact(bytes(7))`. That is what makes read-write symmetry hold at the API
  * level: `fromProto` rebuilds through the same companions, so an entry read
  * back from a switch compares equal to the entry that was written.
  *
  * It stays public because '''action parameters cannot be covered this way''' —
  * `typegen` emits them as bare `ByteString`s inside tuples, with no constructor
  * to intercept. They are canonicalised in the generated `toProto` on the way
  * out, so the wire is right, but a controller diffing action params against its
  * own intent must call this itself.
  *
  * Unsigned only, which is all this is used for: the spec excludes `int<W>` from
  * table key fields and action parameters in P4Runtime v1, so there is no sign
  * extension to undo. Zero encodes as a single `0x00`, not the empty string —
  * the spec defines zero as needing one bit (hence one byte), and "if the
  * string's byte length is zero, the server always rejects the string". That
  * applies to an empty input too: `bytes()` maps to `bytes(0)`, not back to
  * itself, so this never emits the encoding the spec says is always rejected.
  */
def canonical(v : ByteString) : ByteString =
  val bs = v.toByteArray
  if bs.isEmpty then return ByteString.copyFrom(Array(0.toByte))
  var i = 0
  while i < bs.length - 1 && bs(i) == 0.toByte do i += 1
  if i == 0 then v else ByteString.copyFrom(bs, i, bs.length - i)

/** Note the absence of `canonical` calls here.
  *
  * They used to wrap every field, and became unreachable once the companions
  * above started canonicalising at construction: a `MatchFieldType` is one of
  * five case classes whose constructors are all private, so there is no way to
  * hand this function a non-canonical value.
  *
  * They are removed rather than kept as belt-and-braces, because keeping them
  * would be worse than redundant. If construction canonicalisation regressed, a
  * second pass here would quietly repair the wire — so every test that checks
  * what goes on the wire would still pass while the API-level invariant was
  * broken. One normalisation point, exercised by every wire test, beats two
  * where the outer one can hide a failure of the inner.
  */
def matchFieldToProto(fieldId : Int, mf : MatchFieldType) : FieldMatch =
  mf match
    case Exact(v)      => FieldMatch(fieldId, FieldMatchType.Exact(FieldMatch.Exact(value = v)))
    case LPM(v, pl)    => FieldMatch(fieldId, FieldMatchType.Lpm(FieldMatch.LPM(value = v, prefixLen = pl)))
    case Range(l, h)   => FieldMatch(fieldId, FieldMatchType.Range(FieldMatch.Range(low = l, high = h)))
    case Ternary(v, m) => FieldMatch(fieldId, FieldMatchType.Ternary(FieldMatch.Ternary(value = v, mask = m)))
    case Optional(v)   => FieldMatch(fieldId, FieldMatchType.Optional(FieldMatch.Optional(value = v)))

case class TableEntry [TM[_], TA[_], TP[_], XN, XA <: TA[XN]] private (table : XN, matches : TM[XN], action : XA, params : TP[XA], priority : Int)

/** Represents a table entry in a control plane table.
  */
object TableEntry:
  def apply[TM[_], TA[_], TP[_]] (table : String, matches : TM[table.type], action : TA[table.type], params : TP[action.type], priority : Int) : TableEntry[TM, TA, TP, table.type, action.type] =
    TableEntry[TM, TA, TP, table.type, action.type](table, matches, action, params, priority)

case class CounterEntry (counter_id : Int, index : Option[Long], data : Option[(Long, Long)])

type P4Entity[TM[_], TA[_], TP[_], XN, XA <: TA[XN]] = TableEntry[TM, TA, TP, XN, XA] | CounterEntry

/** Represents a connection channel to a target device.
  */
abstract class Chan[TM[_], TA[_], TP[_]] (deviceId : Int, socket : P4RuntimeStub, channel : ManagedChannel):
  /** Returns the device ID of the target device.
    * This is assigned by the controller (i.e. the caller),
    * and should be unique for each controller.
    */
  def getDeviceId() : Int = deviceId
  /** Returns the socket used to communicate with the target device.
    */
  def getSocket() : P4RuntimeStub = socket
  /** Converts a `TableEntry` from the P4R-Type representation
    * to the underlying protobuf representation.
    */
  def toProto(te : TableEntry[TM, TA, TP, _, _]) : p4.v1.p4runtime.TableEntry
  /** Converts a `CounterEntry` from the P4R-Type representation
    * to the underlying protobuf representation.
    */
  def toProto(c : CounterEntry) : p4.v1.p4runtime.CounterEntry =
    p4.v1.p4runtime.CounterEntry(
      counterId = c.counter_id,
      index = c.index.map(l => p4.v1.p4runtime.Index(l)),
      data = c.data.map((byteCount, packetCount) => p4.v1.p4runtime.CounterData(byteCount, packetCount))
  )
  /** Converts a `TableEntry` from the underlying protobuf representation
    * to the P4R-Type representation.
    */
  def fromProto[TM[_], TA[_], TP[_], XN <: String, XA <: TA[XN]](te : p4.v1.p4runtime.TableEntry) : TableEntry[TM, TA, TP, XN, XA]
  /** Converts a `CounterEntry` from the underlying protobuf representation
    * to the P4R-Type representation.
    */
  def fromProto(c : p4.v1.p4runtime.CounterEntry) : CounterEntry =
  CounterEntry(
    counter_id = c.counterId,
    index = c.index.map(i => i.index),
    data = c.data.map(d => (d.byteCount, d.packetCount))
  )
  /** Disconnects the channel, shutting down the connection to the target.
    */
  def disconnect() : Unit =
    channel.shutdownNow()

class P4RTypeRuntimeObserver[O](lock : Object) extends StreamObserver[O] {
  private var log : Seq[O] = Seq()
  def getLog() : Seq[O] = log
  override def onNext(value: O): Unit =
    log = log :+ value
  override def onCompleted(): Unit =
    lock.synchronized {
      lock.notify()
    }
  override def onError(t: Throwable): Unit =
    print("[ERROR] " + t.toString() + "\n")
}

/** Internal function that reads by matching entities with a 'querying' entity.
  *
  * @param c The channel used to communicate with the target device.
  * @param entity The entity to match on. For details on how the matching works, see the P4Runtime specification.
  * @return A list of entities that match the given entity.
  */
private def readAny[TM[_], TA[_], TP[_]]
  (c : Chan[TM, TA, TP], entity : P4Entity[TM, TA, TP, _, _]) : Seq[P4Entity[TM, TA, TP, _, _]] =
  val lock = Object()
  val read_observer = new P4RTypeRuntimeObserver[p4.v1.p4runtime.ReadResponse](lock)
  c.getSocket().read(
    request = ReadRequest(
      deviceId = c.getDeviceId(),
      entities = List(
        Entity (
          entity match
            case te : TableEntry[TM, TA, TP, _, _] => Entity.Entity.TableEntry(c.toProto(te))
            case ce : CounterEntry => Entity.Entity.CounterEntry(c.toProto(ce))
        )
      )
    ),
    read_observer
  )
  lock.synchronized {
    lock.wait()
  }
  read_observer.getLog().last.entities.foldLeft(List[P4Entity[TM, TA, TP, _, _]]())((acc, e) => {
    e.entity match
      case Entity.Entity.TableEntry(te) => acc :+ c.fromProto(te)
      case Entity.Entity.CounterEntry(ce) => acc :+ c.fromProto(ce)
      case _ => acc
  })

/** Reads the contents of a table by matching entries with a given table entry.
  *
  * @param c The channel used to communicate with the target device.
  * @param entity The table entry to match on. For details on how the matching works, see the P4Runtime specification.
  * @return A list of table entries that match the given table entry.
  */
def read[TM[_], TA[_], TP[_]]
  (c : Chan[TM, TA, TP], entity : TableEntry[TM, TA, TP, _, _]) : Seq[TableEntry[TM, TA, TP, _, _]] =
  readAny(c, entity).asInstanceOf[Seq[TableEntry[TM, TA, TP, _, _]]]

/** Reads the contents of one or more counter entries.
  *
  * @param c The channel used to communicate with the target device.
  * @param entity The 'querying' counter entry to match on. For details on how the matching works, see the P4Runtime specification.
  * @return A list of counter entries that match the given entry.
  */
def readCounterEntry
  (c : Chan[_, _, _], entity : CounterEntry) : Seq[CounterEntry] =
  readAny(c, entity).asInstanceOf[Seq[CounterEntry]]

/** Writes a table entry to a table.
  * API users should not call this method directly, but instead use the `insert`, `modify`, and `delete` methods.
  *
  * @param c The channel used to communicate with the target device.
  * @param tableEntry The table entry to write.
  * @param ut The type of the update. See the P4Runtime specification for a list and description of update types.
  * @return A boolean indicating whether or not the write was successful (true if successful, false otherwise).
  */
def write [TM[_], TA[_], TP[_]]
  (c : Chan[TM, TA, TP], entity : P4Entity[TM, TA, TP, _, _], ut : Update.Type) : Boolean =
  val resp = c.getSocket().write(WriteRequest(
    deviceId = c.getDeviceId(),
    electionId = Some(Uint128(high=0,low=1)),
    updates = List(Update(
      `type` = ut,
      entity = Some(Entity(
        entity match
          case te : TableEntry[TM, TA, TP, _, _] => Entity.Entity.TableEntry(c.toProto(te))
          case ce : CounterEntry => Entity.Entity.CounterEntry(c.toProto(ce))
      ))
    ))
  ))
  try
    val response = Await.ready(resp, FiniteDuration(10, SECONDS))
    response.value match
      case Some(scala.util.Success(_)) =>
        true
      case _ =>
        false
  catch
    case _ =>
      false

/** Installs a forwarding pipeline on the target.
  *
  * Until this existed, P4R-Type could read and write table entries but could not
  * put a pipeline on a switch, so something outside it had to (the mininet VM
  * does this out of band). A controller that regenerates its types from a
  * changed p4info needs to deploy that change too, not just compile against it.
  *
  * Unlike `write`, this returns the failure rather than a bare `false`. A
  * pipeline push is where a target reports what it actually objected to — a
  * p4info the switch rejects, a device config for the wrong target, an election
  * id that lost arbitration — and collapsing that to a boolean throws away the
  * only useful part.
  *
  * @param c The channel used to communicate with the target device.
  * @param p4info The P4Info, as produced by p4c's `--p4runtime-files`.
  * @param p4DeviceConfig The target-specific binary. For bmv2 this is the
  *   contents of the `.json` p4c writes alongside the p4info; other targets
  *   expect something else entirely.
  * @param action What the target should do with the config. Defaults to
  *   VERIFY_AND_COMMIT, i.e. check it and make it live.
  * @return Unit on success, or the target's error message.
  */
def setForwardingPipelineConfig
  (c : Chan[_, _, _],
   p4info : P4Info,
   p4DeviceConfig : ByteString,
   action : SetForwardingPipelineConfigRequest.Action =
     SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT) : Either[String, Unit] =
  val resp = c.getSocket().setForwardingPipelineConfig(
    SetForwardingPipelineConfigRequest(
      deviceId = c.getDeviceId(),
      // Must match the arbitration `connect` performed, or the target rejects
      // the push as coming from a non-primary controller.
      electionId = Some(Uint128(high = 0, low = 1)),
      action = action,
      config = Some(ForwardingPipelineConfig(
        p4Info = Some(p4info),
        p4DeviceConfig = p4DeviceConfig
      ))
    )
  )
  try
    Await.ready(resp, FiniteDuration(30, SECONDS)).value match
      case Some(scala.util.Success(_)) => Right(())
      case Some(scala.util.Failure(e)) => Left("SetForwardingPipelineConfig failed: " + e.getMessage)
      case None                        => Left("SetForwardingPipelineConfig timed out after 30s")
  catch
    case e : Throwable => Left("SetForwardingPipelineConfig failed: " + e.getMessage)

/** Reads back the forwarding pipeline currently installed on the target.
  *
  * Useful to confirm a push landed, and to discover what a switch is already
  * running.
  *
  * @param c The channel used to communicate with the target device.
  * @return The installed config, or the target's error message. A target with no
  *   pipeline installed reports that as an error (bmv2 answers FAILED_PRECONDITION).
  */
def getForwardingPipelineConfig
  (c : Chan[_, _, _]) : Either[String, ForwardingPipelineConfig] =
  val resp = c.getSocket().getForwardingPipelineConfig(
    GetForwardingPipelineConfigRequest(deviceId = c.getDeviceId())
  )
  try
    Await.ready(resp, FiniteDuration(30, SECONDS)).value match
      case Some(scala.util.Success(r)) =>
        r.config.toRight("target returned no config")
      case Some(scala.util.Failure(e)) => Left("GetForwardingPipelineConfig failed: " + e.getMessage)
      case None                        => Left("GetForwardingPipelineConfig timed out after 30s")
  catch
    case e : Throwable => Left("GetForwardingPipelineConfig failed: " + e.getMessage)

/** Inserts a table entry into a table.
  *
  * @param c The channel used to communicate with the target device.
  * @param tableEntry The table entry to insert. For details on how the operation works, see the P4Runtime specification.
  * @return A boolean indicating whether or not the insertion was successful (true if successful, false otherwise).
  */
def insert [TM[_], TA[_], TP[_]]
  (c : Chan[TM, TA, TP], entity : P4Entity[TM, TA, TP, _, _]) : Boolean =
  write(c, entity, Update.Type.INSERT)

/** Modifies a table entry in a table.
  *
  * @param c The channel used to communicate with the target device.
  * @param tableEntry The table entry to modify. For details on how the operation works, see the P4Runtime specification.
  * @return A boolean indicating whether or not the modification was successful (true if successful, false otherwise).
  */
def modify [TM[_], TA[_], TP[_]]
  (c : Chan[TM, TA, TP], entity : P4Entity[TM, TA, TP, _, _]) : Boolean =
  write(c, entity, Update.Type.MODIFY)

/** Deletes a table entry from a table.
  *
  * @param c The channel used to communicate with the target device.
  * @param tableEntry The table entry to delete. For details on how the operation works, see the P4Runtime specification.
  * @return A boolean indicating whether or not the deletion was successful (true if successful, false otherwise).
  */
def delete [TM[_], TA[_], TP[_]]
  (c : Chan[TM, TA, TP], entity : P4Entity[TM, TA, TP, _, _]) : Boolean =
  write(c, entity, Update.Type.DELETE)

/** A helper function for generating bytestrings from sequences of bytes.
  *
  * @param a A sequence of bytes (which may be represented as integers).
  * @return A bytestring containing the given bytes.
  */
def bytes (a : Byte*) : ByteString = ByteString.copyFrom(a.toArray)
