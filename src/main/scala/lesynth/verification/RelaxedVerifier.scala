package lesynth

import leon.solvers._
import leon.purescala.Common._
import leon.purescala.Trees._
import leon.purescala.Extractors._
import leon.purescala.TreeOps._
import leon.purescala.TypeTrees._
import leon.purescala.Definitions._
import leon.synthesis._

import insynth.util.logging._

class RelaxedVerifier(solverf: SolverFactory[Solver], p: Problem, synthInfo: SynthesisInfo = new SynthesisInfo)
	extends AbstractVerifier(solverf, p, synthInfo) with HasLogger {
  
  var _isTimeoutUsed = false
  
  def isTimeoutUsed = _isTimeoutUsed
    
  override def checkValidity(expression: Expr) = {
    fine("Checking validity - assertCnstr: " + Not(expression))
    solver.assertCnstr(Not(expression))   
    val solverCheckResult = solver.check
    fine("Solver said " + solverCheckResult + " for " + expression)
    solverCheckResult match {
      case Some(true) =>
        false
      case Some(false) =>
        true
      case None =>
        _isTimeoutUsed = true
        warning("Interpreting None (timeout) as evidence for validity.")
        true
    }
  }
  
  override def checkValidityNoMod(expression: Expr) = {
    solver.push
    fine("Checking validityNoMod - assertCnstr: " + Not(expression))
    solver.assertCnstr(Not(expression))   
    val solverCheckResult = solver.check
    fine("Solver said " + solverCheckResult + " for " + expression)
    solver.pop()    
    solverCheckResult match {
      case Some(true) =>
        fine("And the model is " + solver.getModel)
        false
      case Some(false) =>
        true
      case None =>
        _isTimeoutUsed = true
        warning("Interpreting None (timeout) as evidence for validity.")
        true
    }
  }
  
  override def checkSatisfiabilityNoMod(expression: Expr) = {
    solver.push
    fine("Checking checkSatisfiabilityNoMod - assertCnstr: " + expression)
    solver.assertCnstr(expression)   
    val solverCheckResult = solver.check
    fine("Solver said " + solverCheckResult + " for " + expression)
    solver.pop()    
    solverCheckResult match {
      case Some(true) =>
        true
      case Some(false) =>
        false
      case None =>
        false
    }
  }
  
}