import ir.IRException;
import ir.IRFunction;
import ir.IRInstruction;
import ir.IRProgram;
import ir.IRReader;
import ir.operand.IRLabelOperand;
import ir.operand.IROperand;
import mips.MIPSInstruction;
import mips.MIPSProgram;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
  public static void main(String[] args) throws FileNotFoundException, IRException {
    if (args.length < 2) {
      System.err.println("Usage: Main <path-to-ir-file> <path-to-output-s-file>");
      System.exit(1);
    }

    IRProgram program = new IRReader().parseIRFile(args[0]);

    // prefix all label operands with their function name to avoid duplicates
    for (IRFunction func : program.functions) {
      for (IRInstruction instr : func.instructions) {
        for (IROperand op : instr.operands) {
          if (op instanceof IRLabelOperand) {
            ((IRLabelOperand) op).prefix(func.name + "_");
          }
        }
      }
    }

    PrintStream out = new PrintStream(args[1]);

    out.println("  .text");
    out.println("  .globl main");

    for (IRFunction func : program.functions) {
      out.println(func.name + ":");
      InstructionSelection is = new InstructionSelection(out);

      for (IRInstruction instr : func.instructions) {
        is.generateInstructions(instr);
      }

      Map<Integer, MIPSInstruction> instructions = is.getInstructions();
      Map<Integer, Integer> data = new HashMap<>();
      Map<String, Integer> labels = is.getLabels();
      labels.put(func.name, 0); // function entry label points to first instruction

      MIPSProgram mipsProg = new MIPSProgram(instructions, data, labels);

      // debug: print instructions and labels
      System.out.println("=== " + func.name + " ===");
      System.out.println("Labels: " + mipsProg.labels);
      for (Map.Entry<Integer, MIPSInstruction> entry : mipsProg.instructions.entrySet()) {
        System.out.println(entry.getKey() + ": " + entry.getValue());
      }
    }

    


  }
}









  
