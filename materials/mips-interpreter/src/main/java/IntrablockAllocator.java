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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Intra-block greedy register allocator (spec §1.2.2).
 *
 * <p>Algorithm overview, per-block:
 * <ol>
 *   <li>Partition instructions into basic blocks. A block starts at a label
 *       (potential branch target) and ends at a branch, jump, or call.</li>
 *   <li>Count how many times each virtual register is <em>read</em> in the block
 *       (writes-only don't benefit from staying in a register across instructions).</li>
 *   <li>Greedily assign physical registers: most-read virtual gets $t0, next gets
 *       $t1, … until the pool is exhausted. Remaining virtuals are spilled.</li>
 *   <li>Block entry: load the assigned virtuals whose first block access is a read
 *       (those first accessed as writes are defined here — no entry load needed).</li>
 *   <li>Per instruction: assigned virtuals use their physical reg directly; spilled
 *       virtuals borrow a scratch reg and get a load before / store after.</li>
 *   <li>Block exit (before every branch/call, or at fall-through): store all
 *       assigned registers back to their stack slots so memory is authoritative.</li>
 * </ol>
 *
 * <p>Register pools (spec says "only use the 10 temporary registers $t0–$t9"):
 * <ul>
 *   <li>Allocation pool (persistent for the block): $t0–$t6 (int), $f2–$f24 (float)</li>
 *   <li>Scratch/spill pool (per-instruction only):  $t7–$t9 (int), $f26–$f30 (float)</li>
 * </ul>
 * Every virtual register also gets a fixed fp-relative stack slot so spilled values
 * always have a home and block-exit stores always have a target.
 */
public class IntrablockAllocator {
  private enum RegisterClass {
    INT,
    FLOAT
  }

  // Allocation pool: these physical registers hold an assigned virtual for the entire block.
  // $t0-$t6 (7 regs) — the top-7 most-read virtuals per block win a slot here.
  private static final List<Register> INT_REGS = Arrays.asList(
      new Register("$t0", false),
      new Register("$t1", false),
      new Register("$t2", false),
      new Register("$t3", false),
      new Register("$t4", false),
      new Register("$t5", false),
      new Register("$t6", false));

  // Scratch pool: reserved for spilled virtuals that need a load/store within one instruction.
  // Using $t7-$t9 keeps them separate from the allocation pool so they never collide.
  private static final List<Register> INT_SPILL_REGS = Arrays.asList(
      new Register("$t7", false),
      new Register("$t8", false),
      new Register("$t9", false));

  // Float allocation pool: $f2-$f24 even regs ($f0 is the float return register, so start at $f2).
  private static final List<Register> FLOAT_REGS = Arrays.asList(
      // $f0/$f1 reserved for return value; use even-numbered regs (MIPS float pairs)
      new Register("$f2", false),
      new Register("$f4", false),
      new Register("$f6", false),
      new Register("$f8", false),
      new Register("$f10", false),
      new Register("$f14", false),
      new Register("$f16", false),
      new Register("$f18", false),
      new Register("$f20", false),
      new Register("$f22", false),
      new Register("$f24", false));

  // Float scratch pool: $f26-$f30 — same purpose as INT_SPILL_REGS but for float virtuals.
  private static final List<Register> FLOAT_SPILL_REGS = Arrays.asList(
      new Register("$f26", false),
      new Register("$f28", false),
      new Register("$f30", false));

  // output for emitted assembly
  private final PrintStream out;
  // output for emitted assembly in emitted order
  private final Map<Integer, MIPSInstruction> instructions;
  private final Map<String, Integer> labels;
  private int mipsIdx = 0;
  private String pendingLabel = null;

  /**
   * Result of planning a single block.
   *
   * <p>{@code assignedRegs}: the top-N most-read virtuals and the physical register each won.
   * <p>{@code entryLoads}: the subset of assigned virtuals whose first block access is a read —
   * these must be loaded from the stack at block entry because their values come from before
   * the block. Virtuals first accessed as writes are excluded: they are defined here, so loading
   * them would read an uninitialized (or stale) stack slot.
   */
  private static final class BlockPlan {
    private final Map<String, Register> assignedRegs;
    private final Set<String> entryLoads;

    private BlockPlan(Map<String, Register> assignedRegs, Set<String> entryLoads) {
      this.assignedRegs = assignedRegs;
      this.entryLoads = entryLoads;
    }
  }

  // Temporary scratch assignments valid only for one instruction (reset per instruction).
  private static final class ScratchPool {
    private final Map<String, Register> regs = new HashMap<>();
    private int nextInt = 0;
    private int nextFloat = 0;
  }

  public IntrablockAllocator(PrintStream out) {
    this.out = out;

    this.instructions = new LinkedHashMap<>();
    this.labels = new LinkedHashMap<>();
  }

  // regular entry point if there is no label after the function
  public void allocate(IRFunction func, Map<Integer, MIPSInstruction> inputInstructions) {
    allocate(func, inputInstructions, null);
  }

  // overall allocator driver here
  public void allocate(IRFunction func, Map<Integer, MIPSInstruction> inputInstructions,
      String trailingLabel) {
    Register sp = new Register("$sp", false);
    Register fp = new Register("$fp", false);
    Register ra = new Register("$ra", false);

    // get instructions in the same order they were generated
    List<MIPSInstruction> orderedInstructions = orderedInstructions(inputInstructions);

    // every virtual reg still gets a stack slot as backup
    Map<String, Integer> vrMap = buildVirtualRegMap(orderedInstructions);

    // decide which virtual regs use integer regs vs float regs
    Map<String, RegisterClass> vrClasses = buildVrClasses(orderedInstructions);

    // save old frame pointer and return address
    emitPrologue(sp, fp, ra);

    int frameSize = 4 * vrMap.size();
    int totalArrayDataSize = computeTotalArrayDataSize(func);
    Map<String, Integer> arrayBaseOffsets = buildArrayBaseOffsets(func, frameSize, totalArrayDataSize);

    // make room for virtual register spill slots and local array data
    emitAddImm(sp, sp, -(frameSize + totalArrayDataSize));

    // put incoming params and array base pointers into their stack slots
    loadParameters(func, vrMap, fp);
    initArrays(arrayBaseOffsets, vrMap, fp);

    // body jumps here instead of returning directly
    String epilogueLabel = func.name + "_epilogue";
    emitBody(buildBlocks(orderedInstructions), vrMap, vrClasses, fp, epilogueLabel, trailingLabel);
    emitEpilogue(sp, fp, ra, epilogueLabel);
  }

  // methods for seeing state in testing
  public Map<Integer, MIPSInstruction> getInstructions() {
    return instructions;
  }

  public Map<String, Integer> getLabels() {
    return labels;
  }

  // convert map back into a list so we can scan linearly
  private List<MIPSInstruction> orderedInstructions(Map<Integer, MIPSInstruction> inputInstructions) {
    List<Map.Entry<Integer, MIPSInstruction>> entries = new ArrayList<>(inputInstructions.entrySet());
    entries.sort(Map.Entry.comparingByKey());

    List<MIPSInstruction> ordered = new ArrayList<>(entries.size());
    for (Map.Entry<Integer, MIPSInstruction> entry : entries) {
      ordered.add(entry.getValue());
    }
    return ordered;
  }

  // write one final mips instruction
  private void emit(MIPSInstruction instr) {
    if (instr.label != null) {
      labels.put(instr.label, mipsIdx);
    }
    instructions.put(mipsIdx++, instr);
    out.println("   " + instr);
  }

  // labels need to go on the first instruction we emit for an original instruction
  private void emitWithPendingLabel(MIPSInstruction instr) {
    if (pendingLabel != null && instr.label == null) {
      instr.label = pendingLabel;
      pendingLabel = null;
    }
    emit(instr);
  }

  // arrays are stored below the spill slots in the frame
  private int computeTotalArrayDataSize(IRFunction func) {
    Set<String> paramNames = new HashSet<>();
    for (IRVariableOperand p : func.parameters) {
      paramNames.add(p.getName());
    }

    int totalArrayDataSize = 0;
    for (IRVariableOperand var : func.variables) {
      if (var.type instanceof IRArrayType && !paramNames.contains(var.getName())) {
        totalArrayDataSize += ((IRArrayType) var.type).getSize() * 4;
      }
    }
    return totalArrayDataSize;
  }

  // remember where each local array starts relative to fp
  private Map<String, Integer> buildArrayBaseOffsets(IRFunction func, int frameSize,
      int totalArrayDataSize) {
    Set<String> paramNames = new HashSet<>();
    for (IRVariableOperand p : func.parameters) {
      paramNames.add(p.getName());
    }

    List<IRVariableOperand> localArrayVars = new ArrayList<>();
    for (IRVariableOperand var : func.variables) {
      if (var.type instanceof IRArrayType && !paramNames.contains(var.getName())) {
        localArrayVars.add(var);
      }
    }

    Map<String, Integer> arrayBaseOffsets = new LinkedHashMap<>();
    int nextArrayBase = -1 * (frameSize + totalArrayDataSize);
    for (IRVariableOperand var : localArrayVars) {
      String key = "$" + var.getName();
      arrayBaseOffsets.put(key, nextArrayBase);
      nextArrayBase += ((IRArrayType) var.type).getSize() * 4;
    }

    return arrayBaseOffsets;
  }

  // same frame setup as naive allocator
  private void emitPrologue(Register sp, Register fp, Register ra) {
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.SW, null, ra, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.SW, null, fp, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.MOVE, null, fp, sp));
  }

  // assign a stack home for each virtual register
  private Map<String, Integer> buildVirtualRegMap(List<MIPSInstruction> orderedInstructions) {
    Map<String, Integer> vrMap = new HashMap<>();
    int offset = -4;

    for (MIPSInstruction inst : orderedInstructions) {
      for (Register read : inst.getReads()) {
        if (read != null && read.isVirtual && !vrMap.containsKey(read.name)) {
          vrMap.put(read.name, offset);
          offset -= 4;
        }
      }

      Register write = inst.getWrite();
      if (write != null && write.isVirtual && !vrMap.containsKey(write.name)) {
        vrMap.put(write.name, offset);
        offset -= 4;
      }
    }

    return vrMap;
  }

  // work out which physical register file each virtual reg belongs to
  private Map<String, RegisterClass> buildVrClasses(List<MIPSInstruction> orderedInstructions) {
    Map<String, RegisterClass> vrClasses = new HashMap<>();

    for (MIPSInstruction inst : orderedInstructions) {
      switch (inst.op) {
        // float math/comparison ops use $f registers
        case LI_S, MOV_S, ADD_S, ADDI_S, SUB_S, MUL_S, DIV_S,
            C_EQ_S, C_NE_S, C_LT_S, C_GT_S, C_GE_S -> markFloatRegisters(inst, vrClasses);
        case L_S -> {
          markVirtualRegister((Register) inst.operands.get(MIPSInstruction.R_D_LW),
              RegisterClass.FLOAT, vrClasses);
          markAddressBase((Addr) inst.operands.get(MIPSInstruction.ADDR_LW),
              RegisterClass.INT, vrClasses);
        }
        case S_S -> {
          markVirtualRegister((Register) inst.operands.get(MIPSInstruction.R_S_SW),
              RegisterClass.FLOAT, vrClasses);
          markAddressBase((Addr) inst.operands.get(MIPSInstruction.ADDR_SW),
              RegisterClass.INT, vrClasses);
        }
        // everything else uses normal integer registers by default
        default -> markIntRegisters(inst, vrClasses);
      }
    }

    return vrClasses;
  }

  // mark all register operands in a float instruction as float regs
  private void markFloatRegisters(MIPSInstruction inst, Map<String, RegisterClass> vrClasses) {
    for (MIPSOperand operand : inst.operands) {
      if (operand instanceof Register) {
        markVirtualRegister((Register) operand, RegisterClass.FLOAT, vrClasses);
      }
    }
  }

  // mark normal register operands and address bases as integer regs
  private void markIntRegisters(MIPSInstruction inst, Map<String, RegisterClass> vrClasses) {
    for (MIPSOperand operand : inst.operands) {
      if (operand instanceof Register) {
        markVirtualRegister((Register) operand, RegisterClass.INT, vrClasses);
      } else if (operand instanceof Addr) {
        markAddressBase((Addr) operand, RegisterClass.INT, vrClasses);
      }
    }
  }

  // only virtual registers need classification
  private void markVirtualRegister(Register reg, RegisterClass registerClass,
      Map<String, RegisterClass> vrClasses) {
    if (reg != null && reg.isVirtual) {
      vrClasses.putIfAbsent(reg.name, registerClass);
    }
  }

  private void markAddressBase(Addr addr, RegisterClass registerClass,
      Map<String, RegisterClass> vrClasses) {
    if (addr.register != null) {
      markVirtualRegister(addr.register, registerClass, vrClasses);
    }
  }

  // copy params from the caller area into our virtual register slots
  private void loadParameters(IRFunction func, Map<String, Integer> vrMap, Register fp) {
    for (int i = 0; i < func.parameters.size(); i++) {
      String paramKey = "$" + func.parameters.get(i).getName();
      if (!vrMap.containsKey(paramKey)) {
        continue;
      }
      Register t = new Register("$t0", false);
      emit(new MIPSInstruction(MIPSOp.LW, null, t,
          new Addr(new Imm(String.valueOf(8 + i * 4), "DEC"), fp)));
      emit(new MIPSInstruction(MIPSOp.SW, null, t,
          new Addr(new Imm(String.valueOf(vrMap.get(paramKey)), "DEC"), fp)));
    }
  }

  // initialize local array vars with pointers to their data area
  private void initArrays(Map<String, Integer> arrayBaseOffsets,
      Map<String, Integer> vrMap, Register fp) {
    Register t0 = new Register("$t0", false);
    for (Map.Entry<String, Integer> entry : arrayBaseOffsets.entrySet()) {
      String varKey = entry.getKey();
      int dataFpOffset = entry.getValue();
      if (!vrMap.containsKey(varKey)) {
        continue;
      }
      emitAddImm(t0, fp, dataFpOffset);
      emit(new MIPSInstruction(MIPSOp.SW, null, t0,
          new Addr(new Imm(String.valueOf(vrMap.get(varKey)), "DEC"), fp)));
    }
  }

  /**
   * Partition instructions into basic blocks (spec §1.2.2, step 1).
   *
   * <p>Block boundaries follow the standard rule:
   * <ul>
   *   <li>A labeled instruction starts a new block — it may be a branch target from elsewhere.</li>
   *   <li>A branch, jump, or call ends the current block. Calls additionally end the block
   *       because they are ABI entry points: the callee may overwrite every $t register, so
   *       any value kept in a $t across a call would be silently clobbered.</li>
   * </ul>
   */
  private List<List<MIPSInstruction>> buildBlocks(List<MIPSInstruction> orderedInstructions) {
    List<List<MIPSInstruction>> blocks = new ArrayList<>();
    List<MIPSInstruction> current = new ArrayList<>();

    for (MIPSInstruction inst : orderedInstructions) {
      // Label = potential branch target → anything before it belongs to the previous block.
      if (inst.label != null && !current.isEmpty()) {
        blocks.add(current);
        current = new ArrayList<>();
      }

      current.add(inst);

      // Branch/jump/call ends this block; the next instruction belongs to a fresh block.
      if (endsAllocationBlock(inst)) {
        blocks.add(current);
        current = new ArrayList<>();
      }
    }

    if (!current.isEmpty()) {
      blocks.add(current);
    }

    return blocks;
  }

  // things that end the current allocation block
  private boolean endsAllocationBlock(MIPSInstruction inst) {
    return inst.isBranch()
        || inst.op == MIPSOp.J
        || inst.op == MIPSOp.JR
        || inst.op == MIPSOp.JAL
        || inst.op == MIPSOp.BC1T
        || inst.op == MIPSOp.BC1F;
  }

  // allocate each block independently
  private void emitBody(List<List<MIPSInstruction>> blocks, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, String epilogueLabel,
      String trailingLabel) {
    for (List<MIPSInstruction> block : blocks) {
      emitBlock(block, vrMap, vrClasses, fp, epilogueLabel);
    }

    // branch bootstrap code can pass a label that should fall through to epilogue
    if (trailingLabel != null) {
      emit(new MIPSInstruction(MIPSOp.J, trailingLabel, new Addr(epilogueLabel)));
    }
  }

  /**
   * Emit all physical instructions for one basic block (spec §1.2.2, steps 3-6).
   *
   * <p>Three phases:
   * <ol>
   *   <li><b>Entry loads</b> (step 3/4): load the assigned virtuals that are live-in
   *       (first accessed as reads) from their stack slots into their physical regs.</li>
   *   <li><b>Body</b> (step 5): translate each instruction; assigned virtuals use
   *       their physical reg directly; spilled virtuals borrow a scratch reg.</li>
   *   <li><b>Exit stores</b> (step 4/6): before every block-ending instruction (branch,
   *       jump, call) — and at fall-through — store all assigned physical regs back to
   *       their stack slots so memory is the source of truth for the next block.</li>
   * </ol>
   */
  private void emitBlock(List<MIPSInstruction> block, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, String epilogueLabel) {
    BlockPlan plan = planBlock(block, vrClasses);
    // activeAssigned tracks which assigned virtuals have been loaded (and possibly dirtied),
    // so the exit-store pass only spills regs that were actually touched this block.
    Set<String> activeAssigned = new HashSet<>();
    boolean storedAtExit = false;

    // Phase 1: entry loads. The block's label (if any) must land on the first emitted instruction.
    pendingLabel = block.get(0).label;
    preloadAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);

    for (int i = 0; i < block.size(); i++) {
      MIPSInstruction inst = block.get(i);
      if (i > 0) {
        pendingLabel = inst.label;
      }

      // JR = RETURN: redirect through shared epilogue so stack cleanup always runs.
      if (inst.op == MIPSOp.JR) {
        // Phase 3 (exit stores) before leaving the block.
        storeAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);
        emitWithPendingLabel(new MIPSInstruction(MIPSOp.J, null, new Addr(epilogueLabel)));
        storedAtExit = true;
        break;
      }

      // Any other block-ender (branch/jump/call): stores first, then the control-flow instruction,
      // so the callee/target always sees up-to-date stack values (spec §1.2.2 step 6).
      if (endsAllocationBlock(inst)) {
        storeAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);
        emitAllocatedInstruction(inst, plan, activeAssigned, vrMap, vrClasses, fp);
        storedAtExit = true;
        break;
      }

      // Phase 2: body — emit with physical register substitution.
      emitAllocatedInstruction(inst, plan, activeAssigned, vrMap, vrClasses, fp);
    }

    // Phase 3 for fall-through blocks: the block has no explicit terminator, but the next
    // block may not assign the same physical registers, so we must flush to the stack here.
    pendingLabel = null;
    if (!storedAtExit) {
      storeAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);
    }
  }

  /**
   * Plan register assignment for one block (spec §1.2.2, step 2-3).
   * Runs the greedy pass for each register class, then determines which assigned
   * virtuals need an entry load.
   */
  private BlockPlan planBlock(List<MIPSInstruction> block, Map<String, RegisterClass> vrClasses) {
    Map<String, Register> assignedRegs = new LinkedHashMap<>();
    assignBlockRegisters(block, vrClasses, RegisterClass.INT, INT_REGS, assignedRegs);
    assignBlockRegisters(block, vrClasses, RegisterClass.FLOAT, FLOAT_REGS, assignedRegs);
    return new BlockPlan(assignedRegs, findEntryLoads(block, assignedRegs));
  }

  /**
   * Greedy assignment (spec §1.2.2 step 2): rank the virtuals by read count and hand out
   * physical registers from the pool in order until the pool is exhausted.
   * Virtuals that don't win a slot are spilled (handled per-instruction in scratch regs).
   */
  private void assignBlockRegisters(List<MIPSInstruction> block,
      Map<String, RegisterClass> vrClasses, RegisterClass registerClass,
      List<Register> pool, Map<String, Register> assignedRegs) {
    List<String> ranked = rankBlockVirtuals(block, vrClasses, registerClass);
    int limit = Math.min(ranked.size(), pool.size());
    for (int i = 0; i < limit; i++) {
      assignedRegs.put(ranked.get(i), pool.get(i));
    }
  }

  /**
   * Count reads of each virtual register in the block and sort descending (spec §1.2.2).
   * The spec says "assign a register to the live range with the maximum number of uses" —
   * we count only reads because a pure write-only register gains nothing from staying in a
   * physical register across instructions (it has no values to re-read).
   * Write-only virtuals get count 0 so they still appear in the ranked list (they participate
   * in firstSeen tie-breaking) but lose to any virtual that is actually read.
   */
  private List<String> rankBlockVirtuals(List<MIPSInstruction> block,
      Map<String, RegisterClass> vrClasses, RegisterClass registerClass) {
    Map<String, Integer> useCounts = new HashMap<>();
    Map<String, Integer> firstSeen = new HashMap<>();
    int seenIndex = 0;

    for (MIPSInstruction inst : block) {
      for (Register read : inst.getReads()) {
        if (read != null && read.isVirtual
            && registerClassOf(read.name, vrClasses) == registerClass) {
          useCounts.put(read.name, useCounts.getOrDefault(read.name, 0) + 1);
          firstSeen.putIfAbsent(read.name, seenIndex++);
        }
      }

      // track the write so it appears in firstSeen (for tie-breaking), but
      // only count it as a use if it is also read — a pure def with no reads
      // in this block doesn't benefit from staying in a register
      Register write = inst.getWrite();
      if (write != null && write.isVirtual
          && registerClassOf(write.name, vrClasses) == registerClass) {
        firstSeen.putIfAbsent(write.name, seenIndex++);
        useCounts.putIfAbsent(write.name, 0);
      }
    }

    List<String> ranked = new ArrayList<>(useCounts.keySet());
    ranked.sort((left, right) -> {
      int countCmp = Integer.compare(useCounts.get(right), useCounts.get(left));
      if (countCmp != 0) {
        return countCmp;
      }

      int seenCmp = Integer.compare(firstSeen.get(left), firstSeen.get(right));
      if (seenCmp != 0) {
        return seenCmp;
      }

      return left.compareTo(right);
    });
    return ranked;
  }

  /**
   * Determine which assigned virtuals need a load at block entry (spec §1.2.2 step 3).
   * "At the start of each block, load a set of variables that you expect to use."
   * Only virtuals whose first access in the block is a read need loading — they carry a
   * value from before the block. Virtuals whose first access is a write are defined here,
   * so there is nothing to load (loading would read a stale or uninitialized stack slot).
   */
  private Set<String> findEntryLoads(List<MIPSInstruction> block, Map<String, Register> assignedRegs) {
    Set<String> entryLoads = new HashSet<>();
    Set<String> seen = new HashSet<>();

    for (MIPSInstruction inst : block) {
      for (Register read : inst.getReads()) {
        if (read != null && read.isVirtual && assignedRegs.containsKey(read.name)
            && !seen.contains(read.name)) {
          entryLoads.add(read.name);
          seen.add(read.name);
        }
      }

      Register write = inst.getWrite();
      if (write != null && write.isVirtual && assignedRegs.containsKey(write.name)) {
        seen.add(write.name);
      }
    }

    return entryLoads;
  }

  // Block entry loads (spec §1.2.2 step 3): bring live-in assigned virtuals from stack into
  // their physical registers so the block body can use them without per-instruction loads.
  private void preloadAssignedRegisters(BlockPlan plan, Set<String> activeAssigned,
      Map<String, Integer> vrMap, Map<String, RegisterClass> vrClasses, Register fp) {
    List<String> entryLoads = new ArrayList<>(plan.entryLoads);
    entryLoads.sort(Comparator.comparingInt(vrMap::get));

    for (String vrName : entryLoads) {
      Register phys = plan.assignedRegs.get(vrName);
      emitWithPendingLabel(new MIPSInstruction(loadOpFor(registerClassOf(vrName, vrClasses)), null,
          phys, frameAddress(vrMap.get(vrName), fp)));
      activeAssigned.add(vrName);
    }
  }

  // Block exit stores (spec §1.2.2 step 4): "at the end of each block, store all values from
  // your registers to memory." We flush only activeAssigned (virtuals that were actually loaded
  // or written this block) to avoid spurious stores of regs we never touched.
  private void storeAssignedRegisters(BlockPlan plan, Set<String> activeAssigned,
      Map<String, Integer> vrMap, Map<String, RegisterClass> vrClasses, Register fp) {
    List<String> vrNames = new ArrayList<>(activeAssigned);
    vrNames.sort(Comparator.comparingInt(vrMap::get));

    for (String vrName : vrNames) {
      spillVirtual(vrName, plan.assignedRegs.get(vrName), vrMap, vrClasses, fp);
    }
  }

  /**
   * Translate one virtual-register instruction into physical-register instructions (spec §1.2.2 step 5).
   *
   * <p>For each virtual read:
   * <ul>
   *   <li>Assigned virtual → use its block-persistent physical reg directly (no load).</li>
   *   <li>Spilled virtual → borrow a scratch reg ($t7-$t9) and emit a load from the stack.</li>
   * </ul>
   * For the virtual write:
   * <ul>
   *   <li>Assigned virtual → write directly into its physical reg; mark it active so the
   *       exit-store pass knows to flush it.</li>
   *   <li>Spilled virtual → write into a scratch reg, then immediately store to the stack
   *       after the instruction (per-instruction store, as in naive allocation).</li>
   * </ul>
   */
  private void emitAllocatedInstruction(MIPSInstruction inst, BlockPlan plan,
      Set<String> activeAssigned, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp) {
    Register[] reads = inst.getReads();
    Register[] physReads = new Register[reads.length];
    ScratchPool scratch = new ScratchPool();
    Set<String> spilledWrites = new HashSet<>();

    for (int i = 0; i < reads.length; i++) {
      Register read = reads[i];
      if (read != null && read.isVirtual) {
        physReads[i] = registerForRead(read.name, plan, activeAssigned, scratch,
            vrMap, vrClasses, fp);
      } else {
        physReads[i] = read;
      }
    }

    Register write = inst.getWrite();
    Register physWrite = write;
    if (write != null && write.isVirtual) {
      if (plan.assignedRegs.containsKey(write.name)) {
        // Assigned virtual: write into its persistent physical reg.
        physWrite = plan.assignedRegs.get(write.name);
        activeAssigned.add(write.name);
      } else {
        // Spilled virtual: write into scratch, store to stack immediately after.
        physWrite = registerForWrite(write.name, scratch, vrClasses);
        spilledWrites.add(write.name);
      }
    }

    emitWithPendingLabel(substituteRegisters(inst, physReads, physWrite));

    // Per-instruction store for spilled writes (same as naive for these virtuals).
    List<String> writesToStore = new ArrayList<>(spilledWrites);
    writesToStore.sort(Comparator.comparingInt(vrMap::get));
    for (String vrName : writesToStore) {
      spillVirtual(vrName, scratch.regs.get(vrName), vrMap, vrClasses, fp);
    }
  }

  /**
   * Resolve a virtual read to a physical register, loading from the stack if necessary.
   *
   * <p>Two cases (spec §1.2.2 step 5):
   * <ol>
   *   <li>Assigned virtual: return its persistent physical reg. The entry-load pass should
   *       have already loaded it, but if not (e.g. a write-first virtual that later gets
   *       read again), emit a lazy load here so correctness is guaranteed.</li>
   *   <li>Spilled virtual: allocate a scratch reg from $t7-$t9 and emit a load from the
   *       stack. The scratch reg is only valid for this one instruction's operand list.</li>
   * </ol>
   */
  private Register registerForRead(String vrName, BlockPlan plan, Set<String> activeAssigned,
      ScratchPool scratch, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp) {
    if (plan.assignedRegs.containsKey(vrName)) {
      Register phys = plan.assignedRegs.get(vrName);

      // Assigned but not yet active — write-first virtual being read after its definition.
      if (!activeAssigned.contains(vrName)) {
        emitWithPendingLabel(new MIPSInstruction(loadOpFor(registerClassOf(vrName, vrClasses)), null,
            phys, frameAddress(vrMap.get(vrName), fp)));
        activeAssigned.add(vrName);
      }
      return phys;
    }

    if (scratch.regs.containsKey(vrName)) {
      return scratch.regs.get(vrName);
    }

    RegisterClass registerClass = registerClassOf(vrName, vrClasses);
    Register phys = nextScratchRegister(vrName, registerClass, scratch);
    emitWithPendingLabel(new MIPSInstruction(loadOpFor(registerClass), null,
        phys, frameAddress(vrMap.get(vrName), fp)));
    return phys;
  }

  // writes to spilled virtuals just use one of the reserved scratch regs
  private Register registerForWrite(String vrName, ScratchPool scratch,
      Map<String, RegisterClass> vrClasses) {
    if (scratch.regs.containsKey(vrName)) {
      return scratch.regs.get(vrName);
    }
    return nextScratchRegister(vrName, registerClassOf(vrName, vrClasses), scratch);
  }

  // grab the next scratch reg for this one instruction
  private Register nextScratchRegister(String vrName, RegisterClass registerClass,
      ScratchPool scratch) {
    if (scratch.regs.containsKey(vrName)) {
      return scratch.regs.get(vrName);
    }

    List<Register> pool = scratchPoolFor(registerClass);
    int nextIdx;
    if (registerClass == RegisterClass.FLOAT) {
      nextIdx = scratch.nextFloat++;
    } else {
      nextIdx = scratch.nextInt++;
    }

    if (nextIdx >= pool.size()) {
      throw new IllegalStateException("ran out of spill scratch regs for " + registerClass);
    }

    Register phys = pool.get(nextIdx);
    scratch.regs.put(vrName, phys);
    return phys;
  }

  // actual store instruction for a virtual register
  private void spillVirtual(String vrName, Register phys, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp) {
    emitWithPendingLabel(new MIPSInstruction(storeOpFor(registerClassOf(vrName, vrClasses)), null,
        phys, frameAddress(vrMap.get(vrName), fp)));
  }

  // emits dest = src + imm, using LI+ADD/SUB when imm falls outside 16-bit signed range
  private void emitAddImm(Register dest, Register src, int imm) {
    if (imm >= -32768 && imm <= 32767) {
      emit(new MIPSInstruction(MIPSOp.ADDI, null, dest, src,
          new Imm(String.valueOf(imm), "DEC")));
    } else {
      Register tmp = new Register("$t9", false);
      emit(new MIPSInstruction(MIPSOp.LI, null, tmp,
          new Imm(String.valueOf(Math.abs(imm)), "DEC")));
      MIPSOp op = imm < 0 ? MIPSOp.SUB : MIPSOp.ADD;
      emit(new MIPSInstruction(op, null, dest, src, tmp));
    }
  }

  // helper for offset($fp)
  private Addr frameAddress(int offset, Register fp) {
    return new Addr(new Imm(String.valueOf(offset), "DEC"), fp);
  }

  // default to int if we never saw a float use
  private RegisterClass registerClassOf(String vrName, Map<String, RegisterClass> vrClasses) {
    return vrClasses.getOrDefault(vrName, RegisterClass.INT);
  }

  // choose the matching load opcode
  private MIPSOp loadOpFor(RegisterClass registerClass) {
    return registerClass == RegisterClass.FLOAT ? MIPSOp.L_S : MIPSOp.LW;
  }

  // choose the matching store opcode
  private MIPSOp storeOpFor(RegisterClass registerClass) {
    return registerClass == RegisterClass.FLOAT ? MIPSOp.S_S : MIPSOp.SW;
  }

  // choose the scratch pool for spilled values
  private List<Register> scratchPoolFor(RegisterClass registerClass) {
    return registerClass == RegisterClass.FLOAT ? FLOAT_SPILL_REGS : INT_SPILL_REGS;
  }

  // build a copy of the instruction with physical regs substituted in
  private static MIPSInstruction substituteRegisters(
      MIPSInstruction inst, Register[] physReads, Register physWrite) {
    Register[] virtualReads = inst.getReads();
    Map<String, Register> sub = new HashMap<>();
    for (int i = 0; i < virtualReads.length; i++) {
      if (virtualReads[i] != null && virtualReads[i].isVirtual) {
        sub.put(virtualReads[i].name, physReads[i]);
      }
    }

    Register virtualWrite = inst.getWrite();
    if (virtualWrite != null && virtualWrite.isVirtual && physWrite != null) {
      sub.put(virtualWrite.name, physWrite);
    }

    List<MIPSOperand> newOps = new ArrayList<>();
    for (MIPSOperand op : inst.operands) {
      newOps.add(substituteOperand(op, sub));
    }
    return new MIPSInstruction(inst.op, null, newOps.toArray(new MIPSOperand[0]));
  }

  // replace one operand if it contains a virtual register
  private static MIPSOperand substituteOperand(MIPSOperand operand, Map<String, Register> sub) {
    if (operand instanceof Register) {
      Register r = (Register) operand;
      if (!r.isVirtual) {
        return r;
      }
      return sub.getOrDefault(r.name, r);
    }
    if (operand instanceof Addr) {
      Addr addr = (Addr) operand;
      if (addr.register == null || !addr.register.isVirtual) {
        return addr;
      }
      Register newReg = sub.getOrDefault(addr.register.name, addr.register);
      return addr.mode == Addr.Mode.BASE_OFFSET
          ? new Addr(addr.constant, newReg)
          : new Addr(newReg);
    }
    return operand;
  }

  // restore caller frame and return
  private void emitEpilogue(Register sp, Register fp, Register ra, String epilogueLabel) {
    emit(new MIPSInstruction(MIPSOp.MOVE, epilogueLabel, sp, fp));
    emit(new MIPSInstruction(MIPSOp.LW, null, fp, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.LW, null, ra, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.JR, null, ra));
  }
}
