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
import main.java.mips.MIPSProgram;  

public class NaiveAllocator{

    public NaiveAllocator() {

    }

    private void emit(MIPSInstruction instr) {
        instructions.put(mipsIdx, instr);
        mipsIdx+=1;
        out.println("   " + instr);

    } 

    public void naiveAllocator(IRFunction func, MIPSProgram mipsProg) {

        Map<String, Integer> vrMap = new HashMap<>(); //virtual register name, offset

        //1) Prologue: save $ra, $fp on the stack, set $fp to the $sp location

        //push ra on the stack
        Register sp = new Register("$sp", false);
        Register ra = new Register("$ra", false);
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.SW, null, ra, new Addr(new Imm("0", "DEC"), sp)));

        //push fp on the stack
        Register fp = new Register("$fp", false);
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("-4", "DEC")));
        emit(new MIPSInstruction(MIPSOp.SW, null, fp, new Addr(new Imm("0", "DEC"), sp)));
        
        //set fp to the sp location
        emit(new MIPSInstruction(MIPSOp.MOVE, null, fp, sp));
        
        //2) Iterate through the MIPS function and create a hashmap of the virtual registers 
        // and its offset on the stack (in relation to the fp). 
        // Allocate memory the size of # of vr * 4 on the stack

        int offset = -4;
        for (IRVariableOperand var: func.variables) {
            
            if (!vrMap.containsKey(var.getName())) { //not in the hashmap, so put it in there
                vrMap.put("$" + var.getName(), offset);
                offset-=4;
            } 
            
        }

        int frameSize = 4 * vrMap.size();
        emit(new MIPSInstruction(MIPSOp.ADDI, null,
            new Register("$sp", false),
            new Register("$sp", false),
            new Imm(String.valueOf(-frameSize), "DEC"))
            );

        //3) For each instruction, load the operands / virtual registers into physical registers 
        // and output it in a MIPS instruction with the physical registers.


    }




    }

/*
public MIPSInstruction(MIPSOp op, String label, MIPSOperand... operands) {
        this.op = op;
        this.label = label;
        this.operands = Arrays.asList(operands);
    }

*/



    


/*

Instruction selection generates a MIPS program.

1) Prologue: save $ra, $fp on the stack, set $fp to the $sp location

2) Iterate through the MIPS function and create a hashmap of the virtual registers and its offset on the stack (in relation to the fp). Allocate memory the size of # of vr * 4 on the stack

3) For each instruction, load the operands / virtual registers into physical registers and output it in a MIPS instruction with the physical registers.

4) Save the result in that value's stack slot

5) Epiloge: restore $ra, $fp


*/