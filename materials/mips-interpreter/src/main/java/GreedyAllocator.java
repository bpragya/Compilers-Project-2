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

/*
 * Intrablock greedy register allocator.
 *
 * The allocator keeps the most-read virtual registers in physical registers
 * for the duration of one basic block. Values that do not get selected are
 * spilled and use short-lived scratch registers around each instruction.
 *
 * The integer register split is:
 *
 *   $t0-$t7  -->  selected: held for the entire block
 *   $t8-$t9  -->  scratch: loaned out per instruction, immediately returned
 *
 * With only two scratch registers, a spilled write would reuse one of the spilled
 * read scratch registers for its destination. That is safe for the
 * MIPS instructions, because a destination register may be the same as
 * one of the source registers such as "add $t8, $t8, $t9". Plus, there can only be two read registers
 * at once per the MIPS ISA.
 */
public class GreedyAllocator {

  // the registers we hand out to the most-used virtuals in a block
  private static final List<Register> SELECTED_REGS = Arrays.asList(
      new Register("$t0", false), new Register("$t1", false),
      new Register("$t2", false), new Register("$t3", false),
      new Register("$t4", false), new Register("$t5", false),
      new Register("$t6", false), new Register("$t7", false));

  // reserved for spilled virtuals that need to briefly exist during one instruction
  private static final List<Register> SCRATCH_REGS = Arrays.asList(
      new Register("$t8", false), new Register("$t9", false));

  private final PrintStream out;
  private final Map<Integer, MIPSInstruction> instructions = new LinkedHashMap<>();
  private final Map<String, Integer> labels = new LinkedHashMap<>();
  private int emitIdx = 0;
  private String pendingLabel = null;

  /*
   * what we decide before processing a block:
   * a.) selected    --> which virtual gets which physical register for this block
   * b.) loadAtEntry --> the selected virtuals we need to load at the start (only ones that are read before being written)
   */
  private static class BlockPlan {
    final Map<String, Register> selected;
    final Set<String> loadAtEntry;

    BlockPlan(Map<String, Register> selected, Set<String> loadAtEntry) {
      this.selected = selected;
      this.loadAtEntry = loadAtEntry;
    }
  }

  // tracks which scratch registers we've handed out within the current instruction
  // gets thrown away and recreated fresh for every instruction
  private static class Scratch {
    final Map<String, Register> assigned = new HashMap<>();
    int nextInt = 0;
  }

  public GreedyAllocator(PrintStream out) {
    this.out = out;
  }

  public void allocate(IRFunction func, Map<Integer, MIPSInstruction> input) {
    allocate(func, input, null);
  }

  public void allocate(IRFunction func, Map<Integer, MIPSInstruction> input, String trailingLabel) {
    Register sp = new Register("$sp", false);
    Register fp = new Register("$fp", false);
    Register ra = new Register("$ra", false);

    List<MIPSInstruction> instrs = sortedInstructions(input);

    // every virtual gets a stack slot, selected virtuals use it at block boundaries,
    Map<String, Integer> stackSlot = assignStackSlots(instrs);
    // spilled virtuals use it on every instruction just like the naive allocator

    emitPrologue(sp, fp, ra);

    int frameSize = 4 * stackSlot.size();
    int arrayDataSize = localArrayDataSize(func);
    Map<String, Integer> arrayBase = localArrayBases(func, frameSize, arrayDataSize);

    emitAddImm(sp, sp, -(frameSize + arrayDataSize));

    copyParamsToStack(func, stackSlot, fp);
    initLocalArrays(arrayBase, stackSlot, fp);

    String epilogue = func.name + "_epilogue";
    List<List<MIPSInstruction>> blocks = splitIntoBlocks(instrs);
    for (List<MIPSInstruction> block : blocks) {
      processBlock(block, stackSlot, fp, epilogue);
    }
    if (trailingLabel != null) {
      emit(new MIPSInstruction(MIPSOp.J, trailingLabel, new Addr(epilogue)));
    }

    emitEpilogue(sp, fp, ra, epilogue);
  }

