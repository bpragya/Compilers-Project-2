import ir.IRFunction;
import ir.IRInstruction;
import ir.IRProgram;
import ir.IRReader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Main {

  // Expected instruction counts per function after optimization
  private static final Map<String, Integer> EXPECTED = new LinkedHashMap<>();
  static {
    EXPECTED.put("fib", 11);
    EXPECTED.put("main", 5);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: Main <path-to-ir-file>");
      return;
    }

    IRProgram program = new IRReader().parseIRFile(args[0]);

    // Snapshot instruction counts before optimization
    Map<String, Integer> before = new LinkedHashMap<>();
    for (IRFunction fn : program.functions) {
      before.put(fn.name, fn.instructions.size());
    }

    new IROptimizer().optimize(program);

    // Print results and verify against expected
    System.out.println("=== Optimizer Test ===\n");
    boolean allPassed = true;

    for (IRFunction fn : program.functions) {
      int countBefore = before.get(fn.name);
      int countAfter = fn.instructions.size();
      Integer expected = EXPECTED.get(fn.name);

      System.out.println("Function: " + fn.name);
      System.out.println("  Before : " + countBefore + " instructions");
      System.out.println("  After  : " + countAfter + " instructions");

      if (expected != null) {
        boolean passed = countAfter == expected;
        System.out.println("  Expected: " + expected + "  [" + (passed ? "PASS" : "FAIL") + "]");
        if (!passed)
          allPassed = false;
      }
      System.out.println();
    }

    System.out.println("Result: " + (allPassed ? "ALL TESTS PASSED" : "SOME TESTS FAILED"));
  }
}
