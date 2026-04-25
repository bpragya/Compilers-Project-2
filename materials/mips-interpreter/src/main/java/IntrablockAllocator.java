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

public class IntrablockAllocator {
  // need some way to differentiate regs
  private enum RegisterClass {
    INT,
    FLOAT
  }

  // these are the integer regs we keep assigned for a whole block
  private static final List<Register> INT_REGS = Arrays.asList(
      new Register("$t0", false),
      new Register("$t1", false),
      new Register("$t2", false),
      new Register("$t3", false),
      new Register("$t4", false));
  
  // keep a few integer temps around for spilled values inside one instruction
  private static final List<Register> INT_SPILL_REGS = Arrays.asList(
      new Register("$t5", false),
      new Register("$t6", false),
      new Register("$t7", false));

  // same idea for float values
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
      new Register("$f24", false));

  // float scratch regs for spilled reads/writes
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

  // for one block: who stays in a real reg, and who needs a load at block entry
  private static final class BlockPlan {
    private final Map<String, Register> assignedRegs;
    private final Set<String> entryLoads;

    private BlockPlan(Map<String, Register> assignedRegs, Set<String> entryLoads) {
      this.assignedRegs = assignedRegs;
      this.entryLoads = entryLoads;
    }
  }

  // scratch regs only live for one instruction while we deal with spills
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

      // branches/jumps end the current block, and calls do too since $t regs are caller-saved
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

  // main block allocator
  private void emitBlock(List<MIPSInstruction> block, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp, String epilogueLabel) {
    BlockPlan plan = planBlock(block, vrClasses);
    Set<String> activeAssigned = new HashSet<>();
    boolean storedAtExit = false;

    // if the block starts with a label, any preload has to inherit it
    pendingLabel = block.get(0).label;
    preloadAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);

    for (int i = 0; i < block.size(); i++) {
      MIPSInstruction inst = block.get(i);
      if (i > 0) {
        pendingLabel = inst.label;
      }

      // all returns still go through one shared epilogue
      if (inst.op == MIPSOp.JR) {
        storeAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);
        emitWithPendingLabel(new MIPSInstruction(MIPSOp.J, null, new Addr(epilogueLabel)));
        storedAtExit = true;
        break;
      }

      // if control leaves here, memory needs to be up to date before the jump/call happens
      if (endsAllocationBlock(inst)) {
        storeAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);
        emitAllocatedInstruction(inst, plan, activeAssigned, vrMap, vrClasses, fp);
        storedAtExit = true;
        break;
      }

      emitAllocatedInstruction(inst, plan, activeAssigned, vrMap, vrClasses, fp);
    }

    // fallthrough blocks still need to spill their assigned regs at the end
    pendingLabel = null;
    if (!storedAtExit) {
      storeAssignedRegisters(plan, activeAssigned, vrMap, vrClasses, fp);
    }
  }

  // choose which virtual regs win real regs for this block
  private BlockPlan planBlock(List<MIPSInstruction> block, Map<String, RegisterClass> vrClasses) {
    Map<String, Register> assignedRegs = new LinkedHashMap<>();
    assignBlockRegisters(block, vrClasses, RegisterClass.INT, INT_REGS, assignedRegs);
    assignBlockRegisters(block, vrClasses, RegisterClass.FLOAT, FLOAT_REGS, assignedRegs);
    return new BlockPlan(assignedRegs, findEntryLoads(block, assignedRegs));
  }

  // greedy part from the pdf: most-used live ranges get the fixed regs first
  private void assignBlockRegisters(List<MIPSInstruction> block,
      Map<String, RegisterClass> vrClasses, RegisterClass registerClass,
      List<Register> pool, Map<String, Register> assignedRegs) {
    List<String> ranked = rankBlockVirtuals(block, vrClasses, registerClass);
    int limit = Math.min(ranked.size(), pool.size());
    for (int i = 0; i < limit; i++) {
      assignedRegs.put(ranked.get(i), pool.get(i));
    }
  }

  // count each virtual register access in the block, then sort by most uses first
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

      Register write = inst.getWrite();
      if (write != null && write.isVirtual
          && registerClassOf(write.name, vrClasses) == registerClass) {
        useCounts.put(write.name, useCounts.getOrDefault(write.name, 0) + 1);
        firstSeen.putIfAbsent(write.name, seenIndex++);
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

  // only load the assigned regs whose first block access is a read
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

  // load the fixed block regs we know are live on entry
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

  // at block boundaries we make memory the source of truth again
  private void storeAssignedRegisters(BlockPlan plan, Set<String> activeAssigned,
      Map<String, Integer> vrMap, Map<String, RegisterClass> vrClasses, Register fp) {
    List<String> vrNames = new ArrayList<>(activeAssigned);
    vrNames.sort(Comparator.comparingInt(vrMap::get));

    for (String vrName : vrNames) {
      spillVirtual(vrName, plan.assignedRegs.get(vrName), vrMap, vrClasses, fp);
    }
  }

  // rewrite one instruction using the block assignments plus scratch regs for spills
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
        physWrite = plan.assignedRegs.get(write.name);
        activeAssigned.add(write.name);
      } else {
        physWrite = registerForWrite(write.name, scratch, vrClasses);
        spilledWrites.add(write.name);
      }
    }

    emitWithPendingLabel(substituteRegisters(inst, physReads, physWrite));

    List<String> writesToStore = new ArrayList<>(spilledWrites);
    writesToStore.sort(Comparator.comparingInt(vrMap::get));
    for (String vrName : writesToStore) {
      spillVirtual(vrName, scratch.regs.get(vrName), vrMap, vrClasses, fp);
    }
  }

  // fixed block reg if it has one, otherwise scratch reg plus a load
  private Register registerForRead(String vrName, BlockPlan plan, Set<String> activeAssigned,
      ScratchPool scratch, Map<String, Integer> vrMap,
      Map<String, RegisterClass> vrClasses, Register fp) {
    if (plan.assignedRegs.containsKey(vrName)) {
      Register phys = plan.assignedRegs.get(vrName);

      // block entry loading should usually cover this, but this keeps reads safe
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
