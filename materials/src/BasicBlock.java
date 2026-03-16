import ir.IRInstruction;
import ir.operand.IRLabelOperand;

import java.util.ArrayList;
import java.util.List;

public class BasicBlock {
  private int id;
  List<IRInstruction> instructions;
  List<BasicBlock> successors;
  List<BasicBlock> predecessors;

  public BasicBlock(int id) {
    this.id = id;
    this.instructions = new ArrayList<>();
    this.successors = new ArrayList<>();
    this.predecessors = new ArrayList<>();
  }

  /**
   * Returns this block's label if its first instruction is a {@code LABEL},
   * otherwise {@code null}.
   *
   * @return the label name, or {@code null} if this block has no leading label
   */
  public int getId() {
    return id;
  }

  /**
   * Returns this block's label if its first instruction is a {@code LABEL},
   * otherwise {@code null}.
   *
   * @return the label name, or {@code null} if this block has no leading label
   */
  public String getLabel() {
    if (!instructions.isEmpty() && instructions.get(0).opCode == IRInstruction.OpCode.LABEL) {
      return ((IRLabelOperand) instructions.get(0).operands[0]).getName();
    }
    return null;
  }
}
