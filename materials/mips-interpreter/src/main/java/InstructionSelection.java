import ir.IRFunction;
import ir.IRInstruction;
import ir.operand.IRConstantOperand;
import ir.operand.IROperand;
import ir.operand.IRVariableOperand;

import java.io.PrintStream;

public class InstructionSelection {

    
    // private PrintStream out;

    // public InstructionSelection(PrintStream out) {
    //     this.out = out;
    // }

    // private void emit(String s) {
    //     out.println("  " + s);
    // } - was having claude fix my compilation errors and not sure what this does

    //not sure if this structure is correct
    int count = 0;

    public void generateInstructions(IRFunction func, IRInstruction instr) {

        switch (instr.opCode) {
            case ASSIGN -> {
                IROperand dest = instr.operands[0];
                IROperand src = instr.operands[1];

                if (src instanceof IRVariableOperand) {
                    //move from one register to another
                    emit("move $" + dest + ", " + "$" + src);

                } else /*if (src instanceof IRConstantOperand)*/ {
                    emit("li $" + dest + ", " + src);

                }

            }

            case ADD -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String tmp1 = "$" + src1;
                String tmp2 = "$" + src2;

                if (src1 instanceof IRConstantOperand) {
                    tmp1 = "$tmp" + count; //?

                    emit("li " + tmp1 + ", " + src1);
                    count += 1;
                }
                if (src2 instanceof IRConstantOperand) {
                    tmp2 = "$tmp" + count; //?

                    emit("li " + tmp2 + ", " + src2);
                    count += 1;
                }

                //now constants are in registers

                emit("add $" + dest + ", " + tmp1 + ", " + tmp2);


            }

            case SUB -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String tmp1 = "$" + src1;
                String tmp2 = "$" + src2;

                if (src1 instanceof IRConstantOperand) {
                    tmp1 = "$tmp" + count; //?

                    emit("li " + tmp1 + ", " + src1);
                    count += 1;
                }
                if (src2 instanceof IRConstantOperand) {
                    tmp2 = "$tmp" + count; //?

                    emit("li $" + tmp2 + ", " + src2);
                    count += 1;
                }

                //now constants are in registers

                emit("sub $" + dest + ", " + tmp1 + ", " + tmp2);


            }

            case MULT -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String tmp1 = "$" + src1;
                String tmp2 = "$" + src2;

                if (src1 instanceof IRConstantOperand) {
                    tmp1 = "$tmp" + count; //?

                    emit("li " + tmp1 + ", " + src1);
                    count += 1;
                }
                if (src2 instanceof IRConstantOperand) {
                    tmp2 = "$tmp" + count; //?

                    emit("li $" + tmp2 + ", " + src2);
                    count += 1;
                }

                //now constants are in registers