  public Map<Integer, MIPSInstruction> getInstructions() { return instructions; }
  public Map<String, Integer> getLabels() { return labels; }

  /*
   * A block starts whenever we hit a label (someone might jump here) and ends at any branch, jump, or call.
   * Calls end blocks too because the callee is free to trash all $t registers.
   */
  private List<List<MIPSInstruction>> splitIntoBlocks(List<MIPSInstruction> instrs) {
    List<List<MIPSInstruction>> blocks = new ArrayList<>();
    List<MIPSInstruction> current = new ArrayList<>();

    for (MIPSInstruction inst : instrs) {
      if (inst.label != null && !current.isEmpty()) {
        blocks.add(current);
        current = new ArrayList<>();
      }
      current.add(inst);
      if (isBlockEnd(inst)) {
        blocks.add(current);
        current = new ArrayList<>();
      }
    }
    if (!current.isEmpty()) blocks.add(current);
    return blocks;
  }

  private boolean isBlockEnd(MIPSInstruction inst) {
    return inst.isBranch()
        || inst.op == MIPSOp.J
        || inst.op == MIPSOp.JR
        || inst.op == MIPSOp.JAL
        || inst.op == MIPSOp.BC1T
        || inst.op == MIPSOp.BC1F;
  }

  private void processBlock(List<MIPSInstruction> block, Map<String, Integer> stackSlot,
      Register fp, String epilogue) {

    BlockPlan plan = planBlock(block);

    // "dirty" tracks which selected registers have been touched this block
    // we only flush the ones we've actually written into
    Set<String> dirty = new HashSet<>();
    boolean flushedAlready = false;

    // if this block has a label, it must go on the very first instruction we emit
    pendingLabel = block.get(0).label;
    loadEntryRegisters(plan, dirty, stackSlot, fp);

    for (int i = 0; i < block.size(); i++) {
      MIPSInstruction inst = block.get(i);
      if (i > 0) pendingLabel = inst.label;

      if (inst.op == MIPSOp.JR) {
        // return: write everything back before jumping to the epilogue for frame cleanup
        flushToStack(plan, dirty, stackSlot, fp);
        emitLabeled(new MIPSInstruction(MIPSOp.J, null, new Addr(epilogue)));
        flushedAlready = true;
        break;
      }

      if (isBlockEnd(inst)) {
        // leaving the block: flush first so whoever we jump to sees up-to-date stack values
        flushToStack(plan, dirty, stackSlot, fp);
        translateInstruction(inst, plan, dirty, stackSlot, fp);
        flushedAlready = true;
        break;
      }

      translateInstruction(inst, plan, dirty, stackSlot, fp);
    }

    // fall-through: next block starts fresh so we still need to write back
    pendingLabel = null;
    if (!flushedAlready) {
      flushToStack(plan, dirty, stackSlot, fp);
    }
  }

  private BlockPlan planBlock(List<MIPSInstruction> block) {
    Map<String, Register> selected = new LinkedHashMap<>();
    greedySelect(block, SELECTED_REGS, selected);
    return new BlockPlan(selected, findLiveIns(block, selected));
  }

