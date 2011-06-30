package cp

class RuntimeSettings extends Serializable {
  var experimental : Boolean = purescala.Settings.experimental 
  var showIDs: Boolean = purescala.Settings.showIDs
  var noForallAxioms: Boolean = purescala.Settings.noForallAxioms
  var unrollingLevel: Int = purescala.Settings.unrollingLevel
  var zeroInlining : Boolean = purescala.Settings.zeroInlining 
  var useBAPA: Boolean = purescala.Settings.useBAPA
  var useInstantiator: Boolean = purescala.Settings.useInstantiator
  var useFairInstantiator: Boolean = purescala.Settings.useFairInstantiator
  var useCores : Boolean = purescala.Settings.useCores 
  var pruneBranches : Boolean = purescala.Settings.pruneBranches 
  var solverTimeout : Option[Int] = purescala.Settings.solverTimeout 
  var luckyTest : Boolean = purescala.Settings.luckyTest 
  var bitvectorBitwidth : Option[Int] = purescala.Settings.bitvectorBitwidth
  var useTemplates : Boolean = purescala.Settings.useTemplates

  /* when you add a new parameter here, remember to add it to copySettings in
   * RuntimeMethods */
  var useScalaEvaluator : Boolean = Settings.useScalaEvaluator
  var verbose : Boolean = Settings.verbose
}

object Settings {
  var useScalaEvaluator : Boolean = false
  var verbose : Boolean = false
}
