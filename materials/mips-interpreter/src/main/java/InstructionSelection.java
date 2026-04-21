import ir.IRFunction;
import ir.IRInstruction;
import ir.operand.IRConstantOperand;
import ir.operand.IROperand;
import ir.operand.IRVariableOperand;
import main.java.mips.MIPSInstruction;
import main.java.mips.MIPSOp;
import main.java.mips.operand.Addr;
import main.java.mips.operand.Imm;
import main.java.mips.operand.Register;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;


public class InstructionSelection {

    private PrintStream out;
    private Map<Integer, MIPSInstruction> instructions;
    private Map<String, Integer> labels;
    private int mipsIdx = 0;

    public InstructionSelection(PrintStream out) {
        this.out = out;
        this.instructions = new HashMap<>();
        this.labels = new HashMap<>();
    }

    public Map<Integer, MIPSInstruction> getInstructions() {
        return instructions;
    }

    public Map<String, Integer> getLabels() {
        return labels;
    }

    public void emit(MIPSInstruction instr) {
        instructions.put(mipsIdx, instr);
        mipsIdx+=1;
        out.println("   " + instr);
    }

    //not sure if this structure is correct
    int count = 0;

    public void generateInstructions(IRInstruction instr) {

        switch (instr.opCode) {
            case ASSIGN -> {
                IROperand dest = instr.operands[0];
                IROperand src = instr.operands[1];

                if (src instanceof IRVariableOperand) {
                    //move from one register to another
                    //emit("move $" + dest + ", " + "$" + src); 
                    emit(new MIPSInstruction(MIPSOp.MOVE, null, 
                    new Register("$" + dest.toString(), true), new Register("$" + src.toString(), true)));

                } else /*if (src instanceof IRConstantOperand)*/ {
                    emit(
                        new MIPSInstruction(MIPSOp.LI, null,
                        new Register("$" + dest.toString(), true), new Imm(src.toString(),"DEC"))
                    );
                    //emit("li $" + dest + ", " + src);

                }

            }

            case ADD -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                boolean src1const = src1 instanceof IRConstantOperand;
                boolean src2const = src2 instanceof IRConstantOperand;

                if (src1const && src2const) { //both are constants
                    //load one into a register
                    String tmp = "$tmp" + count++;
                    //emit("li " + tmp + ", " + src1);
                    emit(
                        new MIPSInstruction(MIPSOp.LI, null, 
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC"))
                    );
                    //emit("addi $" + dest + ", " + tmp + ", " + src2);
                    emit(
                        new MIPSInstruction(MIPSOp.ADDI, null,
                        new Register("$" + dest.toString(), true),
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC"))

                    );

                } else if (!src1const && src2const) { //src1 is a variable and src2 is a constant
                    //emit("addi $" + dest + ", $" + src1 + ", " + src2);
                    emit(
                        new MIPSInstruction(MIPSOp.ADDI, null,
                        new Register("$" + dest.toString(), true),
                        new Register("$" + src1.toString(), true),
                        new Imm(src2.toString(), "DEC")
                        )
                    );
                } else if (src1const && !src2const) {
                    //emit("addi $" + dest + ", $" + src2 + ", " + src1);
                    emit(
                        new MIPSInstruction(MIPSOp.ADDI, null,
                        new Register("$" + dest.toString(), true),
                        new Register("$" + src2.toString(), true),
                        new Imm(src1.toString(), "DEC"))
                    );
                } else { //both are vars
                    //emit("add $" + dest + ", $" + src1 + ", $" + src2); //prints out MIPS instruction, write a new function that
                    emit(
                        new MIPSInstruction(MIPSOp.ADD, null,
                        new Register("$" + dest.toString(), true),
                        new Register("$" + src1.toString(), true),
                        new Register("$" + src2.toString(), true))
                    );
                }
            }

            case SUB -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                boolean src1const = src1 instanceof IRConstantOperand;
                boolean src2const = src2 instanceof IRConstantOperand;

                if (!src1const && !src2const) { //both vars
                    emit(new MIPSInstruction(MIPSOp.SUB, null,
                        new Register("$" + dest.toString(), true),
                        new Register("$" + src1.toString(), true),
                        new Register("$" + src2.toString(), true)));
                } else if (!src1const) { //src1 var, src2 const
                    
                    emit(new MIPSInstruction(MIPSOp.ADDI, null,
                        new Register("$" + dest.toString(), true),
                        new Register("$" + src1.toString(), true),
                        new Imm("-" + src2.toString(), "DEC")));
                } else if (!src2const) {
                
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    emit(new MIPSInstruction(MIPSOp.SUB, null,
                        new Register("$" + dest.toString(), true),
                        new Register(tmp, true),
                        new Register("$" + src2.toString(), true)));
                } else {
                    
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    emit(new MIPSInstruction(MIPSOp.ADDI, null,
                        new Register("$" + dest.toString(), true),
                        new Register(tmp, true),
                        new Imm("-" + src2.toString(), "DEC")));
                }

            }

            case MULT -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                Register r1;
                Register r2;
                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    //emit("li " + tmp1 + ", " + src1);
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else { //put in register if it is not a constant
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    //emit("li " + tmp2 + ", " + src2);
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }
                //emit("mult $" + dest + ", " + tmp1 + ", " + tmp2);
                emit(new MIPSInstruction(MIPSOp.MUL, null,
                    new Register("$" + dest.toString(), true),
                    r1,
                    r2));
            }

            case DIV -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String tmp1 = "$" + src1;
                // String tmp2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     tmp1 = "$tmp" + count;
                //     emit("li " + tmp1 + ", " + src1);
                //     count += 1;
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     tmp2 = "$tmp" + count;
                //     emit("li " + tmp2 + ", " + src2);
                //     count += 1;
                // }
                // emit("div $" + dest + ", " + tmp1 + ", " + tmp2);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.DIV, null,
                    new Register("$" + dest.toString(), true),
                    r1,
                    r2));
            }
            case AND -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String tmp1 = "$" + src1;
                // String tmp2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     tmp1 = "$tmp" + count;
                //     emit("li " + tmp1 + ", " + src1);
                //     count += 1;
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     tmp2 = "$tmp" + count;
                //     emit("li " + tmp2 + ", " + src2);
                //     count += 1;
                // }
                // emit("and $" + dest + ", " + tmp1 + ", " + tmp2);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.AND, null,
                    new Register("$" + dest.toString(), true),
                    r1,
                    r2));

            }
            case OR -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String tmp1 = "$" + src1;
                // String tmp2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     tmp1 = "$tmp" + count;
                //     emit("li " + tmp1 + ", " + src1);
                //     count += 1;
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     tmp2 = "$tmp" + count;
                //     emit("li " + tmp2 + ", " + src2);
                //     count += 1;
                // }
                // emit("or $" + dest + ", " + tmp1 + ", " + tmp2);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.OR, null,
                    new Register("$" + dest.toString(), true),
                    r1,
                    r2));

            } //---------------------------------------------------------------------
            case GOTO -> { //TODO:
                IROperand labelName = instr.operands[0];
                //emit("j " + labelName);

                //addr is a memory address operand - which is a label
                emit(new MIPSInstruction(MIPSOp.J, null, new Addr(labelName.toString()))
                );
            }
            //the last two operands will be the same type
            case BREQ -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String reg1 = "$" + src1;
                // String reg2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     reg1 = "$tmp" + count;
                //     count += 1;
                //     emit("li " + reg1 + ", " + src1);
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     reg2 = "$tmp" + count;
                //     count += 1;
                //     emit("li " + reg2 + ", " + src2);
                // }
                // emit("beq " + reg1 + ", " + reg2 + ", " + labelName);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.BEQ, null,
                    r1, r2, new Addr(labelName.toString())));

            }
            case BRNEQ -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String reg1 = "$" + src1;
                // String reg2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     reg1 = "$tmp" + count;
                //     count += 1;
                //     emit("li " + reg1 + ", " + src1);
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     reg2 = "$tmp" + count++;
                //     emit("li " + reg2 + ", " + src2);
                // }
                // emit("bne " + reg1 + ", " + reg2 + ", " + labelName);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.BNE, null,
                    r1, r2, new Addr(labelName.toString())));

            }
            case BRLT -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String reg1 = "$" + src1;
                // String reg2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     reg1 = "$tmp" + count++;
                //     emit("li " + reg1 + ", " + src1);
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     reg2 = "$tmp" + count++;
                //     emit("li " + reg2 + ", " + src2);
                // }
                // emit("blt " + reg1 + ", " + reg2 + ", " + labelName);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.BLT, null,
                    r1, r2, new Addr(labelName.toString())));

            }
            case BRGT -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String reg1 = "$" + src1;
                // String reg2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     reg1 = "$tmp" + count++;
                //     emit("li " + reg1 + ", " + src1);
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     reg2 = "$tmp" + count++;
                //     emit("li " + reg2 + ", " + src2);
                // }
                // emit("bgt " + reg1 + ", " + reg2 + ", " + labelName);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.BGT, null,
                    r1, r2, new Addr(labelName.toString())));

            }
            case BRGEQ -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                // String reg1 = "$" + src1;
                // String reg2 = "$" + src2;
                // if (src1 instanceof IRConstantOperand) {
                //     reg1 = "$tmp" + count++;
                //     emit("li " + reg1 + ", " + src1);
                // }
                // if (src2 instanceof IRConstantOperand) {
                //     reg2 = "$tmp" + count++;
                //     emit("li " + reg2 + ", " + src2);
                // }
                // emit("bge " + reg1 + ", " + reg2 + ", " + labelName);

                Register r1;
                Register r2;

                if (src1 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src1.toString(), "DEC")));
                    r1 = new Register(tmp, true);
                } else {
                    r1 = new Register("$" + src1.toString(), true);
                }

                if (src2 instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src2.toString(), "DEC")));
                    r2 = new Register(tmp, true);
                } else {
                    r2 = new Register("$" + src2.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.BGE, null,
                    r1, r2, new Addr(labelName.toString())));

            }
            case RETURN -> {
                IROperand src = instr.operands[0];

                // String raReg = "$" + src;
                // if (src instanceof IRConstantOperand) {
                //     raReg = "$tmp" + count;
                //     count += 1;
                //     emit("li " + raReg + ", " + src);
                // }
                // emit("move $v0, " + raReg);
                // emit("jr $ra");

                Register r;

                if (src instanceof IRConstantOperand) {
                    String tmp = "$tmp" + count++;
                    emit(new MIPSInstruction(MIPSOp.LI, null,
                        new Register(tmp, true),
                        new Imm(src.toString(), "DEC")));
                    r = new Register(tmp, true);
                } else {
                    r = new Register("$" + src.toString(), true);
                }

                emit(new MIPSInstruction(MIPSOp.MOVE, null,
                    new Register("$v0", false), r)); //TODO: should this be virtual?

                emit(new MIPSInstruction(MIPSOp.JR, null,
                    new Register("$ra", false))); //TODO: show this be virtual?

            }
            case CALL -> {
                IROperand funcOp = instr.operands[0];

                Register sp = new Register("$sp", false);

                for (int i = instr.operands.length - 1; i > 0; i--) { //pushing the args in reverse order
                    Register argReg;
                    if (instr.operands[i] instanceof IRConstantOperand) {
                        String tmp = "$tmp" + count++;
                        emit(new MIPSInstruction(MIPSOp.LI, null,
                            new Register(tmp, true),
                            new Imm(instr.operands[i].toString(), "DEC")));
                        argReg = new Register(tmp, true);
                    } else {
                        argReg = new Register("$" + instr.operands[i].toString(), true);
                    }
                    emit(new MIPSInstruction(MIPSOp.ADDI, null,
                        sp, sp, new Imm("-4", "DEC")));
                    emit(new MIPSInstruction(MIPSOp.SW, null,
                        argReg, new Addr(new Imm("0", "DEC"), sp)));
                }

                emit(new MIPSInstruction(MIPSOp.JAL, null,
                    new Addr(funcOp.toString())));

                emit(new MIPSInstruction(MIPSOp.ADDI, null,
                    sp, sp, new Imm(String.valueOf(4 * (instr.operands.length - 1)), "DEC")));

            }
            case CALLR -> {
                IROperand dest = instr.operands[0];
                IROperand funcOp = instr.operands[1];

                Register sp = new Register("$sp", false); //TODO: should this be virtual?

                for (int i = instr.operands.length - 1; i > 1; i--) { //pushing the args in reverse order
                    Register argReg;
                    if (instr.operands[i] instanceof IRConstantOperand) {
                        String tmp = "$tmp" + count++;
                        emit(new MIPSInstruction(MIPSOp.LI, null,
                            new Register(tmp, true),
                            new Imm(instr.operands[i].toString(), "DEC")));
                        argReg = new Register(tmp, true);
                    } else {
                        argReg = new Register("$" + instr.operands[i].toString(), true);
                    }
                    emit(new MIPSInstruction(MIPSOp.ADDI, null,
                        sp, sp, new Imm("-4", "DEC")));
                    emit(new MIPSInstruction(MIPSOp.SW, null,
                        argReg, new Addr(new Imm("0", "DEC"), sp)));
                }

                emit(new MIPSInstruction(MIPSOp.JAL, null,
                    new Addr(funcOp.toString())));

                emit(new MIPSInstruction(MIPSOp.MOVE, null,
                    new Register("$" + dest.toString(), true),
                    new Register("$v0", false)));

                emit(new MIPSInstruction(MIPSOp.ADDI, null,
                    sp, sp, new Imm(String.valueOf(4 * (instr.operands.length - 2)), "DEC")));

            }
            case ARRAY_STORE -> {
                IROperand src = instr.operands[0];
                IROperand arr = instr.operands[1];
                IROperand idx = instr.operands[2];

                if (idx instanceof IRConstantOperand) {
                    int offset = 4 * Integer.parseInt(((IRConstantOperand) idx).getValueString());
                    // emit("sw $" + src + ", " + offset + "($" + arr + ")");
                    emit(new MIPSInstruction(MIPSOp.SW, null,
                        new Register("$" + src.toString(), true),
                        new Addr(new Imm(String.valueOf(offset), "DEC"), new Register("$" + arr.toString(), true))));
                } else {
                    // emit("sll $" + idx + ", $" + idx + ", 2");
                    emit(new MIPSInstruction(MIPSOp.SLL, null,
                        new Register("$" + idx.toString(), true),
                        new Register("$" + idx.toString(), true),
                        new Imm("2", "DEC")));
                    // emit("add $" + idx + ", $" + arr + ", $" + idx);
                    emit(new MIPSInstruction(MIPSOp.ADD, null,
                        new Register("$" + idx.toString(), true),
                        new Register("$" + arr.toString(), true),
                        new Register("$" + idx.toString(), true)));
                    // emit("sw $" + src + ", 0($" + idx + ")");
                    emit(new MIPSInstruction(MIPSOp.SW, null,
                        new Register("$" + src.toString(), true),
                        new Addr(new Imm("0", "DEC"), new Register("$" + idx.toString(), true))));
                }

            }
            case ARRAY_LOAD -> {
                IROperand dest = instr.operands[0];
                IROperand arr = instr.operands[1];
                IROperand idx = instr.operands[2];

                if (idx instanceof IRConstantOperand) {
                    int offset = 4 * Integer.parseInt(((IRConstantOperand) idx).getValueString());
                    // emit("lw $" + dest + ", " + offset + "($" + arr + ")");
                    emit(new MIPSInstruction(MIPSOp.LW, null,
                        new Register("$" + dest.toString(), true),
                        new Addr(new Imm(String.valueOf(offset), "DEC"), new Register("$" + arr.toString(), true))));
                } else {
                    // emit("sll $" + idx + ", $" + idx + ", 2");
                    emit(new MIPSInstruction(MIPSOp.SLL, null,
                        new Register("$" + idx.toString(), true),
                        new Register("$" + idx.toString(), true),
                        new Imm("2", "DEC")));
                    // emit("add $" + idx + ", $" + arr + ", $" + idx);
                    emit(new MIPSInstruction(MIPSOp.ADD, null,
                        new Register("$" + idx.toString(), true),
                        new Register("$" + arr.toString(), true),
                        new Register("$" + idx.toString(), true)));
                    // emit("lw $" + dest + ", 0($" + idx + ")");
                    emit(new MIPSInstruction(MIPSOp.LW, null,
                        new Register("$" + dest.toString(), true),
                        new Addr(new Imm("0", "DEC"), new Register("$" + idx.toString(), true))));
                }
            }
            case LABEL -> {
                IROperand labelName = instr.operands[0];
                // emit(labelName + ":");
                labels.put(labelName.toString(), mipsIdx);
                out.println("   " + labelName + ":");
            }

        }
    }
}