  /*
   * count how many times each virtual is read, then hand out registers to the most-read ones first until we run out of registers.
   * we only count reads (uses, number of loads).
   */
  private void greedySelect(List<MIPSInstruction> block, List<Register> pool,
      Map<String, Register> selected) {
    Map<String, Integer> readCount = new HashMap<>(); // register name, mapped with freq
    Map<String, Integer> firstSeen = new HashMap<>(); // register name, mapped with freq
    int seenIdx = 0;
    // walk the block once and record:
    // a.) readCount  --> how often each virtual register is read
    // b.) firstSeen  --> the order each virtual first appeared (used as a tiebreaker)
    for (MIPSInstruction inst : block) {
      for (Register r : inst.getReads()) {
        if (r == null || !r.isVirtual) continue;
        // increase this virtual's read count by 1.
        // first time we see it, getOrDefault returns 0 so it ends up at 1, so its still counted.
        int previousCount = readCount.getOrDefault(r.name, 0);
        readCount.put(r.name, previousCount + 1);
        // tie-breaker logic. only record the first time we see this virtual, so have this to break ties.
        if (!firstSeen.containsKey(r.name)) {
          firstSeen.put(r.name, seenIdx++);
        }
      }
      Register w = inst.getWrite();
      if (w != null && w.isVirtual) {
        if (!firstSeen.containsKey(w.name)) {
          firstSeen.put(w.name, seenIdx++);
        }
        // write-only virtuals enter the ranking at zero reads. they're still here, but they won't interfere with frequency count, since they're below anything that gets read at least once.
        if (!readCount.containsKey(w.name)) {
          readCount.put(w.name, 0);
        }
      }
    }
    List<String> ranked = rankByReadCount(readCount, firstSeen);
    // assign the most-used virtuals to physical registers until the pool runs out
    int handouts = Math.min(ranked.size(), pool.size());
    for (int i = 0; i < handouts; i++) {
      selected.put(ranked.get(i), pool.get(i));
    }
  }
  // rank virtuals by how useful they are to keep in a register.
  // more reads means higher priority. ties broken through which comes first in block, and if they manage same order (through src operands) then by alphabetical order of name.
  private static List<String> rankByReadCount(Map<String, Integer> readCount,
      Map<String, Integer> firstSeen) {
    List<String> ranked = new ArrayList<>(readCount.keySet());
    ranked.sort((a, b) -> {
      int byReadCount = Integer.compare(readCount.get(b), readCount.get(a));
      if (byReadCount != 0) return byReadCount;

      int byAppearance = Integer.compare(firstSeen.get(a), firstSeen.get(b));
      if (byAppearance != 0) return byAppearance;

      return a.compareTo(b);
    });
    return ranked;
  }

  /*
   * which selected virtuals do we need to load at block entry...
   * only the ones whose first touch in this block is a read,
   * those are carrying a value in from before the block.
   * if the first touch is a write, we're defining it fresh here,
   * so loading from the stack would just pull in garbage.
   */
  private Set<String> findLiveIns(List<MIPSInstruction> block, Map<String, Register> selected) {
    Set<String> liveIns = new HashSet<>();
    Set<String> seen = new HashSet<>();

    for (MIPSInstruction inst : block) {
      for (Register r : inst.getReads()) {
        if (r != null && r.isVirtual && selected.containsKey(r.name) && seen.add(r.name)) {
          liveIns.add(r.name);
        }
      }
      Register w = inst.getWrite();
      if (w != null && w.isVirtual && selected.containsKey(w.name)) {
        seen.add(w.name);
      }
    }
    return liveIns;
  }

  private void loadEntryRegisters(BlockPlan plan, Set<String> dirty,
      Map<String, Integer> stackSlot, Register fp) {
    List<String> toLoad = new ArrayList<>(plan.loadAtEntry);
    toLoad.sort(Comparator.comparingInt(stackSlot::get));
    for (String vr : toLoad) {
      emitLabeled(new MIPSInstruction(MIPSOp.LW, null,
          plan.selected.get(vr), slot(stackSlot.get(vr), fp)));
      dirty.add(vr);
    }
  }

  // write all the selected registers we touched back to their stack slots -
  // we only flush dirty ones, no point storing a register we never touched
  private void flushToStack(BlockPlan plan, Set<String> dirty,
      Map<String, Integer> stackSlot, Register fp) {
    List<String> toFlush = new ArrayList<>(dirty);
    toFlush.sort(Comparator.comparingInt(stackSlot::get));
    for (String vr : toFlush) {
      emitLabeled(new MIPSInstruction(MIPSOp.SW, null,
          plan.selected.get(vr), slot(stackSlot.get(vr), fp)));
    }
  }

