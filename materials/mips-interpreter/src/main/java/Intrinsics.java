import java.io.PrintStream;

/**
 * Emits MIPS assembly implementations of the Tiger-IR built-in functions.
 *
 * <p>These functions ({@code geti}, {@code getf}, {@code puti}, {@code putf},
 * {@code putc}) have no Tiger-IR bodies. Each is implemented as a standard
 * MIPS function with a full prologue/epilogue that delegates to a MIPS syscall.
 */
public class Intrinsics {

  /**
   * Emits all Tiger-IR intrinsic functions to {@code out}.
   *
   * @param out the output stream receiving the MIPS assembly text
   */
  public static void emit(PrintStream out) {
    emitFunction(out, "getf", null, 6);
    emitFunction(out, "putf", "l.s $f12, 8($fp)", 2);
    emitFunction(out, "geti", null, 5);
    emitFunction(out, "puti", "lw $a0, 8($fp)", 1);
    emitFunction(out, "putc", "lw $a0, 8($fp)", 11);
  }

  /**
   * Emits a single intrinsic function with a standard prologue and epilogue.
   *
   * <p>The generated frame saves {@code $ra} and {@code $fp}, optionally loads
   * an argument (e.g. the value to print from {@code 8($fp)}), fires the given
   * syscall, then restores the stack and returns via {@code jr $ra}.
   *
   * @param out         the output stream receiving the MIPS assembly text
   * @param name        the function label (e.g. {@code "geti"})
   * @param loadArg     a single MIPS instruction string to load the syscall
   *                    argument, or {@code null} if the syscall takes no input
   *                    (e.g. read syscalls that return into a register)
   * @param syscallCode the MIPS syscall number (e.g. 5 = read_int, 1 = print_int)
   */
  private static void emitFunction(PrintStream out, String name, String loadArg, int syscallCode) {
    out.println(name + ":");
    out.println("   addi $sp, $sp, -4");
    out.println("   sw $ra, 0($sp)");
    out.println("   addi $sp, $sp, -4");
    out.println("   sw $fp, 0($sp)");
    out.println("   move $fp, $sp");
    if (loadArg != null) out.println("   " + loadArg);
    out.println("   li $v0, " + syscallCode);
    out.println("   syscall");
    out.println("   move $sp, $fp");
    out.println("   lw $fp, 0($sp)");
    out.println("   addi $sp, $sp, 4");
    out.println("   lw $ra, 0($sp)");
    out.println("   addi $sp, $sp, 4");
    out.println("   jr $ra");
  }
}
