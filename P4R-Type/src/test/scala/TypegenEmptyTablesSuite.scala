// Named package, for the same reason QuackMppTypegenSuite is: `generate` is the
// downstream entry point, and only a named package can import it the way a real
// consumer does.
package p4rtype.consumertest

import typegen.generate

/** Regression: typegen must produce compilable output for a table-less p4info,
  * not just avoid crashing on it.
  *
  * A p4info with no tables is a real p4c output: compile a program whose only
  * P4Runtime object is a counter (e.g. nsg-ethz/p4-learning's
  * examples/counter/indirect_counter.p4) and p4c emits `{counters, pkgInfo,
  * typeInfo}` with `tables` and `actions` absent. `counter_only.p4info.json` is
  * the minimal form of that: one counter, no tables, no actions.
  *
  * This started as a crash — `genTableAction` reduced an empty `matchActionCases`
  * and threw `UnsupportedOperationException: empty.reduceLeft` out of the
  * Either-typed API. Guarding that reduce stopped the throw but was not enough:
  * with no actions `genActionName` still emitted `type ActionName =  | "*"`, a
  * union with an empty left operand and thus a Scala 3 syntax error — so
  * `generate` returned a `Right` whose source the downstream build could not
  * compile. Both are now guarded, so the emitted `ActionName`/`TableAction`
  * degrade to just their `"*"` arms.
  *
  * Two mechanisms work together here, and both are needed:
  *
  *  - `counter_only.scala` is the committed generated source; it sits under
  *    src/test/scala and is compiled by this module exactly as
  *    `quackmpp_exchange.scala` and `matchkinds.scala` are. That compile is what
  *    proves the *current* emission is valid Scala.
  *  - The drift check below re-runs `generate` and asserts its output still equals
  *    that committed file. That is what catches a *future* typegen change: the
  *    committed .scala is static, so a regression that emits non-compiling source
  *    leaves it compiling untouched — the signal comes from the drift test failing
  *    at test time, not from the build.
  *
  * Neither alone suffices, so do not drop the drift check and lean on the
  * committed file, nor vice versa.
  */
class TypegenEmptyTablesSuite extends munit.FunSuite {

  test("a table-less p4info yields a Right (never throws)") {
    val json = scala.io.Source.fromResource("counter_only.p4info.json").mkString

    val result =
      try generate(json, "counteronly")
      catch
        case e: Throwable =>
          fail(
            s"typegen threw ${e.getClass.getName}: ${e.getMessage} — its Either " +
            s"API must not leak exceptions on a table-less p4info"
          )

    assert(result.isRight, s"expected a Right for a table-less p4info, got: $result")
  }

  test("typegen output matches the committed counter_only.scala") {
    TypegenDrift.check(
      "counter_only.p4info.json", "src/test/scala/counter_only.scala", "counteronly"
    )
  }
}
