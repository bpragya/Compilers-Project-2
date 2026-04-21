import ir.IRInstruction;
import ir.datatype.IRFloatType;
import ir.operand.IRConstantOperand;
import ir.operand.IROperand;
import ir.operand.IRVariableOperand;
import mips.MIPSInstruction;
import mips.MIPSOp;
import mips.operand.Addr;
import mips.operand.Imm;
import mips.operand.Register;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a single IR instruction into one or more MIPS instructions.
 * Constants are materialized into fresh virtual temporaries as needed.
 * One instance should be created per IR function so that the temporary
 * counter resets between functions.
 */
public class InstructionSelection {

    private final PrintStream out;
    private final Map<Integer, MIPSInstruction> instructions;
    private final Map<String, Integer> labels;
    private int mipsIdx = 0;
    private int tmpCounter = 0;
    private int addrTmpCounter = 0;

    public InstructionSelection(PrintStream out) {
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
     * Translates {@code instr} and emits the resulting MIPS instruction(s).
     *
     * @param instr the IR instruction to lower
     */
    public void generateInstructions(IRInstruction instr) {
        switch (instr.opCode) {
            case ASSIGN -> emitAssign(instr);
            case ADD    -> emitAdd(instr);
            case SUB    -> emitSub(instr);
            case MULT   -> emitBinaryRR(instr, MIPSOp.MUL);
            case DIV    -> emitBinaryRR(instr, MIPSOp.DIV);
            case AND    -> emitBinaryRR(instr, MIPSOp.AND);
            case OR     -> emitBinaryRR(instr, MIPSOp.OR);
            case GOTO   -> emit(new MIPSInstruction(MIPSOp.J, null,
                                    new Addr(instr.operands[0].toString())));
            case BREQ   -> emitBranch(instr, MIPSOp.BEQ);
            case BRNEQ  -> emitBranch(instr, MIPSOp.BNE);
            case BRLT   -> emitBranch(instr, MIPSOp.BLT);
            case BRGT   -> emitBranch(instr, MIPSOp.BGT);
            case BRGEQ  -> emitBranch(instr, MIPSOp.BGE);
            case RETURN -> emitReturn(instr);
            case CALL   -> emitCall(instr);
            case CALLR  -> emitCallR(instr);
            case ARRAY_STORE -> emitArrayStore(instr);
            case ARRAY_LOAD  -> emitArrayLoad(instr);
            case LABEL  -> {
                labels.put(instr.operands[0].toString(), mipsIdx);
                out.println("   " + instr.operands[0] + ":");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void emit(MIPSInstruction instr) {
        instructions.put(mipsIdx++, instr);
        out.println("   " + instr);
    }

    /**
     * Returns a virtual register holding {@code op}'s value. If {@code op} is a
     * constant, a fresh temporary is materialized with {@code li}; otherwise the
     * operand's existing virtual register is returned directly.
     */
    private Register resolveToRegister(IROperand op) {
        if (op instanceof IRConstantOperand) {
            String tmp = "$tmp" + tmpCounter++;
            emit(new MIPSInstruction(MIPSOp.LI, null,
                    new Register(tmp, true),
                    new Imm(op.toString(), "DEC")));
            return new Register(tmp, true);
        }
        return new Register("$" + op.toString(), true);
    }

    private Register vr(IROperand op) {
        return new Register("$" + op.toString(), true);
    }

    private void emitAssign(IRInstruction instr) {
        IROperand dest = instr.operands[0];
        IROperand src  = instr.operands[1];
        if (src instanceof IRVariableOperand) {
            emit(new MIPSInstruction(MIPSOp.MOVE, null, vr(dest), vr(src)));
        } else {
            emit(new MIPSInstruction(MIPSOp.LI, null, vr(dest),
                    new Imm(src.toString(), "DEC")));
        }
    }

    private void emitAdd(IRInstruction instr) {
        IROperand dest = instr.operands[0];
        IROperand src1 = instr.operands[1];
        IROperand src2 = instr.operands[2];
        boolean c1 = src1 instanceof IRConstantOperand;
        boolean c2 = src2 instanceof IRConstantOperand;

        if (!c1 && !c2) {
            emit(new MIPSInstruction(MIPSOp.ADD, null, vr(dest), vr(src1), vr(src2)));
        } else if (!c1) {
            emit(new MIPSInstruction(MIPSOp.ADDI, null, vr(dest), vr(src1),
                    new Imm(src2.toString(), "DEC")));
        } else if (!c2) {
            emit(new MIPSInstruction(MIPSOp.ADDI, null, vr(dest), vr(src2),
                    new Imm(src1.toString(), "DEC")));
        } else {
            String tmp = "$tmp" + tmpCounter++;
            emit(new MIPSInstruction(MIPSOp.LI,   null, new Register(tmp, true),
                    new Imm(src1.toString(), "DEC")));
            emit(new MIPSInstruction(MIPSOp.ADDI, null, vr(dest),
                    new Register(tmp, true), new Imm(src2.toString(), "DEC")));
        }
    }

    private void emitSub(IRInstruction instr) {
        IROperand dest = instr.operands[0];
        IROperand src1 = instr.operands[1];
        IROperand src2 = instr.operands[2];
        boolean c1 = src1 instanceof IRConstantOperand;
        boolean c2 = src2 instanceof IRConstantOperand;

        if (!c1 && !c2) {
            emit(new MIPSInstruction(MIPSOp.SUB, null, vr(dest), vr(src1), vr(src2)));
        } else if (!c1) {
            // src1 is a variable, src2 is a constant
            IRConstantOperand src2c = (IRConstantOperand) src2;
            if (src2c.type instanceof IRFloatType) {
                Register tmp = resolveToRegister(src2);
                emit(new MIPSInstruction(MIPSOp.SUB_S, null, vr(dest), vr(src1), tmp));
            } else {
                int negated = -Integer.parseInt(src2.toString());
                emit(new MIPSInstruction(MIPSOp.ADDI, null, vr(dest), vr(src1),
                        new Imm(String.valueOf(negated), "DEC")));
            }
        } else if (!c2) {
            String tmp = "$tmp" + tmpCounter++;
            emit(new MIPSInstruction(MIPSOp.LI,  null, new Register(tmp, true),
                    new Imm(src1.toString(), "DEC")));
            emit(new MIPSInstruction(MIPSOp.SUB, null, vr(dest),
                    new Register(tmp, true), vr(src2)));
        } else {
            // both are constants
            IRConstantOperand src2c = (IRConstantOperand) src2;
            if (src2c.type instanceof IRFloatType) {
                String tmp1 = "$tmp" + tmpCounter++;
                String tmp2 = "$tmp" + tmpCounter++;
                emit(new MIPSInstruction(MIPSOp.LI_S, null, new Register(tmp1, true),
                        new Imm(src1.toString(), "DEC")));
                emit(new MIPSInstruction(MIPSOp.LI_S, null, new Register(tmp2, true),
                        new Imm(src2.toString(), "DEC")));
                emit(new MIPSInstruction(MIPSOp.SUB_S, null, vr(dest),
                        new Register(tmp1, true), new Register(tmp2, true)));
            } else {
                String tmp = "$tmp" + tmpCounter++;
                emit(new MIPSInstruction(MIPSOp.LI, null, new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                int negated = -Integer.parseInt(src2.toString());
                emit(new MIPSInstruction(MIPSOp.ADDI, null, vr(dest),
                        new Register(tmp, true),
                        new Imm(String.valueOf(negated), "DEC")));
            }
        }
    }

    private void emitBinaryRR(IRInstruction instr, MIPSOp op) {
        IROperand dest = instr.operands[0];
        Register r1 = resolveToRegister(instr.operands[1]);
        Register r2 = resolveToRegister(instr.operands[2]);
        emit(new MIPSInstruction(op, null, vr(dest), r1, r2));
    }

    private void emitBranch(IRInstruction instr, MIPSOp op) {
        String label = instr.operands[0].toString();
        Register r1 = resolveToRegister(instr.operands[1]);
        Register r2 = resolveToRegister(instr.operands[2]);
        emit(new MIPSInstruction(op, null, r1, r2, new Addr(label)));
    }

    private void emitReturn(IRInstruction instr) {
        if (instr.operands != null && instr.operands.length > 0) {
            Register r = resolveToRegister(instr.operands[0]);
            emit(new MIPSInstruction(MIPSOp.MOVE, null,
                    new Register("$v0", false), r));
        }
        emit(new MIPSInstruction(MIPSOp.JR, null, new Register("$ra", false)));
    }

    private void emitCall(IRInstruction instr) {
        Register sp = new Register("$sp", false);
        pushArgsToStack(instr, 1, sp);
        emit(new MIPSInstruction(MIPSOp.JAL, null, new Addr(instr.operands[0].toString())));
        int argCount = instr.operands.length - 1;
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp,
                new Imm(String.valueOf(4 * argCount), "DEC")));
    }

    private void emitCallR(IRInstruction instr) {
        IROperand dest  = instr.operands[0];
        Register sp = new Register("$sp", false);
        pushArgsToStack(instr, 2, sp);
        emit(new MIPSInstruction(MIPSOp.JAL, null, new Addr(instr.operands[1].toString())));
        emit(new MIPSInstruction(MIPSOp.MOVE, null, vr(dest), new Register("$v0", false)));
        int argCount = instr.operands.length - 2;
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp,
                new Imm(String.valueOf(4 * argCount), "DEC")));
    }

    private void pushArgsToStack(IRInstruction instr, int firstArgIdx, Register sp) {
        for (int i = instr.operands.length - 1; i >= firstArgIdx; i--) {
            Register argReg = resolveToRegister(instr.operands[i]);
            emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
            emit(new MIPSInstruction(MIPSOp.SW,   null, argReg,
                    new Addr(new Imm("0", "DEC"), sp)));
        }
    }

    private void emitArrayStore(IRInstruction instr) {
        IROperand src = instr.operands[0];
        IROperand arr = instr.operands[1];
        IROperand idx = instr.operands[2];

        if (idx instanceof IRConstantOperand) {
            int offset = 4 * Integer.parseInt(((IRConstantOperand) idx).getValueString());
            emit(new MIPSInstruction(MIPSOp.SW, null, vr(src),
                    new Addr(new Imm(String.valueOf(offset), "DEC"), vr(arr))));
        } else {
            String addrTmp = "$__addr" + addrTmpCounter++;
            emit(new MIPSInstruction(MIPSOp.SLL, null,
                    new Register(addrTmp, true), vr(idx), new Imm("2", "DEC")));
            emit(new MIPSInstruction(MIPSOp.ADD, null,
                    new Register(addrTmp, true), vr(arr), new Register(addrTmp, true)));
            emit(new MIPSInstruction(MIPSOp.SW, null, vr(src),
                    new Addr(new Imm("0", "DEC"), new Register(addrTmp, true))));
        }
    }

    private void emitArrayLoad(IRInstruction instr) {
        IROperand dest = instr.operands[0];
        IROperand arr  = instr.operands[1];
        IROperand idx  = instr.operands[2];

        if (idx instanceof IRConstantOperand) {
            int offset = 4 * Integer.parseInt(((IRConstantOperand) idx).getValueString());
            emit(new MIPSInstruction(MIPSOp.LW, null, vr(dest),
                    new Addr(new Imm(String.valueOf(offset), "DEC"), vr(arr))));
        } else {
            String addrTmp = "$__addr" + addrTmpCounter++;
            emit(new MIPSInstruction(MIPSOp.SLL, null,
                    new Register(addrTmp, true), vr(idx), new Imm("2", "DEC")));
            emit(new MIPSInstruction(MIPSOp.ADD, null,
                    new Register(addrTmp, true), vr(arr), new Register(addrTmp, true)));
            emit(new MIPSInstruction(MIPSOp.LW, null, vr(dest),
                    new Addr(new Imm("0", "DEC"), new Register(addrTmp, true))));
        }
    }
}
