import ir.IRException;
import ir.IRProgram;
import ir.IRPrinter;
import ir.IRReader;

import java.io.FileNotFoundException;

public class Main {
  public static void main(String[] args) throws FileNotFoundException, IRException {
    if (args.length < 1) {
      System.err.println("Usage: Main <path-to-ir-file>");
      System.exit(1);
    }

    IRProgram program = new IRReader().parseIRFile(args[0]);
    new IROptimizer().optimize(program);
    new IRPrinter(System.out).printProgram(program);
  }
}