  /*
   * Translate one virtual-register instruction into real registers:
   * a.) selected virtual  -->  just use its physical register, it's already there
   * b.) spilled virtual   -->  borrow scratch, use it for this instruction, then return it
   */
  private void translateInstruction(MIPSInstruction inst, BlockPlan plan, Set<String> dirty,
      Map<String, Integer> stackSlot, Register fp) {

    Register[] vReads = inst.getReads();
    Register[] pReads = new Register[vReads.length];
    Scratch scratch = new Scratch();
    Set<String> spilledWrites = new HashSet<>();

    for (int i = 0; i < vReads.length; i++) {
      Register r = vReads[i];
      if (r != null && r.isVirtual) {
        pReads[i] = resolveRead(r.name, plan, dirty, scratch, stackSlot, fp);
      } else {
        pReads[i] = r;
      }
    }

    Register vWrite = inst.getWrite();
    Register pWrite = vWrite;
    if (vWrite != null && vWrite.isVirtual) {
      if (plan.selected.containsKey(vWrite.name)) {
        pWrite = plan.selected.get(vWrite.name);
        dirty.add(vWrite.name); // we've written into this register now, so flush it at exit
      } else {
        pWrite = scratchForWrite(vWrite.name, scratch);
        spilledWrites.add(vWrite.name);
      }
    }

    emitLabeled(substituteRegs(inst, vReads, pReads, vWrite, pWrite));

    // spilled writes need to go back to the stack right after the instruction
    List<String> toStore = new ArrayList<>(spilledWrites);
    toStore.sort(Comparator.comparingInt(stackSlot::get));
    for (String vr : toStore) {
      emitLabeled(new MIPSInstruction(MIPSOp.SW, null,
          scratch.assigned.get(vr), slot(stackSlot.get(vr), fp)));
    }
  }

  /*
   * Get the physical register to use for reading a virtual:
   *   if it's selected, return its register (and load from stack if we haven't yet)
   *   if it's spilled, grab a scratch register and load the value in
   */
  private Register resolveRead(String vr, BlockPlan plan, Set<String> dirty,
      Scratch scratch, Map<String, Integer> stackSlot, Register fp) {

    if (plan.selected.containsKey(vr)) {
      Register phys = plan.selected.get(vr);
      if (!dirty.contains(vr)) {
        // this virtual was written before being read - load its value back now
        emitLabeled(new MIPSInstruction(MIPSOp.LW, null, phys, slot(stackSlot.get(vr), fp)));
        dirty.add(vr);
      }
      return phys;
    }

    // already handed out a scratch reg for this virtual earlier in this instruction
    if (scratch.assigned.containsKey(vr)) return scratch.assigned.get(vr);

    // spilled: borrow a scratch reg and load from the stack
    Register phys = borrowScratch(vr, scratch);
    emitLabeled(new MIPSInstruction(MIPSOp.LW, null, phys, slot(stackSlot.get(vr), fp)));
    return phys;
  }

  /*
   * Pick a register for a spilled write. if both scratch regs are already holding
   * spilled reads, reuse one of those read registers as the destination. this
   * two-scratch instructions valid without changing the load/store behavior.
   */
  private Register scratchForWrite(String vr, Scratch scratch) {
    if (scratch.assigned.containsKey(vr)) return scratch.assigned.get(vr);
    if (hasScratchLeft(scratch)) return borrowScratch(vr, scratch);

    Register reused = firstBorrowedScratch(scratch);
    if (reused == null) throw new IllegalStateException("out of scratch regs");
    scratch.assigned.put(vr, reused);
    return reused;
  }

  private boolean hasScratchLeft(Scratch scratch) {
    return scratch.nextInt < SCRATCH_REGS.size();
  }