                emit("mult $" + dest + ", " + tmp1 + ", " + tmp2);


            }

            case DIV -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String tmp1 = "$" + src1;
                String tmp2 = "$" + src2;

                if (src1 instanceof IRConstantOperand) {
                    tmp1 = "$tmp" + count; //?

                    emit("li " + tmp1 + ", " + src1);
                    count += 1;
                }
                if (src2 instanceof IRConstantOperand) {
                    tmp2 = "$tmp" + count; //?

                    emit("li $" + tmp2 + ", " + src2);
                    count += 1;
                }

                //now constants are in registers

                emit("div $" + dest + ", " + tmp1 + ", " + tmp2);

            }

            case AND -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String tmp1 = "$" + src1;
                String tmp2 = "$" + src2;

                if (src1 instanceof IRConstantOperand) {
                    tmp1 = "$tmp" + count; //?

                    emit("li " + tmp1 + ", " + src1);
                    count += 1;
                }
                if (src2 instanceof IRConstantOperand) {
                    tmp2 = "$tmp" + count; //?

                    emit("li $" + tmp2 + ", " + src2);
                    count += 1;
                }

                //now constants are in registers

                emit("and $" + dest + ", " + tmp1 + ", " + tmp2);


            }

            case OR -> {
                IROperand dest = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String tmp1 = "$" + src1;
                String tmp2 = "$" + src2;

                if (src1 instanceof IRConstantOperand) {
                    tmp1 = "$tmp" + count; //?

                    emit("li " + tmp1 + ", " + src1);
                    count += 1;
                }
                if (src2 instanceof IRConstantOperand) {
                    tmp2 = "$tmp" + count; //?

                    emit("li $" + tmp2 + ", " + src2);
                    count += 1;
                }

                //now constants are in registers

                emit("or $" + dest + ", " + tmp1 + ", " + tmp2);



            }
            case GOTO -> {
                IROperand labelName = instr.operands[0];
                emit("j " + labelName);

            }

            //the last two operands will be the same type
            case BREQ -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String reg1 = "$" + src1;
                String reg2 = "$" + src2;


                //register, register, label
                if (src1 instanceof IRConstantOperand) {
                    //put the constant in a register

                    reg1 = "$tmp" + count; //?
                    count += 1;

                    reg2 = "$tmp" + count; //?
                    count += 1;

                    emit("li " + reg1 + ", " + src1);
                    emit("li " + reg2 + ", " + src2);

                }


                emit("beq " + reg1 + ", " + reg2 + ", " + labelName);


            }
            case BRNEQ -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String reg1 = "$" + src1;
                String reg2 = "$" + src2;

                //register, register, label
                if (src1 instanceof IRConstantOperand) {
                    //put the constant in a register

                    reg1 = "$tmp" + count; //?
                    count += 1;

                    reg2 = "$tmp" + count; //?
                    count += 1;

                    emit("li " + reg1 + ", " + src1);
                    emit("li " + reg2 + ", " + src2);

                }


                emit("bne " + reg1 + ", " + reg2 + ", " + labelName);


            }
            case BRLT -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String reg1 = "$" + src1;
                String reg2 = "$" + src2;

                //register, register, label
                if (src1 instanceof IRConstantOperand) {
                    //put the constant in a register

                    reg1 = "$tmp" + count; //?
                    count += 1;

                    reg2 = "$tmp" + count; //?
                    count += 1;

                    emit("li " + reg1 + ", " + src1);
                    emit("li " + reg2 + ", " + src2);

                }


                emit("blt " + reg1 + ", " + reg2 + ", " + labelName);


            }
            case BRGT -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String reg1 = "$" + src1;
                String reg2 = "$" + src2;


                //register, register, label
                if (src1 instanceof IRConstantOperand) {
                    //put the constant in a register

                    reg1 = "$tmp" + count; //?
                    count += 1;

                    reg2 = "$tmp" + count; //?
                    count += 1;

                    emit("li " + reg1 + ", " + src1);
                    emit("li " + reg2 + ", " + src2);

                }


                emit("bgt " + reg1 + ", " + reg2 + ", " + labelName);


            }
            case BRGEQ -> {
                IROperand labelName = instr.operands[0];
                IROperand src1 = instr.operands[1];
                IROperand src2 = instr.operands[2];

                String reg1 = "$" + src1;
                String reg2 = "$" + src2;


                //register, register, label
                if (src1 instanceof IRConstantOperand) {
                    //put the constant in a register

                    reg1 = "$tmp" + count; //?
                    count += 1;

                    reg2 = "$tmp" + count; //?
                    count += 1;

                    emit("li " + reg1 + ", " + src1);
                    emit("li " + reg2 + ", " + src2);

                }


                emit("bge " + reg1 + ", " + reg2 + ", " + labelName);


            }
            case RETURN -> {  //always called with an arg
                IROperand src = instr.operands[0];

                //put src into a register

                String raReg = "$" + src;

                if (src instanceof IRConstantOperand) {
                    raReg = "$tmp" + count;
                    count += 1;
                    emit("li " + raReg + ", " + src);
                }
                //puts the return value in v0
                emit("move $v0 ," + raReg);

                //jumps back to $ra
                emit("jr $ra");


            }
            case CALL -> {
                IROperand funcOp = instr.operands[0];

                //how to get the args? 1->n
                for (int i = 1; i < instr.operands.length; i++) {
                    //pushing the args onto the stack

                    String argReg = "$" + instr.operands[i]; //value is not in a register yet

                    if (instr.operands[i] instanceof IRConstantOperand) {
                        argReg = "$tmp" + count;
                        count += 1;

                        //load the value in the register
                        emit("li " + argReg + ", " + instr.operands[i]);
                    }

                    //push value onto stack - which order??
                    emit("addi $sp, $sp, -4");
                    emit("sw " + argReg + ", 0($sp)");

                }

                //jump to the function
                emit("jal " + funcOp); //saves the ra

                //pop all the values
                emit("addi $sp, $sp, " + (4 * (instr.operands.length - 1)));

            }
            case CALLR -> {
                IROperand dest = instr.operands[0];
                IROperand funcOp = instr.operands[1];

                //how to get the args 2->n
                for (int i = 2; i < instr.operands.length; i++) {
                    //pushing the args onto the stack

                    String argReg = "$" + instr.operands[i];

                    if (instr.operands[i] instanceof IRConstantOperand) {
                        argReg = "$tmp" + count;
                        count += 1;

                        //load the value in the register
                        emit("li " + argReg + ", " + instr.operands[i]);
                    }

                    //push value onto stack - which order??
                    emit("addi $sp, $sp, -4");
                    emit("sw " + argReg + ", 0($sp)");

                }

                //jump to the function
                emit("jal " + funcOp);

                //store the return value ($v0) in dest
                emit("move $" + dest + ", $v0");
                emit("addi $sp, $sp, " + (4 * (instr.operands.length - 2)));


            }
            case ARRAY_STORE -> {
                IROperand src = instr.operands[0];
                IROperand arr = instr.operands[1];
                IROperand idx = instr.operands[2];

                if (idx instanceof IRConstantOperand) {
                    emit("sw $" + src + ", " + idx + "(" + arr + ")");

                } else { //idx is a variable
                    emit("sll $" + idx + ", $" + idx + ", 2"); //idx = idx * 4
                    emit("add $" + idx + ", $" + arr + ", $" + idx);  // idx = base + idx
                    emit("sw $" + src + ", 0($" + idx + ")");
                }


            }
            case ARRAY_LOAD -> {
                IROperand dest = instr.operands[0];
                IROperand arr = instr.operands[1];
                IROperand idx = instr.operands[2];

                if (idx instanceof IRConstantOperand) {
                    emit("lw $" + dest + ", " + idx + "(" + arr + ")");

                } else { //idx is a variable
                    emit("sll $" + idx + ", $" + idx + ", 2"); //idx = idx * 4
                    emit("add $" + idx + ", $" + arr + ", $" + idx);  // idx = base + idx
                    emit("lw $" + dest + ", 0($" + idx + ")");
                }


            }
            case LABEL -> {
                IROperand labelName = instr.operands[0];
                emit(labelName + ":");

            }

        }
    }




}
