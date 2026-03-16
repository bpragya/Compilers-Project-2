import ir.IRInstruction;
import ir.IRInstruction.OpCode;
import ir.operand.IRLabelOperand;
import ir.operand.IRVariableOperand;

import java.util.Set;

public class Helpers {
    public static final Set<OpCode> BRANCH_OPCODES = Set.of(
            OpCode.BREQ, OpCode.BRNEQ, OpCode.BRLT, OpCode.BRGT, OpCode.BRGEQ);

    public static final Set<OpCode> TERMINATOR_OPCODES = Set.of(
            OpCode.BREQ, OpCode.BRNEQ, OpCode.BRLT, OpCode.BRGT, OpCode.BRGEQ,
            OpCode.GOTO, OpCode.RETURN);

    /**
     * Returns {@code true} if {@code inst} writes a result into {@code operands[0]}
     * as an {@link IRVariableOperand}.
     */
    public static boolean defines(IRInstruction inst) {
        return (inst.opCode == OpCode.ASSIGN
                || inst.opCode == OpCode.ADD
                || inst.opCode == OpCode.SUB
                || inst.opCode == OpCode.MULT
                || inst.opCode == OpCode.DIV
                || inst.opCode == OpCode.AND
                || inst.opCode == OpCode.OR
                || inst.opCode == OpCode.CALLR
                || inst.opCode == OpCode.ARRAY_LOAD)
                && inst.operands.length > 0
                && inst.operands[0] instanceof IRVariableOperand;
    }

    /**
     * Returns the variable name written by {@code inst}.
     * Only called when {@link #defines(IRInstruction)} is {@code true}.
     */
    public static String defVar(IRInstruction inst) {
        return ((IRVariableOperand) inst.operands[0]).getName();
    }

    /**
     * Returns the branch or goto target label name of {@code inst}.
     * Only called when {@code inst.opCode} is in {@link #TERMINATOR_OPCODES} and
     * not {@code RETURN}.
     */
    public static String targetLabel(IRInstruction inst) {
        return ((IRLabelOperand) inst.operands[0]).getName();
    }
}
