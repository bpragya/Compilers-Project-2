import ir.IRInstruction;
import ir.operand.IRVariableOperand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeadCodeElimination {
  private ReachingDefinitions rd;

  public DeadCodeElimination(ReachingDefinitions rd) {
    this.rd = rd;
  }

  /**
   * Runs one pass of dead code elimination over {@code cfg}.
   * Runs reaching definitions, then removes any defining instruction whose
   * result is never used.
   *
   * @param cfg the control flow graph to eliminate dead code from
   * @return {@code true} if any instruction was removed, {@code false} if the CFG
   *         is unchanged
   */
  public boolean run(CFG cfg) {
    rd.run(cfg);
    Set<IRInstruction> usedDefinitions = computeUsedDefs(cfg);

    boolean changed = false;
    for (BasicBlock block : cfg.getBlocks()) {
      List<IRInstruction> toRemove = new ArrayList<>();
      for (IRInstruction inst : block.instructions) {
        if (Helpers.defines(inst) && !usedDefinitions.contains(inst)) {
          if (inst.opCode != IRInstruction.OpCode.CALLR) {
            toRemove.add(inst);
            changed = true;
          }
        }
      }
      block.instructions.removeAll(toRemove);
    }
    return changed;
  }

  /**
   * Collects all of the reaching definitions that have actually been read. Tracks
   * which definitions
   * are alive at each use point.
   *
   * @param cfg the control flow graph to scan for used definitions
   * @return the set of defining instructions whose results are consumed by at
   *         least one use
   */
  private Set<IRInstruction> computeUsedDefs(CFG cfg) {
    Set<IRInstruction> usedDefs = new HashSet<>();

    for (BasicBlock block : cfg.getBlocks()) {
      Set<IRInstruction> live = new HashSet<>(rd.inSet.get(block));

      for (IRInstruction inst : block.instructions) {
        for (IRVariableOperand useVar : Helpers.uses(inst)) {
          String variable = useVar.getName();
          for (IRInstruction d : live) {
            if (Helpers.defines(d) && Helpers.defVar(d).equals(variable)) {
              usedDefs.add(d);
            }
          }
        }
        if (Helpers.defines(inst)) {
          String variable = Helpers.defVar(inst);
          live.removeIf(d -> Helpers.defines(d) && Helpers.defVar(d).equals(variable));
          live.add(inst);
        }
      }
    }
    return usedDefs;
  }
}
