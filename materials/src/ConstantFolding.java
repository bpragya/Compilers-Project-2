import java.util.HashSet;
import java.util.Set;

import ir.IRInstruction;
import ir.IRInstruction.OpCode;
import ir.datatype.IRIntType;
import ir.operand.IRConstantOperand;
import ir.operand.IROperand;

public class ConstantFolding {
  private Set<OpCode> foldableSet;

  public ConstantFolding() {
    this.foldableSet = Set.of(OpCode.ADD, OpCode.SUB, OpCode.MULT, OpCode.DIV, OpCode.AND, OpCode.OR);
  }

  public boolean run(CFG cfg) {
    boolean changed = false;
    for (BasicBlock block : cfg.getBlocks()) {
      changed |= foldBlock(block);
      // TODO: Finish
    }
  }

  private boolean foldBlock(BasicBlock block) {
    boolean changed = false;
    for (IRInstruction inst : block.instructions) {
      if (isFoldable(inst)) {
        foldInstruction(inst);
        // TODO: Finish
      }
    }
  }

  private boolean isFoldable(IRInstruction instruction) {
    return foldableSet.contains(instruction.opCode)
        && (instruction.operands[1] instanceof IRConstantOperand)
        && (instruction.operands[2] instanceof IRConstantOperand);
  }

  private void foldInstruction(IRInstruction inst) {
    Number leftOperand = parse(inst.operands[1]);
    Number rightOperand = parse(inst.operands[2]);
    Number result = evaluate(inst.opCode, leftOperand, rightOperand);

  }

  private Number parse(IRConstantOperand operand) {
    if (operand.type instanceof IRIntType) {
      return Integer.parseInt(operand.getValueString());
    } else {
      return Float.parseFloat(operand.getValueString());
    }
  }

  private Number evaluate(OpCode operation, Number leftOperand, Number rightOperand) {
    if (leftOperand instanceof Integer) {

    }

  }
}
