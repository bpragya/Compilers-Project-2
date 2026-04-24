import ir.IRFunction;
import ir.datatype.IRArrayType;
import ir.operand.IRVariableOperand;
import mips.MIPSInstruction;
import mips.MIPSOp;
import mips.operand.Addr;
import mips.operand.Imm;
import mips.operand.MIPSOperand;
import mips.operand.Register;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Performs naive register allocation by spilling all virtual registers to the
 * stack. Each virtual register is assigned a fixed fp-relative slot; loads and
 * stores are emitted around every instruction.
 */
public class NaiveAllocator {

  private final PrintStream out;
  private final Map<Integer, MIPSInstruction> instructions;
  private final Map<String, Integer> labels;
  private int mipsIdx = 0;

  /**
   * @param out destination for the emitted MIPS assembly text
   */
  public NaiveAllocator(PrintStream out) {
    this.out = out;
    this.instructions = new HashMap<>();
    this.labels = new HashMap<>();
  }

  /** Returns the emitted MIPS instructions indexed by emission order. */
  public Map<Integer, MIPSInstruction> getInstructions() {
    return instructions;
  }

  /** Returns the label-to-instruction-index map. */
  public Map<String, Integer> getLabels() {
    return labels;
  }

  /**
   * Allocates registers for {@code func} using the naive spill-everything
   * strategy and emits the resulting MIPS instructions.
   *
   * @param func              the IR function being compiled
   * @param inputInstructions the unallocated instructions produced by instruction
   *                          selection
   */
  public void allocate(IRFunction func, Map<Integer, MIPSInstruction> inputInstructions) {
    allocate(func, inputInstructions, null);
  }

