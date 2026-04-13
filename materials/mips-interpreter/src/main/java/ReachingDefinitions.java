import ir.IRInstruction;

import java.util.*;

public class ReachingDefinitions {
  Map<BasicBlock, Set<IRInstruction>> inSet;
  Map<BasicBlock, Set<IRInstruction>> outSet;
  Map<String, Set<IRInstruction>> allDefinitions;

  public ReachingDefinitions(CFG cfg) {
    this.inSet = new HashMap<>();
    this.outSet = new HashMap<>();
    this.allDefinitions = new HashMap<>();
    updateDefinitions(cfg);
  }

  /**
   * Runs the fixed-point dataflow solver over {@code cfg}, filling the
   * {@link #inSet} and {@link #outSet} for every Basic Block.
   *
   * @param cfg the control flow graph to analyze
   */
  public void run(CFG cfg) {
    updateDefinitions(cfg);
    // new "truncated" defs just dropped
    inSet.clear();
    outSet.clear();
    // Every Basic Block gets its own IN/OUT set
    for (BasicBlock block : cfg.getBlocks()) {
      inSet.put(block, new HashSet<IRInstruction>());
      outSet.put(block, new HashSet<IRInstruction>());
    }

    List<BasicBlock> order = reversePostOrder(cfg);
    boolean changed = true;

    while (changed) {
      changed = false;

      for (BasicBlock block : order) {
        Set<IRInstruction> newIN = new HashSet<>();
        for (BasicBlock predecessor : block.predecessors) {
          newIN.addAll(outSet.get(predecessor));
        }
        inSet.put(block, newIN);

        Set<IRInstruction> newOut = new HashSet<>(inSet.get(block));
        newOut.removeAll(computeKILL(block));
        newOut.addAll(computeGEN(block));

        if (!newOut.equals(outSet.get(block))) {
          outSet.put(block, newOut);
          changed = true;
        }
      }
    }
  }

  /**
   * Computes definitions through the CFG (writes)
   * so that we have know which ones are still relevant
   *
   * @param cfg the control-flow graph to update definitions for
   */
  public void updateDefinitions(CFG cfg) {
    allDefinitions.clear();

    for (BasicBlock block : cfg.getBlocks()) {
      for (IRInstruction instr : block.instructions) {
        if (Helpers.defines(instr)) {
          String variable = Helpers.defVar(instr);
          if (!allDefinitions.containsKey(variable)) {
            allDefinitions.put(variable, new HashSet<>());
          }
          allDefinitions.get(variable).add(instr);
        }
      }
    }
  }

  /**
   * Computes GEN[block] — the set of definitions in {@code block} that reach
   * its exit. Only the last definition of each variable is used.
   *
   * @param block the block to compute GEN for
   * @return the GEN set
   */
  private Set<IRInstruction> computeGEN(BasicBlock block) {
    Set<IRInstruction> gen = new HashSet<>();
    for (IRInstruction inst : block.instructions) {
      if (Helpers.defines(inst)) {
        String variable = Helpers.defVar(inst);
        gen.removeIf(d -> Helpers.defVar(d).equals(variable));
        gen.add(inst);
      }
    }
    return gen;
  }

  /**
   * Computes KILL[block] — all definitions of variables defined in {@code block}
   * that originate from other blocks (killed by them).
   *
   * @param block the block to compute KILL for
   * @return the KILL set
   */
  private Set<IRInstruction> computeKILL(BasicBlock block) {
    Set<IRInstruction> kill = new HashSet<>();
    Set<IRInstruction> blockInstructSet = new HashSet<>(block.instructions);
    for (IRInstruction inst : block.instructions) {
      if (Helpers.defines(inst)) {
        String variable = Helpers.defVar(inst);
        Set<IRInstruction> defs = allDefinitions.get(variable);
        if (defs != null) {
          for (IRInstruction d : defs) {
            if (!blockInstructSet.contains(d)) {
              kill.add(d);
            }
          }
        }
      }
    }
    return kill;
  }

  /**
   * Returns the Basic Blocks of {@code cfg} in reverse post-order, which gives
   * fastest convergence for forward dataflow analyses.
   *
   * @param cfg the control flow graph to order
   * @return blocks in reverse post-order
   */
  private List<BasicBlock> reversePostOrder(CFG cfg) {
    HashSet<BasicBlock> visited = new HashSet<>();
    List<BasicBlock> postOrder = new ArrayList<>();
    dfs(cfg.entry(), visited, postOrder);
    Collections.reverse(postOrder);
    return postOrder;
  }

  /**
   * Recursive DFS helper that appends each block to {@code postOrder} after
   * all its successors have been visited. Makes convergence to fixed point faster
   *
   * @param block     the current block being visited
   * @param visited   the set of already-visited blocks
   * @param postOrder the list being built in post-order
   */
  public void dfs(BasicBlock block, HashSet<BasicBlock> visited, List<BasicBlock> postOrder) {
    visited.add(block);
    for (BasicBlock neighbor : block.successors) {
      if (!visited.contains(neighbor)) {
        dfs(neighbor, visited, postOrder);
      }
    }
    postOrder.add(block);
  }
}
