/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package ast

import utils.Graphs._

trait CallGraph {
  protected val trees: Trees
  import trees._
  import trees.exprOps._
  protected val symbols: Symbols

  private def collectCalls(fd: FunDef)(e: Expr): Set[(FunDef, FunDef)] = e match {
    case f @ FunctionInvocation(id, tps, _) => Set((fd, symbols.getFunction(id)))
    case _ => Set()
  }

  lazy val graph: DiGraph[FunDef, SimpleEdge[FunDef]] = {
    var g = DiGraph[FunDef, SimpleEdge[FunDef]]()

    for ((_, fd) <- symbols.functions; c <- collect(collectCalls(fd))(fd.fullBody)) {
      g += SimpleEdge(c._1, c._2)
    }

    g
  }

  lazy val allCalls = graph.E.map(e => e._1 -> e._2)

  def isRecursive(f: FunDef) = {
    graph.transitiveSucc(f) contains f
  }

  def isSelfRecursive(f: FunDef) = {
    graph.succ(f) contains f
  }

  def calls(from: FunDef, to: FunDef) = {
    graph.E contains SimpleEdge(from, to)
  }

  def callers(to: FunDef): Set[FunDef] = {
    graph.pred(to)
  }

  def callers(tos: Set[FunDef]): Set[FunDef] = {
    tos.flatMap(callers)
  }

  def callees(from: FunDef): Set[FunDef] = {
    graph.succ(from)
  }

  def callees(froms: Set[FunDef]): Set[FunDef] = {
    froms.flatMap(callees)
  }

  def transitiveCallers(to: FunDef): Set[FunDef] = {
    graph.transitivePred(to)
  }

  def transitiveCallers(tos: Set[FunDef]): Set[FunDef] = {
    tos.flatMap(transitiveCallers)
  }

  def transitiveCallees(from: FunDef): Set[FunDef] = {
    graph.transitiveSucc(from)
  }

  def transitiveCallees(froms: Set[FunDef]): Set[FunDef] = {
    froms.flatMap(transitiveCallees)
  }

  def transitivelyCalls(from: FunDef, to: FunDef): Boolean = {
    graph.transitiveSucc(from) contains to
  }

  lazy val stronglyConnectedComponents = graph.stronglyConnectedComponents.N
}