  private Register firstBorrowedScratch(Scratch scratch) {
    for (Register reg : scratch.assigned.values()) {
      if (SCRATCH_REGS.contains(reg)) return reg;
    }
    return null;
  }

  // grab the next available scratch register for this virtual in this instruction
  private Register borrowScratch(String vr, Scratch scratch) {
    if (scratch.assigned.containsKey(vr)) return scratch.assigned.get(vr);
    int idx = scratch.nextInt++;
    if (idx >= SCRATCH_REGS.size()) throw new IllegalStateException("out of scratch regs");
    Register phys = SCRATCH_REGS.get(idx);
    scratch.assigned.put(vr, phys);
    return phys;
  }

  private static MIPSInstruction substituteRegs(MIPSInstruction inst,
      Register[] vReads, Register[] pReads, Register vWrite, Register pWrite) {

    Map<String, Register> sub = new HashMap<>();
    for (int i = 0; i < vReads.length; i++) {
      if (vReads[i] != null && vReads[i].isVirtual) sub.put(vReads[i].name, pReads[i]);
    }
    if (vWrite != null && vWrite.isVirtual && pWrite != null) sub.put(vWrite.name, pWrite);

    List<MIPSOperand> newOps = new ArrayList<>();
    for (MIPSOperand op : inst.operands) newOps.add(substituteOperand(op, sub));
    return new MIPSInstruction(inst.op, null, newOps.toArray(new MIPSOperand[0]));
  }

  private static MIPSOperand substituteOperand(MIPSOperand operand, Map<String, Register> sub) {
    if (operand instanceof Register r) {
      return r.isVirtual ? sub.getOrDefault(r.name, r) : r;
    }
    if (operand instanceof Addr addr) {
      if (addr.register == null || !addr.register.isVirtual) return addr;
      Register newReg = sub.getOrDefault(addr.register.name, addr.register);
      return addr.mode == Addr.Mode.BASE_OFFSET
          ? new Addr(addr.constant, newReg) : new Addr(newReg);
    }
    return operand;
  }

  // give every virtual register a fixed home on the stack at fp-relative offsets
  // selected virtuals use this at block entry/exit; spilled virtuals use it every instruction
  private Map<String, Integer> assignStackSlots(List<MIPSInstruction> instrs) {
    Map<String, Integer> slots = new HashMap<>();
    int offset = -4;
    for (MIPSInstruction inst : instrs) {
      for (Register r : inst.getReads()) {
        if (r != null && r.isVirtual && slots.putIfAbsent(r.name, offset) == null) offset -= 4;
      }
      Register w = inst.getWrite();
      if (w != null && w.isVirtual && slots.putIfAbsent(w.name, offset) == null) offset -= 4;
    }
    return slots;
  }

  private int localArrayDataSize(IRFunction func) {
    Set<String> params = paramNames(func);
    int size = 0;
    for (IRVariableOperand v : func.variables)
      if (v.type instanceof IRArrayType && !params.contains(v.getName()))
        size += ((IRArrayType) v.type).getSize() * 4;
    return size;
  }

  private Map<String, Integer> localArrayBases(IRFunction func, int frameSize, int arrayDataSize) {
    Set<String> params = paramNames(func);
    Map<String, Integer> bases = new LinkedHashMap<>();
    int next = -(frameSize + arrayDataSize);
    for (IRVariableOperand v : func.variables) {
      if (v.type instanceof IRArrayType && !params.contains(v.getName())) {
        bases.put("$" + v.getName(), next);
        next += ((IRArrayType) v.type).getSize() * 4;
      }
    }
    return bases;
  }

  private Set<String> paramNames(IRFunction func) {
    Set<String> names = new HashSet<>();
    for (IRVariableOperand p : func.parameters) names.add(p.getName());
    return names;
  }

