import ir.IRFunction;
import ir.IRInstruction;

import java.util.*;

public class CFGBuilder {

    /**
     * Builds a {@link CFG} for {@code function} by dividing its instructions
     * into {@link BasicBlock}s and creating successor/predecessor edges between them.
     *
     * @param function the IR function to build a CFG for
     * @return the constructed {@link CFG} with {@code blocks.get(0)} as the entry block
     */
    public CFG buildCFG(IRFunction function) {
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock current = new BasicBlock(0);
        int nextId = 1;

        // ── Pass 1: partition instructions into BasicBlocks ───────────────────
        for (IRInstruction inst : function.instructions) {
            if (inst.opCode == IRInstruction.OpCode.LABEL) {
                if (!current.instructions.isEmpty()) {
                    blocks.add(current);
                    current = new BasicBlock(nextId++);
                }
                current.instructions.add(inst);
            } else if (Helpers.TERMINATOR_OPCODES.contains(inst.opCode)) {
                current.instructions.add(inst);
                blocks.add(current);
                current = new BasicBlock(nextId++);
            } else {
                current.instructions.add(inst);
            }
        }

        // Flush any trailing non-terminated block
        if (!current.instructions.isEmpty()) {
            blocks.add(current);
        }

        // ── Build label → block map for edge wiring ───────────────────────────
        Map<String, BasicBlock> labelToBlock = new HashMap<>();
        for (BasicBlock block : blocks) {
            String lbl = block.getLabel();
            if (lbl != null) {
                labelToBlock.put(lbl, block);
            }
        }

        // ── Pass 2: wire successor / predecessor edges ────────────────────────
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            if (block.instructions.isEmpty()) continue;

            IRInstruction last = block.instructions.getLast();
            BasicBlock next = (i + 1 < blocks.size()) ? blocks.get(i + 1) : null;

            if (Helpers.BRANCH_OPCODES.contains(last.opCode)) {
                BasicBlock target = labelToBlock.get(Helpers.targetLabel(last));
                if (target != null) addEdge(block, target);
                if (next != null)   addEdge(block, next);
            } else if (last.opCode == IRInstruction.OpCode.GOTO) {
                BasicBlock target = labelToBlock.get(Helpers.targetLabel(last));
                if (target != null) addEdge(block, target);
            } else if (last.opCode == IRInstruction.OpCode.RETURN) {
            } else {
                if (next != null) addEdge(block, next);
            }
        }

        return new CFG(blocks, function);
    }

    private void addEdge(BasicBlock from, BasicBlock to) {
        from.successors.add(to);
        to.predecessors.add(from);
    }
}
