import ir.IRFunction;
import ir.IRInstruction;
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
import mips.MIPSProgram;  

public class NaiveAllocator{

    public NaiveAllocator() {
        //not sure what to do here?

    }

    private PrintStream out;
    

    public Map<Integer, MIPSInstruction> getInstructions() {
        return instructions;
    }

    public Map<String, Integer> getLabels() {
        return labels;
    }


    private void emit(MIPSInstruction instr) { //should i create a new insturctions hashmap?
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
        for (MIPSInstruction inst: mipsProg.instructions.values()) {
            
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

        int frameSize = 4 * vrMap.size();
        emit(new MIPSInstruction(MIPSOp.ADDI, null,
            sp,
            sp,
            new Imm(String.valueOf(-frameSize), "DEC"))
            );

        //3) For each instruction, load the operands / virtual registers into physical registers 
        // and output it in a MIPS instruction with the physical registers.
        
        for (MIPSInstruction inst : mipsProg.instructions.values()) {

            int count = 0; //reset for each instruction

            if (inst.op == MIPSOp.JAL) {
                //save t0-t9 before call
                for (int i = 0; i < 10; i++) {
                    //push the temp register on the stack
                    emit(new MIPSInstruction(MIPSOp.ADDI, null,
                            sp,
                            sp,
                            new Imm("-4", "DEC"))
                    );
                    emit(new MIPSInstruction(MIPSOp.SW, null,
                        new Register("$t" + i, false),
                        new Addr(new Imm("0", "DEC"),
                        sp)));
                
                }

                emit(inst); // - working with virtual registers i think?

                //pop t0-t9 after call
                for (int i = 9; i > -1; i--) {
                    //pop the temp register on the stack
                    emit(new MIPSInstruction(MIPSOp.LW, null,
                        new Register("$t" + i, false),
                        new Addr(new Imm("0", "DEC"),
                        sp)));
                    emit(new MIPSInstruction(MIPSOp.ADDI, null,
                            sp,
                            sp,
                            new Imm("4", "DEC"))
                    );
                    
                
                }

            } else {

                for (Register r : inst.getReads()) {
                    Register t = new Register("$t" + count, false);

                    //load the operands into temp registers
                    emit(new MIPSInstruction(MIPSOp.LW, null, 
                            t,
                            new Addr(new Imm(String.valueOf(vrMap.get(r.name)), "DEC"), 
                            new Register("$fp", false)))
                        );

                    
                    count+=1;
                    
                }
                Register w = inst.getWrite();
                if (w != null && w.isVirtual) {
                    emit(new MIPSInstruction(MIPSOp.SW, null,
                                new Register("$t" + count, false), 
                                new Addr(new Imm(String.valueOf(vrMap.get(w.name)), "DEC"), 
                                new Register("$fp", false))));
                }
            }
            
            // Step 4: execute the insturction - rebuild the instruction TODO:

            //after instruction executes store the computed value in a temp register and
            //its stack slot'
            //TODO:
            
        }


        // Epiloge: restore $fp, $ra
        //pop value
        //increment stack pointer
        //set fp to the sp location
        // Register sp = new Register("$sp", false);
        // Register ra = new Register("$ra", false);

        //move sp back up to the frame pointer -- is this correct?
        emit(new MIPSInstruction(MIPSOp.MOVE, null, sp, fp));

        //pop fp on the stack
        //Register fp = new Register("$fp", false);
        emit(new MIPSInstruction(MIPSOp.LW, null, fp, new Addr(new Imm("0", "DEC"), sp)));
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));

        //pop ra on the stack
        emit(new MIPSInstruction(MIPSOp.LW, null, ra, new Addr(new Imm("0", "DEC"), sp)));
        emit(new MIPSInstruction(MIPSOp.ADDI, null, sp, sp, new Imm("4", "DEC")));

        emit(new MIPSInstruction(MIPSOp.JR, null, ra));


        
        
        

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