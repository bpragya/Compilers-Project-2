import ir.IRFunction;
import mips.MIPSInstruction;
import mips.MIPSOp;
import mips.MIPSProgram;
import mips.operand.Addr;
import mips.operand.Imm;
import mips.operand.Register;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

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
     * @param func     the IR function being compiled
     * @param mipsProg the unallocated MIPS program produced by instruction selection
     */
    public void allocate(IRFunction func, MIPSProgram mipsProg) {
        Register sp = new Register("$sp", false);
        Register fp = new Register("$fp", false);
        Register ra = new Register("$ra", false);

        emitPrologue(sp, fp, ra);

        Map<String, Integer> vrMap = buildVrMap(mipsProg);
        int frameSize = 4 * vrMap.size();
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp,
                new Imm(String.valueOf(-frameSize), "DEC")));

        loadParameters(func, vrMap, fp);
        emitBody(mipsProg, vrMap, sp, fp);
        emitEpilogue(sp, fp, ra);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void emit(MIPSInstruction instr) {
        instructions.put(mipsIdx++, instr);
        out.println("   " + instr);
    }

    private void emitPrologue(Register sp, Register fp, Register ra) {
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.SW,   null, ra, new Addr(new Imm("0", "DEC"), sp)));
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.SW,   null, fp, new Addr(new Imm("0", "DEC"), sp)));
        emit(new MIPSInstruction(MIPSOp.MOVE, null, fp, sp));
    }

    private Map<String, Integer> buildVrMap(MIPSProgram mipsProg) {
        Map<String, Integer> vrMap = new HashMap<>();
        int offset = -4;

        for (MIPSInstruction inst : mipsProg.instructions.values()) {
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

    private void emitBody(MIPSProgram mipsProg, Map<String, Integer> vrMap,
                          Register sp, Register fp) {
        for (MIPSInstruction inst : mipsProg.instructions.values()) {
            if (inst.op == MIPSOp.JAL) {
                emitCallWithSave(inst, sp);
            } else {
                emitWithSpill(inst, vrMap, fp);
            }
        }
    }

    private void emitCallWithSave(MIPSInstruction callInst, Register sp) {
        for (int i = 0; i < 10; i++) {
            emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
            emit(new MIPSInstruction(MIPSOp.SW, null,
                    new Register("$t" + i, false),
                    new Addr(new Imm("0", "DEC"), sp)));
        }

        emit(callInst);

        for (int i = 9; i >= 0; i--) {
            emit(new MIPSInstruction(MIPSOp.LW, null,
                    new Register("$t" + i, false),
                    new Addr(new Imm("0", "DEC"), sp)));
            emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
        }
    }

    private void emitWithSpill(MIPSInstruction inst, Map<String, Integer> vrMap,
                               Register fp) {
        int count = 0;
        for (Register r : inst.getReads()) {
            emit(new MIPSInstruction(MIPSOp.LW, null,
                    new Register("$t" + count, false),
                    new Addr(new Imm(String.valueOf(vrMap.get(r.name)), "DEC"), fp)));
            count++;
        }

        Register w = inst.getWrite();
        if (w != null && w.isVirtual) {
            emit(new MIPSInstruction(MIPSOp.SW, null,
                    new Register("$t" + count, false),
                    new Addr(new Imm(String.valueOf(vrMap.get(w.name)), "DEC"), fp)));
        }
    }

    private void emitEpilogue(Register sp, Register fp, Register ra) {
        emit(new MIPSInstruction(MIPSOp.MOVE, null, sp, fp));
        emit(new MIPSInstruction(MIPSOp.LW,   null, fp, new Addr(new Imm("0", "DEC"), sp)));
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.LW,   null, ra, new Addr(new Imm("0", "DEC"), sp)));
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.JR,   null, ra));
    }
}
