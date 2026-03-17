import ir.IRInstruction;
import ir.operand.IRVariableOperand;

import java.util.*;

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
   * is unchanged
   */
  public boolean run(CFG cfg) {
    rd.run(cfg);
    Map<IRInstruction, Set<IRInstruction>> depMap = computeDependencies(cfg);
    // The Mark Pass
    Set<IRInstruction> markedInsts = markLiveInstructions(cfg, depMap);
    boolean changed = false;
    for (BasicBlock block : cfg.getBlocks()) {
      List<IRInstruction> toRemove = new ArrayList<>();
      for (IRInstruction inst : block.instructions) {
        if (Helpers.defines(inst) && !markedInsts.contains(inst)) {
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
   * least one use
   */
  private Map<IRInstruction, Set<IRInstruction>> computeDependencies(CFG cfg) {
    Map<IRInstruction, Set<IRInstruction>> depMap = new HashMap<>();
    // this is a map of an instruction to the instructions which define the USED operands of said instruction
    for (BasicBlock block : cfg.getBlocks()) {
      Set<IRInstruction> live = new HashSet<>(rd.inSet.get(block));

      for (IRInstruction inst : block.instructions) {

        Set<IRInstruction> instContributors = depMap.get(inst);

        if (instContributors == null) {
          instContributors = new HashSet<>();
          depMap.put(inst, instContributors);
        }

        for (IRVariableOperand useVar : Helpers.uses(inst)) {
          String variable = useVar.getName();
          for (IRInstruction d : live) {
            if (Helpers.defines(d) && Helpers.defVar(d).equals(variable)) {
              instContributors.add(d);
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
    return depMap;
  }


  private Set<IRInstruction> markLiveInstructions(CFG cfg, Map<IRInstruction, Set<IRInstruction>> defMap) {
    Set<IRInstruction> liveInstructions = new HashSet<>();

    Queue<IRInstruction> worklist = new LinkedList<>();

    for (BasicBlock block : cfg.getBlocks()) {
      for (IRInstruction inst : block.instructions) {
        if (!(Helpers.defines(inst) && inst.opCode != IRInstruction.OpCode.CALLR)) { // if the instruction is NOT removable!
          if (!liveInstructions.contains(inst)) {
            liveInstructions.add(inst);
            worklist.add(inst);
          }
        }
      }
    }

    while (!worklist.isEmpty()) {
      IRInstruction inst = worklist.remove(); // now look at contributing operands (y op z for x <- y op z)

      Set<IRInstruction> instDeps = defMap.get(inst); // dependencies of said instruction

      if (instDeps != null) {
        for (IRInstruction def : instDeps) {
          if (!liveInstructions.contains(def)) {
            liveInstructions.add(def);
            worklist.add(def);
          }
        }
      }
    }
    return liveInstructions;
  }
}
