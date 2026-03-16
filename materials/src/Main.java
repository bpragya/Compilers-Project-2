import ir.IRFunction;
import ir.IRInstruction;
import ir.IRProgram;
import ir.IRReader;

import java.util.Set;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Main <path-to-ir-file>");
            return;
        }

        IRProgram program = new IRReader().parseIRFile(args[0]);
        CFGBuilder builder = new CFGBuilder();

        for (IRFunction fn : program.functions) {
            System.out.println("=== Function: " + fn.name + " ===");
            CFG cfg = builder.buildCFG(fn);

            printCFG(cfg);

            ReachingDefinitions rd = new ReachingDefinitions(cfg);
            rd.run(cfg);
            printReachingDefinitions(cfg, rd);
        }
    }

    private static void printCFG(CFG cfg) {
        System.out.println("  -- CFG --");
        for (BasicBlock block : cfg.getBlocks()) {
            System.out.println("  Block " + block.getId() + ":");

            for (IRInstruction inst : block.instructions) {
                System.out.println("    [line " + inst.irLineNumber + "] "
                        + inst.opCode + " " + operandsToString(inst));
            }

            StringBuilder succs = new StringBuilder("    -> successors:   [");
            for (BasicBlock s : block.successors) succs.append(s.getId()).append(" ");
            System.out.println(succs.toString().stripTrailing() + "]");

            StringBuilder preds = new StringBuilder("    <- predecessors: [");
            for (BasicBlock p : block.predecessors) preds.append(p.getId()).append(" ");
            System.out.println(preds.toString().stripTrailing() + "]");

            System.out.println();
        }
    }

    private static void printReachingDefinitions(CFG cfg, ReachingDefinitions rd) {
        System.out.println("  -- Reaching Definitions --");
        for (BasicBlock block : cfg.getBlocks()) {
            System.out.println("  Block " + block.getId() + ":");
            printDefSet("IN ", rd.inSet.get(block));
            printDefSet("OUT", rd.outSet.get(block));
            System.out.println();
        }
    }

    private static void printDefSet(String label, Set<IRInstruction> defs) {
        StringBuilder sb = new StringBuilder("    " + label + ": [");
        for (IRInstruction d : defs) {
            sb.append("(line ").append(d.irLineNumber).append(" ")
              .append(d.opCode).append(" ").append(operandsToString(d)).append(") ");
        }
        System.out.println(sb.toString().stripTrailing() + "]");
    }

    private static String operandsToString(IRInstruction inst) {
        if (inst.operands == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inst.operands.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(inst.operands[i]);
        }
        return sb.toString();
    }
}