  public void allocate(IRFunction func, Map<Integer, MIPSInstruction> inputInstructions, String trailingLabel) {
    Register sp = new Register("$sp", false);
    Register fp = new Register("$fp", false);
    Register ra = new Register("$ra", false);

    emitPrologue(sp, fp, ra);

    Map<String, Integer> vrMap = buildVrMap(inputInstructions);
    int frameSize = 4 * vrMap.size();

    Set<String> paramNames = new HashSet<>();
    for (IRVariableOperand p : func.parameters)
      paramNames.add(p.getName());

    int totalArrayDataSize = 0;
    List<IRVariableOperand> localArrayVars = new ArrayList<>();
    for (IRVariableOperand var : func.variables) {
      if (var.type instanceof IRArrayType && !paramNames.contains(var.getName())) {
        localArrayVars.add(var);
        totalArrayDataSize += ((IRArrayType) var.type).getSize() * 4;
      }
    }

    Map<String, Integer> arrayBaseOffsets = new LinkedHashMap<>();
    int nextArrayBase = -(frameSize + totalArrayDataSize);
    for (IRVariableOperand var : localArrayVars) {
      String key = "$" + var.getName();
      arrayBaseOffsets.put(key, nextArrayBase);
      nextArrayBase += ((IRArrayType) var.type).getSize() * 4;
    }

    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp,
        new Imm(String.valueOf(-(frameSize + totalArrayDataSize)), "DEC")));

    loadParameters(func, vrMap, fp);
    initArrays(arrayBaseOffsets, vrMap, fp);
    Set<String> floatVrs = buildFloatSet(inputInstructions);
    String epilogueLabel = func.name + "_epilogue";
    emitBody(inputInstructions, vrMap, fp, epilogueLabel, trailingLabel, floatVrs);
    emitEpilogue(sp, fp, ra, epilogueLabel);
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /**
   * Records {@code instr} in the instruction map and prints it to {@code out}.
   * If the instruction carries a label, that label is registered in the labels
   * map so jump targets can be resolved at interpreter runtime.
   */
  private void emit(MIPSInstruction instr) {
    if (instr.label != null) {
      labels.put(instr.label, mipsIdx);
    }
    instructions.put(mipsIdx++, instr);
    out.println("   " + instr);
  }

  private void initArrays(Map<String, Integer> arrayBaseOffsets,
      Map<String, Integer> vrMap, Register fp) {
    Register t0 = new Register("$t0", false);
    for (Map.Entry<String, Integer> entry : arrayBaseOffsets.entrySet()) {
      String varKey = entry.getKey();
      int dataFpOffset = entry.getValue();
      if (!vrMap.containsKey(varKey))
        continue;
      emit(new MIPSInstruction(MIPSOp.ADDI, null, t0, fp,
          new Imm(String.valueOf(dataFpOffset), "DEC")));
      emit(new MIPSInstruction(MIPSOp.SW, null, t0,
          new Addr(new Imm(String.valueOf(vrMap.get(varKey)), "DEC"), fp)));
    }
  }

  private Set<String> buildFloatSet(Map<Integer, MIPSInstruction> instructions) {
    Set<String> floats = new HashSet<>();
    for (MIPSInstruction inst : instructions.values()) {
      if (isFloatOp(inst.op)) {
        for (Register r : inst.getReads()) {
          if (r != null && r.isVirtual)
            floats.add(r.name);
        }
        Register w = inst.getWrite();
        if (w != null && w.isVirtual)
          floats.add(w.name);
      }
    }
    return floats;
  }

  private boolean isFloatOp(MIPSOp op) {
    switch (op) {
      case LI_S:
      case L_S:
      case S_S:
      case MOV_S:
      case ADD_S:
      case SUB_S:
      case MUL_S:
      case DIV_S:
        return true;
      default:
        return false;
    }
  }

  /**
   * Emits the standard MIPS function prologue: saves {@code $ra} and {@code $fp}
   * on the stack, then sets {@code $fp} to the current stack pointer.
   */
  private void emitPrologue(Register sp, Register fp, Register ra) {
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.SW, null, ra, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.SW, null, fp, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.MOVE, null, fp, sp));
  }

  /**
   * Scans {@code instructions} and assigns each unique virtual register a
   * distinct
   * fp-relative stack slot, starting at {@code -4($fp)} and growing downward.
   *
   * @return map from virtual register name to its fp-relative byte offset
   */
  private Map<String, Integer> buildVrMap(Map<Integer, MIPSInstruction> instructions) {
    Map<String, Integer> vrMap = new HashMap<>();
    int offset = -4;

    for (MIPSInstruction inst : instructions.values()) {
      for (Register r : inst.getReads()) {
        if (r.isVirtual && !vrMap.containsKey(r.name)) {
          vrMap.put(r.name, offset);
          offset -= 4;
        }
      }
      Register w = inst.getWrite();
      if (w != null && w.isVirtual && !vrMap.containsKey(w.name)) {
        vrMap.put(w.name, offset);
        offset -= 4;
      }
    }

    return vrMap;
  }

  /**
   * Copies each incoming argument from its ABI-defined caller stack slot
   * ({@code 8+i*4($fp)}) into the corresponding virtual register's spill slot.
   * Parameters not referenced in the function body are skipped with a warning.
   */
  private void loadParameters(IRFunction func, Map<String, Integer> vrMap, Register fp) {
    for (int i = 0; i < func.parameters.size(); i++) {
      String paramKey = "$" + func.parameters.get(i).getName();
      if (!vrMap.containsKey(paramKey)) {
        System.err.println("Warning: parameter '" + paramKey +
            "' in function '" + func.name +
            "' has no vrMap slot (never referenced in body) — skipping load");
        continue;
      }
      Register t = new Register("$t0", false);
      emit(new MIPSInstruction(MIPSOp.LW, null, t,
          new Addr(new Imm(String.valueOf(8 + i * 4), "DEC"), fp)));
      emit(new MIPSInstruction(MIPSOp.SW, null, t,
          new Addr(new Imm(String.valueOf(vrMap.get(paramKey)), "DEC"), fp)));
    }
  }

  /**
   * Emits physical instructions for every instruction in {@code instructions}.
   * {@code JR} instructions from RETURN are rewritten to {@code J epilogueLabel}
   * so the epilogue's stack-cleanup always runs before returning; all others are
   * handled by {@link #emitWithSpill}.
   */
  private void emitBody(Map<Integer, MIPSInstruction> instructions, Map<String, Integer> vrMap,
      Register fp, String epilogueLabel, String trailingLabel, Set<String> floatVrs) {
    for (MIPSInstruction inst : instructions.values()) {
      if (inst.op == MIPSOp.JR) {
        emit(new MIPSInstruction(MIPSOp.J, inst.label, new Addr(epilogueLabel)));
      } else {
        emitWithSpill(inst, vrMap, fp, floatVrs);
      }
    }
    if (trailingLabel != null) {
      emit(new MIPSInstruction(MIPSOp.J, trailingLabel, new Addr(epilogueLabel)));
    }
  }

  private void emitWithSpill(MIPSInstruction inst, Map<String, Integer> vrMap,
      Register fp, Set<String> floatVrs) {
    Register[] reads = inst.getReads();
    Register[] physReads = new Register[reads.length];
    int intCount = 0;
    int floatCount = 0;
    String pendingLbl = inst.label;

    for (int i = 0; i < reads.length; i++) {
      Register r = reads[i];
      if (r != null && r.isVirtual) {
        String lbl = pendingLbl;
        pendingLbl = null;
        if (floatVrs.contains(r.name)) {
          physReads[i] = new Register("$f" + (floatCount * 2), false);
          emit(new MIPSInstruction(MIPSOp.L_S, lbl, physReads[i],
              new Addr(new Imm(String.valueOf(vrMap.get(r.name)), "DEC"), fp)));
          floatCount++;
        } else {
          physReads[i] = new Register("$t" + intCount, false);
          emit(new MIPSInstruction(MIPSOp.LW, lbl, physReads[i],
              new Addr(new Imm(String.valueOf(vrMap.get(r.name)), "DEC"), fp)));
          intCount++;
        }
      } else {
        physReads[i] = r;
      }
    }

    Register w = inst.getWrite();
    Register physWrite;
    if (w != null && w.isVirtual) {
      int aliasIdx = -1;
      for (int i = 0; i < reads.length; i++) {
        if (reads[i] != null && reads[i].isVirtual && reads[i].name.equals(w.name)) {
          aliasIdx = i;
          break;
        }
      }
      if (aliasIdx >= 0) {
        physWrite = physReads[aliasIdx];
      } else if (floatVrs.contains(w.name)) {
        physWrite = new Register("$f" + (floatCount * 2), false);
      } else {
        physWrite = new Register("$t" + intCount, false);
      }
    } else {
      physWrite = w;
    }

    MIPSInstruction physInst = substituteRegisters(inst, physReads, physWrite);
    if (pendingLbl == null && inst.label != null) {
      physInst = new MIPSInstruction(physInst.op, null,
          physInst.operands.toArray(new MIPSOperand[0]));
    }
    emit(physInst);

    if (w != null && w.isVirtual) {
      MIPSOp storeOp = floatVrs.contains(w.name) ? MIPSOp.S_S : MIPSOp.SW;
      emit(new MIPSInstruction(storeOp, null, physWrite,
          new Addr(new Imm(String.valueOf(vrMap.get(w.name)), "DEC"), fp)));
    }
  }

  /**
   * Returns a copy of {@code inst} with every virtual register replaced by its
   * corresponding physical register from {@code physReads} and {@code physWrite}.
   * The substitution is driven by the name mapping derived from
   * {@link MIPSInstruction#getReads()} and {@link MIPSInstruction#getWrite()},
   * so no opcode-specific logic is needed here.
   *
   * @param inst      the instruction whose registers are to be substituted
   * @param physReads physical registers to substitute for each read, in order
   * @param physWrite physical register to substitute for the write, or
   *                  {@code null}
   * @return a new instruction with the same opcode, label, and substituted
   *         operands
   */
  private static MIPSInstruction substituteRegisters(
      MIPSInstruction inst, Register[] physReads, Register physWrite) {
    Register[] virtualReads = inst.getReads();
    Map<String, Register> sub = new HashMap<>();
    for (int i = 0; i < virtualReads.length; i++) {
      sub.put(virtualReads[i].name, physReads[i]);
    }
    Register virtualWrite = inst.getWrite();
    if (virtualWrite != null && physWrite != null) {
      sub.put(virtualWrite.name, physWrite);
    }
    List<MIPSOperand> newOps = new ArrayList<>();
    for (MIPSOperand op : inst.operands) {
      newOps.add(substituteOperand(op, sub));
    }
    return new MIPSInstruction(inst.op, inst.label, newOps.toArray(new MIPSOperand[0]));
  }

  /**
   * Applies {@code sub} to a single operand. {@link Register} operands are
   * replaced directly; the base register inside an {@link Addr} operand is
   * replaced while preserving the address mode and offset. All other operand
   * types (e.g. {@link Imm}, PC-relative labels) are returned unchanged.
   *
   * @param operand the operand to substitute into
   * @param sub     map from virtual register name to its physical replacement
   * @return the substituted operand, or the original if no substitution applies
   */
  private static MIPSOperand substituteOperand(MIPSOperand operand, Map<String, Register> sub) {
    if (operand instanceof Register) {
      Register r = (Register) operand;
      return sub.getOrDefault(r.name, r);
    }
    if (operand instanceof Addr) {
      Addr addr = (Addr) operand;
      if (addr.register == null)
        return addr;
      Register newReg = sub.getOrDefault(addr.register.name, addr.register);
      return addr.mode == Addr.Mode.BASE_OFFSET
          ? new Addr(addr.constant, newReg)
          : new Addr(newReg);
    }
    return operand;
  }

  /**
   * Emits the standard MIPS function epilogue labelled with {@code epilogueLabel}
   * so that {@code J epilogueLabel} jumps from RETURN sites reach it: restores
   * {@code $sp} from {@code $fp}, then restores {@code $fp} and {@code $ra}
   * from the stack before returning via {@code JR $ra}.
   */
  private void emitEpilogue(Register sp, Register fp, Register ra, String epilogueLabel) {
    emit(new MIPSInstruction(MIPSOp.MOVE, epilogueLabel, sp, fp));
    emit(new MIPSInstruction(MIPSOp.LW, null, fp, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.LW, null, ra, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.JR, null, ra));
  }
}
