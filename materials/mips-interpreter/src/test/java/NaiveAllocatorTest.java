import ir.IRFunction;
import mips.MIPSInstruction;
import mips.MIPSOp;
import mips.operand.Addr;
import mips.operand.Register;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class NaiveAllocatorTest {

    private static IRFunction emptyFunc() {
        return new IRFunction("test", null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    @Test
    void emitWithSpill_emitsInstructionWithCorrectOrder() {
        // ADD %vr1, %vr2, %vr3  (all virtual registers)
        Register vr1 = new Register("%vr1", true);
        Register vr2 = new Register("%vr2", true);
        Register vr3 = new Register("%vr3", true);
        MIPSInstruction add = new MIPSInstruction(MIPSOp.ADD, null, vr1, vr2, vr3);

        Map<Integer, MIPSInstruction> progInsts = new LinkedHashMap<>();
        progInsts.put(0, add);

        NaiveAllocator alloc = new NaiveAllocator(new PrintStream(OutputStream.nullOutputStream()));
        alloc.allocate(emptyFunc(), progInsts);

        Map<Integer, MIPSInstruction> emitted = alloc.getInstructions();

        // 1. The ADD itself must be emitted (the core bug: it was previously dropped)
        int addIdx = -1;
        for (Map.Entry<Integer, MIPSInstruction> e : emitted.entrySet()) {
            if (e.getValue().op == MIPSOp.ADD) addIdx = e.getKey();
        }
        assertNotEquals(-1, addIdx, "ADD instruction was dropped and never emitted");

        // 2. The ADD's operands must all be physical $t registers (not virtual)
        MIPSInstruction addInst = emitted.get(addIdx);
        Register dest = (Register) addInst.operands.get(0);
        Register src1 = (Register) addInst.operands.get(1);
        Register src2 = (Register) addInst.operands.get(2);
        assertFalse(dest.isVirtual, "ADD destination must be a physical register");
        assertFalse(src1.isVirtual, "ADD src1 must be a physical register");
        assertFalse(src2.isVirtual, "ADD src2 must be a physical register");

        // 3. LWs loading the two read registers must come before the ADD
        int lw0Idx = -1, lw1Idx = -1;
        for (Map.Entry<Integer, MIPSInstruction> e : emitted.entrySet()) {
            MIPSInstruction inst = e.getValue();
            if (inst.op == MIPSOp.LW) {
                Register rd = (Register) inst.operands.get(0);
                if (rd.name.equals(src1.name)) lw0Idx = e.getKey();
                else if (rd.name.equals(src2.name)) lw1Idx = e.getKey();
            }
        }
        assertTrue(lw0Idx != -1 && lw0Idx < addIdx, "LW loading src1 must precede the ADD");
        assertTrue(lw1Idx != -1 && lw1Idx < addIdx, "LW loading src2 must precede the ADD");

        // 4. The SW storing the result must come after the ADD (was previously emitted before)
        int swResultIdx = -1;
        for (Map.Entry<Integer, MIPSInstruction> e : emitted.entrySet()) {
            MIPSInstruction inst = e.getValue();
            if (inst.op == MIPSOp.SW) {
                Register src = (Register) inst.operands.get(0);
                if (src.name.equals(dest.name)) swResultIdx = e.getKey();
            }
        }
        assertTrue(swResultIdx != -1 && swResultIdx > addIdx,
                "SW storing the ADD result must come after the ADD");
    }

    // -------------------------------------------------------------------------
    // Bug #3 — JR $ra from RETURN must be redirected through the epilogue
    // -------------------------------------------------------------------------

    /**
     * A single RETURN emits one JR $ra from InstructionSelection.
     * Without the fix that JR fires directly, returning before the epilogue
     * restores $sp/$fp, corrupting the caller's stack frame.
     *
     * With the fix: the body JR must be rewritten to J <func>_epilogue, the
     * epilogue label must be registered in the labels map, and the only JR in
     * the output is the epilogue's own return jump.
     */
    @Test
    void returnJr_isRedirectedThroughEpilogue_notEmittedInBody() {
        // JR $ra — exactly what InstructionSelection.emitReturn produces
        MIPSInstruction jr = new MIPSInstruction(MIPSOp.JR, null, new Register("$ra", false));

        Map<Integer, MIPSInstruction> progInsts = new LinkedHashMap<>();
        progInsts.put(0, jr);

        NaiveAllocator alloc = new NaiveAllocator(new PrintStream(OutputStream.nullOutputStream()));
        alloc.allocate(emptyFunc(), progInsts);

        Map<Integer, MIPSInstruction> emitted = alloc.getInstructions();
        Map<String, Integer> labels = alloc.getLabels();

        String epilogueLabel = "test_epilogue";

        // 1. The epilogue label must be registered so J can resolve it at runtime
        assertTrue(labels.containsKey(epilogueLabel),
                "Epilogue label '" + epilogueLabel + "' must be in the labels map");

        int epilogueStart = labels.get(epilogueLabel);

        // 2. No JR must appear before the epilogue — a premature JR bypasses
        //    the stack-restore sequence and corrupts the caller's frame
        for (Map.Entry<Integer, MIPSInstruction> e : emitted.entrySet()) {
            if (e.getKey() < epilogueStart) {
                assertNotEquals(MIPSOp.JR, e.getValue().op,
                        "JR at index " + e.getKey() + " appears before epilogue (index "
                        + epilogueStart + ") — this skips $sp/$fp restoration");
            }
        }

        // 3. A J instruction targeting the epilogue label must exist in the body;
        //    this is the rewritten RETURN that ensures the epilogue runs first
        boolean hasEpilogueJump = false;
        for (MIPSInstruction inst : emitted.values()) {
            if (inst.op == MIPSOp.J) {
                Addr target = (Addr) inst.operands.get(0);
                if (epilogueLabel.equals(target.label)) {
                    hasEpilogueJump = true;
                    break;
                }
            }
        }
        assertTrue(hasEpilogueJump,
                "No 'J " + epilogueLabel + "' found — RETURN was not redirected through the epilogue");

        // 4. The first instruction of the epilogue must restore $sp from $fp
        //    (MOVE $sp, $fp). Jumping past this is what causes the stack corruption.
        MIPSInstruction firstEpilogue = emitted.get(epilogueStart);
        assertEquals(MIPSOp.MOVE, firstEpilogue.op,
                "First epilogue instruction must be MOVE (restores $sp); got " + firstEpilogue.op);

        // 5. The very last emitted instruction must be the epilogue's JR — confirming
        //    the only path back to the caller goes through the full cleanup sequence
        int lastIdx = emitted.size() - 1;
        assertEquals(MIPSOp.JR, emitted.get(lastIdx).op,
                "Last emitted instruction must be JR $ra from the epilogue");
    }

    /**
     * A function with two separate RETURN points (e.g. an early-exit branch)
     * produces two JR $ra instructions in the unallocated program. Both must be
     * rewritten — leaving even one as a raw JR would bypass stack cleanup on that
     * exit path.
     */
    @Test
    void multipleReturnJrs_allRedirectedToEpilogue() {
        IRFunction func = new IRFunction("multiret", null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        Register ra = new Register("$ra", false);
        Map<Integer, MIPSInstruction> progInsts = new LinkedHashMap<>();
        progInsts.put(0, new MIPSInstruction(MIPSOp.JR, null, ra));
        progInsts.put(1, new MIPSInstruction(MIPSOp.JR, null, ra));

        NaiveAllocator alloc = new NaiveAllocator(new PrintStream(OutputStream.nullOutputStream()));
        alloc.allocate(func, progInsts);

        Map<Integer, MIPSInstruction> emitted = alloc.getInstructions();
        Map<String, Integer> labels = alloc.getLabels();

        String epilogueLabel = "multiret_epilogue";
        assertTrue(labels.containsKey(epilogueLabel),
                "Epilogue label must be registered for multi-return function");

        int epilogueStart = labels.get(epilogueLabel);

        // Count JR instructions before the epilogue — must be zero
        long bodyJrCount = emitted.entrySet().stream()
                .filter(e -> e.getKey() < epilogueStart)
                .filter(e -> e.getValue().op == MIPSOp.JR)
                .count();
        assertEquals(0, bodyJrCount,
                "Found " + bodyJrCount + " JR(s) before the epilogue; all RETURN exits must be "
                + "redirected to the epilogue regardless of how many return points exist");

        // Both original JRs must have been replaced with J epilogueLabel
        long epilogueJumpCount = emitted.values().stream()
                .filter(i -> i.op == MIPSOp.J)
                .filter(i -> epilogueLabel.equals(((Addr) i.operands.get(0)).label))
                .count();
        assertEquals(2, epilogueJumpCount,
                "Expected 2 'J " + epilogueLabel + "' redirects (one per RETURN), found " + epilogueJumpCount);
    }
}
