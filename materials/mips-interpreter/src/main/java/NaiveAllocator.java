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

public class NaiveAllocator{

    public NaiveAllocator() {

    }

    public void naiveAllocator() {

        //push ra on the stack
        Register sp = new Register("$sp", false);
        Register ra = new Register("$ra", false);
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.SW, null, ra, new Addr(new Imm("0", "DEC"), sp)));

        //push fp on the stack
        Register fp = new Register("$fp", false);
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.SW, null, fp, new Addr(new Imm("0", "DEC"), sp)));

        



    }

/*
public MIPSInstruction(MIPSOp op, String label, MIPSOperand... operands) {
        this.op = op;
        this.label = label;
        this.operands = Arrays.asList(operands);
    }

*/



    
}

/*

Instruction selection generates a MIPS program.

1) Prologue: save $ra, $fp on the stack, set $fp to the $sp location

2) Iterate through the MIPS function and create a hashmap of the virtual registers and its offset on the stack (in relation to the fp). Allocate memory the size of # of vr * 4 on the stack

3) For each instruction, load the operands / virtual registers into physical registers and output it in a MIPS instruction with the physical registers.

4) Save the result in that value's stack slot

5) Epiloge: restore $ra, $fp


*/