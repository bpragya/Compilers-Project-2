import ir.IRException;
import ir.IRFunction;
import ir.IRInstruction;
import ir.IRProgram;
import ir.IRReader;
import ir.operand.IRLabelOperand;
import ir.operand.IROperand;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.PrintStream;

public class Main {
  public static void main(String[] args) throws FileNotFoundException, IRException {

    String allocatorFlag = args[2];

    if (args.length < 3) {
      System.err.println("Usage: Main <path-to-ir-file> <path-to-output-s-file>");
      System.exit(1);
    }

    IRProgram program = new IRReader().parseIRFile(args[0]);

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
    PrintStream devNull = new PrintStream(OutputStream.nullOutputStream());

    out.println("  .text");
    out.println("  .globl main");
    out.println("   jal main");
    out.println("   li $v0, 10");
    out.println("   syscall");

    for (IRFunction func : program.functions) {
      InstructionSelection is = new InstructionSelection(devNull);
      for (IRInstruction instr : func.instructions) {
        is.generateInstructions(instr);
      }

      out.println(func.name + ":");

      if (allocatorFlag.equals("--naive")) {
        NaiveAllocator allocator = new NaiveAllocator(out);
        allocator.allocate(func, is.getInstructions(), is.getPendingLabel());
      } else if (allocatorFlag.equals("--greedy")) {
        IntrablockAllocator allocator = new IntrablockAllocator(out);
        allocator.allocate(func, is.getInstructions(), is.getPendingLabel());
      } else {
        System.err.println("run.sh has incorrect input.");
        System.exit(1);
      }
      
      
      
      


    }

    Intrinsics.emit(out);
    out.flush();
    out.close();
  }
}
