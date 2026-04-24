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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IntrablockAllocator {
  // need some way to differentiate regs
  private enum RegisterClass {
    INT,
    FLOAT
  }

  // pool of integer regs
  private static final List<Register> INT_REGS = Arrays.asList(
      new Register("$t0", false),
      new Register("$t1", false),
      new Register("$t2", false),
      new Register("$t3", false),
      new Register("$t4", false),
      new Register("$t5", false),
      new Register("$t6", false),
      new Register("$t7", false));
  
  // pool of float regs
  private static final List<Register> FLOAT_REGS = Arrays.asList(
      // return uses $f0, count from f2 inclusive
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
      new Register("$f24", false),
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

  // split selected mips into blocks so allocation stays local
  private List<List<MIPSInstruction>> buildBlocks(List<MIPSInstruction> orderedInstructions) {
    List<List<MIPSInstruction>> blocks = new ArrayList<>();
    List<MIPSInstruction> current = new ArrayList<>();

    for (MIPSInstruction inst : orderedInstructions) {
      // labels can be branch targets, so start a new block there
      if (inst.label != null && !current.isEmpty()) {
        blocks.add(current);
        current = new ArrayList<>();
      }

      current.add(inst);

      // branches/jumps end the current block
      if (isBlockTerminator(inst)) {
        blocks.add(current);
        current = new ArrayList<>();
      }
    }

    if (!current.isEmpty()) {
      blocks.add(current);
    }

    return blocks;
  }

  // instructions where control flow leaves the current straight-line block
  private boolean isBlockTerminator(MIPSInstruction inst) {
    return inst.isBranch()
        || inst.op == MIPSOp.J
        || inst.op == MIPSOp.JR
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

  // main register allocation loop for one basic block
  private void emitBlock(List<MIPSInstruction> block, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, String epilogueLabel) {
    // virtual register name -> physical register currently holding it
    Map<String, Register> vrToPr = new HashMap<>();

    // physical register name -> virtual register currently inside it
    Map<String, String> prToVr = new HashMap<>();

    // values that changed in a physical reg but are not saved to memory yet
    Set<String> dirty = new HashSet<>();

    // for each instruction, what virtual regs get used later in this block
    List<Map<String, Integer>> nextUseAfter = computeNextUseAfter(block);

    for (int i = 0; i < block.size(); i++) {
      MIPSInstruction inst = block.get(i);
      Map<String, Integer> futureUses = nextUseAfter.get(i);
      pendingLabel = inst.label;

      // all returns go through one shared epilogue
      if (inst.op == MIPSOp.JR) {
        flushDirty(vrToPr, dirty, vrMap, vrClasses, fp);
        emitWithPendingLabel(new MIPSInstruction(MIPSOp.J, null, new Addr(epilogueLabel)));
        clearState(vrToPr, prToVr, dirty);
        continue;
      }

      // calls and control-flow exits cannot trust caller-saved temps afterward
      boolean barrier = inst.op == MIPSOp.JAL || isBlockTerminator(inst);
      if (barrier) {
        flushDirty(vrToPr, dirty, vrMap, vrClasses, fp);
      }

      emitAllocatedInstruction(inst, futureUses, vrMap, vrClasses, fp, vrToPr, prToVr,
          dirty);

      if (barrier) {
        // after a barrier, memory is the source of truth again
        clearState(vrToPr, prToVr, dirty);
      } else {
        // free regs whose values are not needed later in this block
        freeDeadMappings(futureUses, vrMap, vrClasses, fp, vrToPr, prToVr, dirty);
      }
    }

    // clean up any values still live at the end of the block
    pendingLabel = null;
    flushDirty(vrToPr, dirty, vrMap, vrClasses, fp);
    clearState(vrToPr, prToVr, dirty);
  }

  // scan backward so we can tell when each virtual register is next used
  private List<Map<String, Integer>> computeNextUseAfter(List<MIPSInstruction> block) {
    List<Map<String, Integer>> nextUseAfter = new ArrayList<>(
        Collections.nCopies(block.size(), null));
    Map<String, Integer> nextUse = new HashMap<>();

    for (int i = block.size() - 1; i >= 0; i--) {
      MIPSInstruction inst = block.get(i);
      nextUseAfter.set(i, new HashMap<>(nextUse));

      // a write kills the old value
      Register write = inst.getWrite();
      if (write != null && write.isVirtual) {
        nextUse.remove(write.name);
      }

      // reads make this virtual live at this instruction
      for (Register read : inst.getReads()) {
        if (read != null && read.isVirtual) {
          nextUse.put(read.name, i);
        }
      }
    }

    return nextUseAfter;
  }

  // load/read regs, pick a write reg, then emit the rewritten instruction
  private void emitAllocatedInstruction(MIPSInstruction inst, Map<String, Integer> futureUses,
      Map<String, Integer> vrMap, Map<String, RegisterClass> vrClasses, Register fp,
      Map<String, Register> vrToPr, Map<String, String> prToVr, Set<String> dirty) {
    Register[] reads = inst.getReads();
    Register[] physReads = new Register[reads.length];
    Set<String> lockedRegs = lockedReadRegisters(reads, vrToPr);

    for (int i = 0; i < reads.length; i++) {
      Register read = reads[i];
      if (read != null && read.isVirtual) {
        // read operands must already be in real registers
        RegisterClass registerClass = registerClassOf(read.name, vrClasses);
        physReads[i] = ensureLoaded(read.name, registerClass, futureUses, lockedRegs,
            vrMap, vrClasses, fp, vrToPr, prToVr, dirty);
        lockedRegs.add(physReads[i].name);
      } else {
        physReads[i] = read;
      }
    }

    Register write = inst.getWrite();
    Register physWrite = write;
    if (write != null && write.isVirtual) {
      if (vrToPr.containsKey(write.name)) {
        // if the destination is already loaded, just overwrite that reg
        physWrite = vrToPr.get(write.name);
      } else {
        // otherwise get a free register or evict something
        RegisterClass registerClass = registerClassOf(write.name, vrClasses);
        physWrite = acquireRegister(registerClass, futureUses, lockedRegs, vrMap,
            vrClasses, fp, vrToPr, prToVr, dirty);
        bind(write.name, physWrite, vrToPr, prToVr);
      }
    }

    emitWithPendingLabel(substituteRegisters(inst, physReads, physWrite));

    if (write != null && write.isVirtual) {
      // memory is stale now until this gets spilled
      dirty.add(write.name);
    }
  }

  private Set<String> lockedReadRegisters(Register[] reads, Map<String, Register> vrToPr) {
    Set<String> lockedRegs = new HashSet<>();
    for (Register read : reads) {
      if (read != null && read.isVirtual && vrToPr.containsKey(read.name)) {
        // don't evict a register we still need for this instruction
        lockedRegs.add(vrToPr.get(read.name).name);
      }
    }
    return lockedRegs;
  }

  // make sure a virtual register has a physical register holding its value
  private Register ensureLoaded(String vrName, RegisterClass registerClass,
      Map<String, Integer> futureUses, Set<String> lockedRegs, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, Map<String, Register> vrToPr,
      Map<String, String> prToVr, Set<String> dirty) {
    if (vrToPr.containsKey(vrName)) {
      return vrToPr.get(vrName);
    }

    // not loaded yet, so grab a register and load from its spill slot
    Register phys = acquireRegister(registerClass, futureUses, lockedRegs, vrMap,
        vrClasses, fp, vrToPr, prToVr, dirty);
    bind(vrName, phys, vrToPr, prToVr);
    emitWithPendingLabel(new MIPSInstruction(loadOpFor(registerClass), null,
        phys, frameAddress(vrMap.get(vrName), fp)));
    return phys;
  }

  // get a physical register, spilling another value if needed
  private Register acquireRegister(RegisterClass registerClass, Map<String, Integer> futureUses,
      Set<String> lockedRegs, Map<String, Integer> vrMap, Map<String, RegisterClass> vrClasses,
      Register fp, Map<String, Register> vrToPr, Map<String, String> prToVr, Set<String> dirty) {
    List<Register> pool = poolFor(registerClass);
    for (Register candidate : pool) {
      // easiest case: unused physical register
      if (!prToVr.containsKey(candidate.name)) {
        return candidate;
      }
    }

    // otherwise choose one to spill
    Register victim = selectVictim(pool, futureUses, lockedRegs, prToVr);
    spillAndUnbind(victim.name, vrMap, vrClasses, fp, vrToPr, prToVr, dirty);
    return victim;
  }

  // pick the register whose value is needed farthest in the future
  private Register selectVictim(List<Register> pool, Map<String, Integer> futureUses,
      Set<String> lockedRegs, Map<String, String> prToVr) {
    Register fallback = null;
    Register best = null;
    int farthestUse = Integer.MIN_VALUE;

    for (Register candidate : pool) {
      if (fallback == null) {
        fallback = candidate;
      }
      if (lockedRegs.contains(candidate.name)) {
        continue;
      }

      String vrName = prToVr.get(candidate.name);
      Integer nextUse = futureUses.get(vrName);
      // if it is never used again in this block, it is the best victim
      if (nextUse == null) {
        return candidate;
      }
      if (best == null || nextUse > farthestUse) {
        best = candidate;
        farthestUse = nextUse;
      }
    }

    return best != null ? best : fallback;
  }

  // keep both direction maps in sync
  private void bind(String vrName, Register phys, Map<String, Register> vrToPr,
      Map<String, String> prToVr) {
    Register oldPhys = vrToPr.put(vrName, phys);
    if (oldPhys != null && !oldPhys.name.equals(phys.name)) {
      prToVr.remove(oldPhys.name);
    }

    String oldVr = prToVr.put(phys.name, vrName);
    if (oldVr != null && !oldVr.equals(vrName)) {
      vrToPr.remove(oldVr);
    }
  }

  // write all dirty values back to their stack slots
  private void flushDirty(Map<String, Register> vrToPr, Set<String> dirty,
      Map<String, Integer> vrMap, Map<String, RegisterClass> vrClasses,
      Register fp) {
    List<String> dirtyVrs = new ArrayList<>(dirty);
    dirtyVrs.sort(Comparator.comparingInt(vrMap::get));

    for (String vrName : dirtyVrs) {
      Register phys = vrToPr.get(vrName);
      if (phys != null) {
        spillVirtual(vrName, phys, vrMap, vrClasses, fp);
      }
      dirty.remove(vrName);
    }
  }

  // after each instruction, drop values with no more uses in this block
  private void freeDeadMappings(Map<String, Integer> futureUses, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, Map<String, Register> vrToPr,
      Map<String, String> prToVr, Set<String> dirty) {
    List<String> vrNames = new ArrayList<>(vrToPr.keySet());
    vrNames.sort(String::compareTo);

    for (String vrName : vrNames) {
      if (futureUses.containsKey(vrName)) {
        continue;
      }

      Register phys = vrToPr.get(vrName);
      if (phys == null) {
        continue;
      }

      // still need to save it if this instruction wrote a new value
      if (dirty.contains(vrName)) {
        spillVirtual(vrName, phys, vrMap, vrClasses, fp);
        dirty.remove(vrName);
      }

      vrToPr.remove(vrName);
      prToVr.remove(phys.name);
    }
  }

  // spill whatever lives in this physical register, then free it
  private void spillAndUnbind(String physName, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, Map<String, Register> vrToPr,
      Map<String, String> prToVr, Set<String> dirty) {
    String vrName = prToVr.remove(physName);
    if (vrName == null) {
      return;
    }

    Register phys = vrToPr.remove(vrName);
    if (phys != null && dirty.contains(vrName)) {
      spillVirtual(vrName, phys, vrMap, vrClasses, fp);
      dirty.remove(vrName);
    }
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

  // choose the physical register pool
  private List<Register> poolFor(RegisterClass registerClass) {
    return registerClass == RegisterClass.FLOAT ? FLOAT_REGS : INT_REGS;
  }

  // forget all current register mappings
  private void clearState(Map<String, Register> vrToPr, Map<String, String> prToVr,
      Set<String> dirty) {
    vrToPr.clear();
    prToVr.clear();
    dirty.clear();
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
