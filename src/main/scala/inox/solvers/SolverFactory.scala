/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package solvers

trait SolverFactory {
  val program: Program

  type S <: Solver { val program: SolverFactory.this.program.type }

  val name: String

  def getNewSolver(): S

  def shutdown(): Unit = {}

  def reclaim(s: S) {
    s.free()
  }

  def toAPI = SimpleSolverAPI(this)
}

object SolverFactory {

  lazy val hasNativeZ3 = try {
    _root_.z3.Z3Wrapper.withinJar()
    true
  } catch {
    case _: java.lang.UnsatisfiedLinkError => false
    case _: java.lang.NoClassDefFoundError => false
  }

  import _root_.smtlib.interpreters._

  lazy val hasZ3 = try {
    new Z3Interpreter("z3", Array("-in", "-smt2"))
    true
  } catch {
    case _: java.io.IOException => false
  }

  lazy val hasCVC4 = try {
    new CVC4Interpreter("cvc4", Array("-q", "--lang", "smt2.5"))
    true
  } catch {
    case _: java.io.IOException => false
  }

  def create[S1 <: Solver](p: Program)(nme: String, builder: () => S1 { val program: p.type }):
           SolverFactory { val program: p.type; type S = S1 { val program: p.type } } = {
    new SolverFactory {
      val program: p.type = p
      type S = S1 { val program: p.type }

      val name = nme
      def getNewSolver() = builder()
    }
  }

  import evaluators._
  import combinators._
  import unrolling._

  val solverNames = Map(
    "nativez3" -> "Native Z3 with z3-templates for unrolling",
    "unrollz3" -> "Native Z3 with inox-templates for unrolling",
    "smt-cvc4" -> "CVC4 through SMT-LIB",
    "smt-z3"   -> "Z3 through SMT-LIB",
    "princess" -> "Princess with inox unrolling"
  )

  private val fallbacks = Map(
    "nativez3" -> (() => hasNativeZ3, Seq("smt-z3", "smt-cvc4",   "princess"), "Z3 native interface"),
    "unrollz3" -> (() => hasNativeZ3, Seq("smt-z3", "smt-cvc4",   "princess"), "Z3 native interface"),
    "smt-cvc4" -> (() => hasCVC4,     Seq("nativez3", "smt-z3",   "princess"), "'cvc4' binary"),
    "smt-z3"   -> (() => hasZ3,       Seq("nativez3", "smt-cvc4", "princess"), "'z3' binary"),
    "princess" -> (() => true,        Seq(),                                   "Princess solver")
  )

  private var reported: Boolean = false

