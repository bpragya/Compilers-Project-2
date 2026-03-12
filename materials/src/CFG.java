import ir.*;
import ir.operand.*;

import java.util.*;

public class CFG {

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
            IRInstruction.OpCode.GOTO, BREQ, BRNEQ, BRLT, BRGT, BRGEQ,

        ));

        

        for (int i = 0; i < n ; i++) {
        //for (IRInstruction instruction : function.instructions) {
            IRInstruction inst = function.instructions.get(i);
            if (i == 0) {
                //startLNs.add(inst.irLineNumber);
                startLNs.add(i)
            }
            else if (inst.opCode == IRInstruction.OpCode.LABEL) { //labels are leader
                //startLNs.append(inst.irLineNumber);
                startLNs.add(i)
            }
            else if jumpInst.contains(inst.opCode) { //instruction after a jump instruction
                if (i + 1 < n) {
                    s//tartLNs.append(function.instructions.get(i + 1).irLineNumber); 
                    startLNs.add(i+1)
                }
                
            }

        }

        numNodes = startLNs.size();

        for (int i = 0; i < numNodes; i++) {
            //int j = startLNs[i] + 1;
            //IRInstruction inst = function.instructions.get(j);
            int end;
            if (i + 1 < numNodes) { //there is another node
                end = startLNs.get(i+1) - 1
            } else {
                end = n - 1; //this is the last block
            }
            endLNs.add(end);
        }

        //when you have a label in a branch instruction, check what block the label is with
        Map<String, Integer> labelToBlock = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            IRInstruction first = function.instructions.get(startLNs.get(i)); //get the first label
            if (first.opCode == IRInstruction.OpCode.LABEL) {
                labelToBlock.put(IRInstruction.OpCode.LABEL.toString, i)
            }
        }
        //<label, branch>

        List<Integer> successors = new ArrayList<>;

        for (int i = 0; i < numNodes; i++) {
            IRInstruction last = function.instructions.get(endLNs.get(i));
            
            if (jumpInst.contains(last.opCode)) { //last instruction is a branch, go to the label
                //add two edges  - true, false

            } else if last.opCode == IRInstruction.OpCode.GOTO {
                //add one edge
            } else {

            }
        }


        

        

    }
}
