package cp

import scala.tools.nsc.transform.TypingTransformers
import scala.tools.nsc.ast.TreeDSL
import purescala.FairZ3Solver
import purescala.DefaultReporter
import purescala.Common.Identifier
import purescala.Definitions._
import purescala.Trees._

trait CallTransformation 
  extends TypingTransformers
  with CodeGeneration
  with TreeDSL
{
  self: CPComponent =>
  import global._
  import CODE._

  private lazy val cpPackage = definitions.getModule("cp")
  private lazy val cpDefinitionsModule = definitions.getModule("cp.Definitions")

  val reporter = purescala.Settings.reporter

  def transformCalls(unit: CompilationUnit, prog: Program, serializedProgString : String, serializedProgId : Int) : Unit =
    unit.body = new CallTransformer(unit, prog, serializedProgString, serializedProgId).transform(unit.body)
  
  class CallTransformer(unit: CompilationUnit, prog: Program, serializedProgString: String, serializedProgId : Int) extends TypingTransformer(unit) {
    var exprToScalaSym : Symbol = null
    var exprToScalaCastSym : Symbol = null
    var scalaToExprSym : Symbol = null

    override def transform(tree: Tree) : Tree = {
      tree match {
        case a @ Apply(TypeApply(Select(s: Select, n), typeTreeList), rhs @ List(predicate: Function)) if (cpDefinitionsModule == s.symbol && n.toString == "choose") => {
          val Function(funValDefs, funBody) = predicate

          val fd = extractPredicate(unit, funValDefs, funBody)

          val outputVars     = fd.args.map(_.id).toList

          reporter.info("Considering predicate:") 
          reporter.info(fd)

          val codeGen = new CodeGenerator(unit, currentOwner, tree.pos)

          fd.body match {
            case None => reporter.error("Could not extract choose predicate: " + funBody); super.transform(tree)
            case Some(b) =>
              // serialize expression
              val (exprString, exprId) = serialize(b)

              // retrieve program, expression
              val (progAssignment, progSym)                   = codeGen.assignProgram(serializedProgString, serializedProgId)
              val (exprAssignment, exprSym)                   = codeGen.assignExpr(exprString, exprId)

              // compute input variables and assert equalities
              val inputVars = variablesOf(b).filter{ v => !outputVars.contains(v) }.toList

              reporter.info("Input variables  : " + inputVars.mkString(", "))
              reporter.info("Output variables : " + outputVars.map(_.name).mkString(", "))

              // serialize list of input "Variable"s
              val (inputVarListString, inputVarListId) = serialize(inputVars map (iv => Variable(iv)))

              // serialize each output "Identifier"
              val outputStringIdPairList = outputVars.map(ov => serialize(ov))

              val equalities : List[Tree] = (for (iv <- inputVars) yield {
                codeGen.inputEquality(inputVarListString, inputVarListId, iv, scalaToExprSym)
              }).toList

              val (andExprAssignment, andExprSym) = codeGen.assignAndExpr((ID(exprSym) :: equalities) : _*)

              // invoke solver and get ordered list of assignments
              val (solverInvocation, outcomeTupleSym)     = codeGen.invokeSolver(progSym, if (inputVars.isEmpty) exprSym else andExprSym)
              val (outcomeAssignment, outcomeSym)         = codeGen.assignOutcome(outcomeTupleSym)
              val (modelAssignment, modelSym)             = codeGen.assignModel(outcomeTupleSym)
              val unsatConstraintExceptionThrowing        = codeGen.raiseUnsatConstraintException(outcomeSym)

              // retrieving interpretations and converting to Scala
              val tripleList = (for (((outputString, outputId), tt) <- (outputStringIdPairList zip typeTreeList)) yield {
                val (modelValueAssignment, modelValueSym) = codeGen.assignModelValue(outputString, outputId, modelSym)
                val (scalaValueAssignment, scalaValueSym) = codeGen.assignScalaValue(exprToScalaCastSym, tt, modelValueSym)
                (modelValueAssignment, scalaValueAssignment, scalaValueSym)
              })

              val valueAssignments = tripleList.map{ case (mva, sva, _) => List(mva, sva) }.flatten
              val returnExpressions = tripleList.map{ case(_,_,svs) => svs }

              val returnExpr : Tree = if (outputVars.size == 1) Ident(returnExpressions.head) else {
                val tupleTypeTree = TypeTree(definitions.tupleType(typeTreeList map (tt => tt.tpe)))
                New(tupleTypeTree,List(returnExpressions map (Ident(_))))
              }

              val code = BLOCK(
                List(progAssignment, exprAssignment, andExprAssignment) ::: 
                solverInvocation ::: 
                List(outcomeAssignment, unsatConstraintExceptionThrowing, modelAssignment) ::: 
                valueAssignments ::: 
                List(returnExpr) : _*)

              typer.typed(atOwner(currentOwner) {
                code
              })
          }
        }

        case cd @ ClassDef(mods, name, tparams, impl) if (cd.symbol.isModuleClass && tparams.isEmpty && !cd.symbol.isSynthetic) => {
          val codeGen = new CodeGenerator(unit, currentOwner, tree.pos)

          val ((e2sSym, exprToScalaCode), (e2sCastSym,exprToScalaCastCode)) = codeGen.exprToScalaMethods(cd.symbol, prog)
          exprToScalaSym      = e2sSym
          exprToScalaCastSym  = e2sCastSym

          val (scalaToExprCode, s2eSym)                                     = codeGen.scalaToExprMethod(cd.symbol, prog, serializedProgString, serializedProgId)
          scalaToExprSym      = s2eSym

          val skipCounter                                                   = codeGen.skipCounter(codeGen.getProgram(serializedProgString, serializedProgId))

          atOwner(tree.symbol) {
            treeCopy.ClassDef(tree, transformModifiers(mods), name,
                              transformTypeDefs(tparams), impl match {
                                case Template(parents, self, body) =>
                                  treeCopy.Template(impl, transformTrees(parents),
                                    transformValDef(self), 
                                      typer.typed(atOwner(currentOwner) {exprToScalaCode}) ::
                                      typer.typed(atOwner(currentOwner) {exprToScalaCastCode}) :: 
                                      typer.typed(atOwner(currentOwner) {scalaToExprCode}) :: 
                                      typer.typed(atOwner(currentOwner) {skipCounter}) ::
                                      transformStats(body, tree.symbol))
                              }) 
          }
        }

        case _ => super.transform(tree)
      }
    }
  }
}