  def getFromName(name: String, force: Boolean = false)
                 (p: Program, opts: Options)
                 (enc: ast.ProgramTransformer {
                    val sourceProgram: p.type
                    val targetProgram: Program { val trees: inox.trees.type }
                  })(implicit sem: p.Semantics): SolverFactory { val program: p.type; type S <: TimeoutSolver { val program: p.type } } = {

    val finalName = if (force) {
      name 
    } else {
      fallbacks.get(name) match {
        case Some((guard, names, requirement)) if !guard() =>
          val replacement = names.collectFirst { case name if fallbacks(name)._1() => name }.get
          if (!reported) {
            p.ctx.reporter.warning(s"The $requirement is not available. Falling back onto $replacement.")
            reported = true
          }
          replacement

        case Some(_) => name

        case _ => throw FatalError("Unknown solver: " + name)
      }
    }

    finalName match {
      case "nativez3" => create(p)(finalName, () => new {
        val program: p.type = p
        val options = opts
        val encoder: enc.type = enc
      } with z3.NativeZ3Solver with TimeoutSolver with tip.TipDebugger {
        val semantics = sem
        lazy val chooses = ChooseEncoder(p)(encoder)
        lazy val targetSemantics: targetProgram.Semantics = targetProgram.getSemantics
      })

      case "unrollz3" => create(p)(finalName, () => new {
        val program: p.type = p
        val options = opts
        val encoder: enc.type = enc
      } with UnrollingSolver with theories.Z3Theories with TimeoutSolver with tip.TipDebugger {
        val semantics = sem
        lazy val chooses = ChooseEncoder(p)(encoder)
        lazy val targetSemantics: targetProgram.Semantics = targetProgram.getSemantics

        object underlying extends {
          val program: targetProgram.type = targetProgram
          val options = opts
        } with z3.UninterpretedZ3Solver {
          lazy val semantics: targetProgram.Semantics = targetSemantics
        }
      })

      case "smt-cvc4" => create(p)(finalName, () => new {
        val program: p.type = p
        val options = opts
        val encoder: enc.type = enc
      } with UnrollingSolver with theories.CVC4Theories with TimeoutSolver with tip.TipDebugger {
        val semantics = sem
        lazy val chooses = ChooseEncoder(p)(encoder)
        lazy val targetSemantics: targetProgram.Semantics = targetProgram.getSemantics

        object underlying extends {
          val program: targetProgram.type = targetProgram
          val options = opts
        } with smtlib.CVC4Solver {
          lazy val semantics: targetProgram.Semantics = targetSemantics
        }
      })

      case "smt-z3" => create(p)(finalName, () => new {
        val program: p.type = p
        val options = opts
        val encoder: enc.type = enc
      } with UnrollingSolver with theories.Z3Theories with TimeoutSolver with tip.TipDebugger {
        val semantics = sem
        lazy val chooses = ChooseEncoder(p)(encoder)
        lazy val targetSemantics: targetProgram.Semantics = targetProgram.getSemantics

        object underlying extends {
          val program: targetProgram.type = targetProgram
          val options = opts
        } with smtlib.Z3Solver {
          lazy val semantics: targetProgram.Semantics = targetSemantics
        }
      })

      case "princess" => create(p)(finalName, () => new {
        val program: p.type = p
        val options = opts
        val encoder: enc.type = enc
      } with princess.PrincessSolver with TimeoutSolver {
        val semantics = sem
        lazy val chooses = ChooseEncoder(p)(encoder)
        lazy val targetSemantics: targetProgram.Semantics = targetProgram.getSemantics
      })

      case _ => throw FatalError("Unknown solver: " + finalName)
    }
  }

  val solvers: Set[String] = solverNames.map(_._1).toSet

  def getFromSettings(p: Program, opts: Options)
                     (enc: ast.ProgramTransformer {
                        val sourceProgram: p.type
                        val targetProgram: Program { val trees: inox.trees.type }
                      })(implicit sem: p.Semantics): SolverFactory { val program: p.type; type S <: TimeoutSolver { val program: p.type } } = {
    opts.findOption(optSelectedSolvers) match {
      case None => optSelectedSolvers.default.toSeq match {
        case Seq() => throw FatalError("No selected solver")
        case Seq(single) => getFromName(single, force = false)(p, opts)(enc)
        case multiple => PortfolioSolverFactory(p) {
          multiple.map(name => getFromName(name, force = false)(p, opts)(enc))
        }
      }

      case Some(set) => set.toSeq match {
        case Seq() => throw FatalError("No selected solver")
        case Seq(single) => getFromName(single, force = true)(p, opts)(enc)
        case multiple => PortfolioSolverFactory(p) {
          multiple.map(name => getFromName(name, force = true)(p, opts)(enc))
        }
      }
    }
  }

  def apply(name: String, p: InoxProgram, opts: Options, force: Boolean = false): SolverFactory {
    val program: p.type
    type S <: TimeoutSolver { val program: p.type }
  } = getFromName(name, force = force)(p, opts)(ast.ProgramEncoder.empty(p))(p.getSemantics)

  def apply(p: InoxProgram, opts: Options): SolverFactory {
    val program: p.type
    type S <: TimeoutSolver { val program: p.type }
  } = opts.findOption(optSelectedSolvers) match {
    case None => optSelectedSolvers.default.toSeq match {
      case Seq() => throw FatalError("No selected solver")
      case Seq(single) => apply(single, p, opts, force = false)
      case multiple => PortfolioSolverFactory(p) {
        multiple.map(name => apply(name, p, opts, force = false))
      }
    }

    case Some(set) => set.toSeq match {
      case Seq() => throw FatalError("No selected solver")
      case Seq(single) => apply(single, p, opts, force = true)
      case multiple => PortfolioSolverFactory(p) {
        multiple.map(name => apply(name, p, opts, force = true))
      }
    }
  }

  def default(p: InoxProgram): SolverFactory {
    val program: p.type
    type S <: TimeoutSolver { val program: p.type }
  } = apply(p, p.ctx.options)
}
