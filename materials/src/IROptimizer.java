import ir.IRFunction;
import ir.IRInstruction;
import ir.IRProgram;

import java.util.ArrayList;
import java.util.List;

public class IROptimizer {

  /**
   * Optimizes every function in {@code program} by repeatedly applying dead code
   * elimination until no more changes occur, then writes the result back to
   * {@code function.instructions} so {@code IRPrinter} can consume it directly.
   *
   * @param program the IR program to optimize
   * @return the same {@code program} with optimized instruction lists
   */
  public IRProgram optimize(IRProgram program) {
    CFGBuilder builder = new CFGBuilder();
    for (IRFunction function : program.functions) {
      CFG cfg = builder.buildCFG(function);
      ReachingDefinitions rd = new ReachingDefinitions(cfg);
      DeadCodeElimination dce = new DeadCodeElimination(rd);

      boolean changed = true;
      while (changed) {
        changed = dce.run(cfg);
      }
      function.instructions = flatten(cfg);
    }
    return program;
  }

  /**
   * Flattens {@code cfg} back into a single ordered instruction list by
   * adding each block's instructions in block order.
   * Required because the {@code IRPrinter} reads {@code function.instructions}
   * directly.
   *
   * @param cfg the control flow graph to flatten
   * @return a flat list of all remaining instructions in block order
   */
  private List<IRInstruction> flatten(CFG cfg) {
    List<IRInstruction> result = new ArrayList<>();
    for (BasicBlock block : cfg.getBlocks()) {
      result.addAll(block.instructions);
    }
    return result;
  }
}