/** A collection of methods that are called on runtime */
object CallTransformation {

  final class UnsatisfiableConstraintException extends Exception
  final class UnknownConstraintException extends Exception
  
  def modelValue(varId: Identifier, model: Map[Identifier, Expr]) : Expr = model.get(varId) match {
    case Some(value) => value
    case None => simplestValue(varId.getType)
  }

  def modelWithStringKeys(model: Map[Identifier, Expr]) : Map[String, Expr] =
    model.map{ case (k, v) => (k.name, v) }

  def model(outcomeTuple : (Option[Boolean], Map[Identifier, Expr])) : Map[Identifier, Expr] = {
    outcomeTuple._2
  }

  def outcome(outcomeTuple : (Option[Boolean], Map[Identifier, Expr])) : Option[Boolean] = {
    outcomeTuple._1
  }

  def inputVar(inputVarList : List[Variable], varName : String) : Variable = {
    inputVarList.find(_.id.name == varName).getOrElse(scala.Predef.error("Could not find input variable '" + varName + "' in list " + inputVarList))
  }

  def skipCounter(prog: Program) : Unit = {
    val maxId = prog.allIdentifiers max Ordering[Int].on[Identifier](_.id)
    purescala.Common.FreshIdentifier.forceSkip(maxId.id)
  }

  def raiseUnsatConstraintException(outcome : Option[Boolean]) : Unit = {
    outcome match {
      case Some(false)  =>
      case Some(true) => throw new UnsatisfiableConstraintException
      case None        => throw new UnknownConstraintException
    }
  }
}
