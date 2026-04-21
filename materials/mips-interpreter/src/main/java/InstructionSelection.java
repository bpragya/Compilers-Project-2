import ir.IRFunction;
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

public class InstructionSelection {

  private PrintStream out;
  private Map<Integer, MIPSInstruction> instructions;
  private Map<String, Integer> labels;
  private int mipsIdx = 0;
  private int addrTmpCounter = 0;

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
    mipsIdx += 1;
    out.println("   " + instr);
  }

  // not sure if this structure is correct
  int count = 0;

  public void generateInstructions(IRInstruction instr) {

    switch (instr.opCode) {
      case ASSIGN -> {
        IROperand dest = instr.operands[0];
        IROperand src = instr.operands[1];

        if (src instanceof IRVariableOperand) {
          emit(new MIPSInstruction(MIPSOp.MOVE, null,
              new Register("$" + dest.toString(), true), new Register("$" + src.toString(), true)));

        } else /* if (src instanceof IRConstantOperand) */ {
          emit(
              new MIPSInstruction(MIPSOp.LI, null,
                  new Register("$" + dest.toString(), true), new Imm(src.toString(), "DEC")));
        }

      }

      case ADD -> {
        IROperand dest = instr.operands[0];
        IROperand src1 = instr.operands[1];
        IROperand src2 = instr.operands[2];

        boolean src1const = src1 instanceof IRConstantOperand;
        boolean src2const = src2 instanceof IRConstantOperand;

        if (src1const && src2const) { // both are constants
          String tmp = "$tmp" + count++;
          emit(
              new MIPSInstruction(MIPSOp.LI, null,
                  new Register(tmp, true),
                  new Imm(src1.toString(), "DEC")));
          emit(
              new MIPSInstruction(MIPSOp.ADDI, null,
                  new Register("$" + dest.toString(), true),
                  new Register(tmp, true),
                  new Imm(src2.toString(), "DEC"))

          );

        } else if (!src1const && src2const) {
          emit(
              new MIPSInstruction(MIPSOp.ADDI, null,
                  new Register("$" + dest.toString(), true),
                  new Register("$" + src1.toString(), true),
                  new Imm(src2.toString(), "DEC")));
        } else if (src1const && !src2const) {
          emit(
              new MIPSInstruction(MIPSOp.ADDI, null,
                  new Register("$" + dest.toString(), true),
                  new Register("$" + src2.toString(), true),
                  new Imm(src1.toString(), "DEC")));
        } else {
          emit(
              new MIPSInstruction(MIPSOp.ADD, null,
                  new Register("$" + dest.toString(), true),
                  new Register("$" + src1.toString(), true),
                  new Register("$" + src2.toString(), true)));
        }
      }

      case SUB -> {
        IROperand dest = instr.operands[0];
        IROperand src1 = instr.operands[1];
        IROperand src2 = instr.operands[2];

        boolean src1const = src1 instanceof IRConstantOperand;
        boolean src2const = src2 instanceof IRConstantOperand;

        if (!src1const && !src2const) { // both vars
          emit(new MIPSInstruction(MIPSOp.SUB, null,
              new Register("$" + dest.toString(), true),
              new Register("$" + src1.toString(), true),
              new Register("$" + src2.toString(), true)));
        } else if (!src1const) { // src1 var, src2 const
          IRConstantOperand src2c = (IRConstantOperand) src2;
          if (src2c.type instanceof IRFloatType) {
            String tmp = "$tmp" + count++;
            emit(new MIPSInstruction(MIPSOp.LI_S, null,
                new Register(tmp, true),
                new Imm(src2.toString(), "DEC")));
            emit(new MIPSInstruction(MIPSOp.SUB_S, null,
                new Register("$" + dest.toString(), true),
                new Register("$" + src1.toString(), true),
                new Register(tmp, true)));
          } else {
            int negated = -Integer.parseInt(src2.toString());
            emit(new MIPSInstruction(MIPSOp.ADDI, null,
                new Register("$" + dest.toString(), true),
                new Register("$" + src1.toString(), true),
                new Imm(String.valueOf(negated), "DEC")));
          }
        } else if (!src2const) {

          String tmp = "$tmp" + count++;
          emit(new MIPSInstruction(MIPSOp.LI, null,
              new Register(tmp, true),
              new Imm(src1.toString(), "DEC")));
          emit(new MIPSInstruction(MIPSOp.SUB, null,
              new Register("$" + dest.toString(), true),
              new Register(tmp, true),
              new Register("$" + src2.toString(), true)));
        } else { // both const
          IRConstantOperand src2c = (IRConstantOperand) src2;
          if (src2c.type instanceof IRFloatType) {
            String tmp1 = "$tmp" + count++;
            String tmp2 = "$tmp" + count++;
            emit(new MIPSInstruction(MIPSOp.LI_S, null,
                new Register(tmp1, true),
                new Imm(src1.toString(), "DEC")));
            emit(new MIPSInstruction(MIPSOp.LI_S, null,
                new Register(tmp2, true),
                new Imm(src2.toString(), "DEC")));
            emit(new MIPSInstruction(MIPSOp.SUB_S, null,
                new Register("$" + dest.toString(), true),
                new Register(tmp1, true),
                new Register(tmp2, true)));
          } else {
            String tmp = "$tmp" + count++;
            emit(new MIPSInstruction(MIPSOp.LI, null,
                new Register(tmp, true),
                new Imm(src1.toString(), "DEC")));
            int negated = -Integer.parseInt(src2.toString());
            emit(new MIPSInstruction(MIPSOp.ADDI, null,
                new Register("$" + dest.toString(), true),
                new Register(tmp, true),
                new Imm(String.valueOf(negated), "DEC")));
          }
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
        emit(new MIPSInstruction(MIPSOp.MUL, null,
            new Register("$" + dest.toString(), true),
            r1,
            r2));
      }

      case DIV -> {
        IROperand dest = instr.operands[0];
        IROperand src1 = instr.operands[1];
        IROperand src2 = instr.operands[2];
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

      } // ---------------------------------------------------------------------
      case GOTO -> { // TODO:
        IROperand labelName = instr.operands[0];
        emit(new MIPSInstruction(MIPSOp.J, null, new Addr(labelName.toString())));
      }
      case BREQ -> {
        IROperand labelName = instr.operands[0];
        IROperand src1 = instr.operands[1];
        IROperand src2 = instr.operands[2];
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
        if (instr.operands != null && instr.operands.length > 0) {
          IROperand src = instr.operands[0];
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
              new Register("$v0", false), r)); // TODO: should this be virtual?
        }

        emit(new MIPSInstruction(MIPSOp.JR, null,
            new Register("$ra", false))); // TODO: show this be virtual?

      }
      case CALL -> {
        IROperand funcOp = instr.operands[0];

        Register sp = new Register("$sp", false);

        for (int i = instr.operands.length - 1; i > 0; i--) { // pushing the args in reverse order
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

        Register sp = new Register("$sp", false); // TODO: should this be virtual?

        for (int i = instr.operands.length - 1; i > 1; i--) { // pushing the args in reverse order
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
          String addrTmp = "$__addr" + (addrTmpCounter++);
          emit(new MIPSInstruction(MIPSOp.SLL, null,
              new Register(addrTmp, true),
              new Register("$" + idx.toString(), true),
              new Imm("2", "DEC")));
          emit(new MIPSInstruction(MIPSOp.ADD, null,
              new Register(addrTmp, true),
              new Register("$" + arr.toString(), true),
              new Register(addrTmp, true)));
          emit(new MIPSInstruction(MIPSOp.SW, null,
              new Register("$" + src.toString(), true),
              new Addr(new Imm("0", "DEC"), new Register(addrTmp, true))));
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
          String addrTmp = "$__addr" + (addrTmpCounter++);
          emit(new MIPSInstruction(MIPSOp.SLL, null,
              new Register(addrTmp, true),
              new Register("$" + idx.toString(), true),
              new Imm("2", "DEC")));
          emit(new MIPSInstruction(MIPSOp.ADD, null,
              new Register(addrTmp, true),
              new Register("$" + arr.toString(), true),
              new Register(addrTmp, true)));
          emit(new MIPSInstruction(MIPSOp.LW, null,
              new Register("$" + dest.toString(), true),
              new Addr(new Imm("0", "DEC"), new Register(addrTmp, true))));
        }
      }
      case LABEL -> {
        IROperand labelName = instr.operands[0];
        labels.put(labelName.toString(), mipsIdx);
        out.println("   " + labelName + ":");
      }

    }
  }
}