  // caller pushed each argument at 8+i*4($fp), so copy them into our virtual register slots
  private void copyParamsToStack(IRFunction func, Map<String, Integer> stackSlot, Register fp) {
    Register t0 = new Register("$t0", false);
    for (int i = 0; i < func.parameters.size(); i++) {
      String key = "$" + func.parameters.get(i).getName();
      if (!stackSlot.containsKey(key)) continue;
      emit(new MIPSInstruction(MIPSOp.LW, null, t0,
          new Addr(new Imm(String.valueOf(8 + i * 4), "DEC"), fp)));
      emit(new MIPSInstruction(MIPSOp.SW, null, t0,
          new Addr(new Imm(String.valueOf(stackSlot.get(key)), "DEC"), fp)));
    }
  }

  // local arrays live in the frame below the spill slots, store their base address
  // into the virtual register slot so array_load/store can find them
  private void initLocalArrays(Map<String, Integer> arrayBase,
      Map<String, Integer> stackSlot, Register fp) {
    Register t0 = new Register("$t0", false);
    for (Map.Entry<String, Integer> e : arrayBase.entrySet()) {
      if (!stackSlot.containsKey(e.getKey())) continue;
      emitAddImm(t0, fp, e.getValue());
      emit(new MIPSInstruction(MIPSOp.SW, null, t0,
          new Addr(new Imm(String.valueOf(stackSlot.get(e.getKey())), "DEC"), fp)));
    }
  }

  private void emit(MIPSInstruction instr) {
    if (instr.label != null) labels.put(instr.label, emitIdx);
    instructions.put(emitIdx++, instr);
    out.println("   " + instr);
  }

  // if there's a label waiting to be placed, attach it to the next instruction we emit
  private void emitLabeled(MIPSInstruction instr) {
    if (pendingLabel != null && instr.label == null) {
      instr.label = pendingLabel;
      pendingLabel = null;
    }
    emit(instr);
  }

  private void emitPrologue(Register sp, Register fp, Register ra) {
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.SW,   null, ra, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.SW,   null, fp, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.MOVE, null, fp, sp));
  }

  private void emitEpilogue(Register sp, Register fp, Register ra, String label) {
    emit(new MIPSInstruction(MIPSOp.MOVE, label, sp, fp));
    emit(new MIPSInstruction(MIPSOp.LW,   null,  fp, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null,  sp, sp, new Imm("4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.LW,   null,  ra, new Addr(new Imm("0", "DEC"), sp)));
    emit(new MIPSInstruction(MIPSOp.ADDI, null,  sp, sp, new Imm("4", "DEC")));
    emit(new MIPSInstruction(MIPSOp.JR,   null,  ra));
  }

  // handles the case where the immediate is too big for a 16-bit ADDI
  private void emitAddImm(Register dest, Register src, int imm) {
    if (imm >= -32768 && imm <= 32767) {
      emit(new MIPSInstruction(MIPSOp.ADDI, null, dest, src,
          new Imm(String.valueOf(imm), "DEC")));
    } else {
      Register tmp = new Register("$t9", false);
      emit(new MIPSInstruction(MIPSOp.LI, null, tmp,
          new Imm(String.valueOf(Math.abs(imm)), "DEC")));
      emit(new MIPSInstruction(imm < 0 ? MIPSOp.SUB : MIPSOp.ADD, null, dest, src, tmp));
    }
  }

  private Addr slot(int offset, Register fp) {
    return new Addr(new Imm(String.valueOf(offset), "DEC"), fp);
  }

  private List<MIPSInstruction> sortedInstructions(Map<Integer, MIPSInstruction> input) {
    List<Map.Entry<Integer, MIPSInstruction>> entries = new ArrayList<>(input.entrySet());
    entries.sort(Map.Entry.comparingByKey());
    List<MIPSInstruction> out = new ArrayList<>(entries.size());
    for (var e : entries) out.add(e.getValue());
    return out;
  }
}
