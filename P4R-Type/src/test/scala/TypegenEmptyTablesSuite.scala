// Named package, for the same reason QuackMppTypegenSuite is: `generate` is the
// downstream entry point, and only a named package can import it the way a real
// consumer does.
package p4rtype.consumertest

import typegen.generate

/** Regression: typegen must not crash on a table-less p4info.
  *
  * A p4info with no tables is a real p4c output: compile a program whose only
  * P4Runtime object is a counter (e.g. nsg-ethz/p4-learning's
  * examples/counter/indirect_counter.p4) and p4c emits `{counters, pkgInfo,
  * typeInfo}` with `tables` and `actions` absent. `counter_only.p4info.json` is
  * the minimal form of that: one counter, no tables, no actions.
  *
  * This started as a repro. `genTableAction` reduced `matchActionCases` with no
  * empty guard, while its three siblings — genTableMatchFields, genActionName,
  * genActionParams — all guard `if size > 0`. With zero tables the sequence was
  * empty and `.reduce` threw `UnsupportedOperationException: empty.reduceLeft`
  * before any `Either` was produced, so the exception escaped `generate` entirely
  * instead of surfacing as a `Left`. The guard is now in place; with no tables the
  * generated `TableAction` degrades to just the `case "*"` arm, and `generate`
  * returns a `Right`.
  *
  * The contract this pins is minimal and design-neutral: `generate` must return —
  * `Left` or `Right`, either is fine — and must never let an exception escape its
  * `Either`.
  */
class TypegenEmptyTablesSuite extends munit.FunSuite {

  test("a table-less p4info returns via Either, never throws") {
    val json = scala.io.Source.fromResource("counter_only.p4info.json").mkString

    val result =
      try generate(json, "counteronly")
      catch
        case e: Throwable =>
          fail(
            s"typegen threw ${e.getClass.getName}: ${e.getMessage} — its Either " +
            s"API must not leak exceptions on a table-less p4info"
          )

    // Reaching here means it returned rather than threw. Either outcome is
    // acceptable; the bug is solely the escaping exception.
    assert(
      result.isLeft || result.isRight,
      s"unreachable unless generate returned a non-Either: $result"
    )
  }
}
