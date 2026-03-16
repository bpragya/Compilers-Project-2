import ir.*;
import ir.operand.*;

import java.util.*;

public class OldCFG {

  public static void main(String[] args) throws Exception {
    // Parse the IR file
    IRReader irReader = new IRReader();
    IRProgram program = irReader.parseIRFile(args[0]);

    // Build CFG for each function
    for (IRFunction function : program.functions) {
      System.out.println("=== CFG for function: " + function.name + " ===");
      buildCFG(function);
      System.out.println();
    }
  }

  static void buildCFG(IRFunction function) {
    int n = function.instructions.size();
    int numNodes = 0;
    List<Integer> startLNs = new ArrayList<>();
    List<Integer> endLNs = new ArrayList<>();
    Set<IRInstruction.OpCode> jumpInst = new HashSet<>(Arrays.asList(
        IRInstruction.OpCode.BREQ,
        IRInstruction.OpCode.BRNEQ,
        IRInstruction.OpCode.BRLT,
        IRInstruction.OpCode.BRGT,
        IRInstruction.OpCode.BRGEQ));

    for (int i = 0; i < n; i++) {
      // for (IRInstruction instruction : function.instructions) {
      IRInstruction inst = function.instructions.get(i);
      if (!startLNs.contains(i)) { // not in the start list
        if (i == 0) {
          // startLNs.add(inst.irLineNumber);
          startLNs.add(i);
        } else if (inst.opCode == IRInstruction.OpCode.LABEL) { // labels are leader
          // startLNs.append(inst.irLineNumber);
          startLNs.add(i);
        } else if (jumpInst.contains(inst.opCode) || inst.opCode == IRInstruction.OpCode.GOTO) { // instruction after a
                                                                                                 // jump instruction
          if (i + 1 < n) {
            // startLNs.append(function.instructions.get(i + 1).irLineNumber);
            startLNs.add(i + 1);
          }
        }
      }
    }

    numNodes = startLNs.size();

    for (int i = 0; i < numNodes; i++) {
      // int j = startLNs[i] + 1;
      // IRInstruction inst = function.instructions.get(j);
      int end;
      if (i + 1 < numNodes) { // there is another node
        end = startLNs.get(i + 1) - 1;
      } else {
        end = n - 1; // this is the last block
      }
      endLNs.add(end);
    }

    // when you have a label in a branch instruction, check what block the label is
    // with
    Map<String, Integer> labelToBlock = new HashMap<>();
    for (int i = 0; i < numNodes; i++) {
      IRInstruction first = function.instructions.get(startLNs.get(i)); // get the first label
      if (first.opCode == IRInstruction.OpCode.LABEL) {
        labelToBlock.put(((IRLabelOperand) first.operands[0]).getName(), i);
      }
    }
    // <label, branch>

    List<List<Integer>> successors = new ArrayList<>();
    List<List<Integer>> predecessors = new ArrayList<>();
    for (int i = 0; i < numNodes; i++) {
      successors.add(new ArrayList<>());
      predecessors.add(new ArrayList<>());
    }

    for (int i = 0; i < numNodes; i++) {
      IRInstruction last = function.instructions.get(endLNs.get(i));

      if (jumpInst.contains(last.opCode)) { // last instruction is a branch, go to the label
        // add two edges - true, false
        if (i + 1 < numNodes) {
          successors.get(i).add(i + 1);
          predecessors.get(i + 1).add(i);
        }
        String targetLabel = ((IRLabelOperand) last.operands[0]).getName();
        int targetBlock = labelToBlock.get(targetLabel);
        successors.get(i).add(targetBlock);
        predecessors.get(targetBlock).add(i);
      } else if (last.opCode == IRInstruction.OpCode.GOTO) {
        // add one edge
        String targetLabel = last.operands[0].toString();
        int targetBlock = labelToBlock.get(targetLabel);
        successors.get(i).add(targetBlock);
        predecessors.get(targetBlock).add(i);
      } else {
        if (i + 1 < numNodes) {
          successors.get(i).add(i + 1);
          predecessors.get(i + 1).add(i);
        }
      }
    }

    for (int i = 0; i < numNodes; i++) {
      System.out.println("Block " + i);
      System.out.println("Beginning Instruction " + startLNs.get(i));
      System.out.println("Ending Instruction " + endLNs.get(i));
      for (int j = startLNs.get(i); j <= endLNs.get(i); j++) {
        System.out.println("Instruction " + function.instructions.get(j).opCode +
            Arrays.toString(function.instructions.get(j).operands));
      }

      System.out.println("Successor Blocks " + successors.get(i));
      System.out.println("================================");
    }
  }
}
