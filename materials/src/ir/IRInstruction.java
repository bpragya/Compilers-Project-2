package ir;

import ir.operand.IROperand;

public class IRInstruction {

    public enum OpCode {
        ASSIGN,
        ADD, SUB, MULT, DIV, AND, OR,
        GOTO,
        BREQ, BRNEQ, BRLT, BRGT, BRGEQ,
        RETURN,
        CALL, CALLR,
        ARRAY_STORE, ARRAY_LOAD,
        LABEL;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    public OpCode opCode;

    public IROperand[] operands;

    public int irLineNumber;

    public boolean marked; //pragya
    public boolean critical; //pragya

    public IRInstruction() {}


    //PRAGYA: added a mark boolean and a critical boolean for mark and sweep algorithm
    public IRInstruction(OpCode opCode, IROperand[] operands, int irLineNumber, boolean marked, boolean critical) {
        this.opCode = opCode;
        this.operands = operands;
        this.irLineNumber = irLineNumber;
        this.marked = false;
        this.critical = false;
    }

}
