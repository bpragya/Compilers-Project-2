# Compilers - Backend
Pragya Bhadani, Ian Wilson, Prajval Manivannan

A back end for the Tiger-IR language. It reads a Tiger-IR program and emits MIPS assembly that runs on the [SPIM](https://spimsimulator.sourceforge.net/) simulator. Two register allocation strategies are supported:

- **Naive** (`--naive`): every virtual register lives on the stack. Values are loaded before each instruction and stored right after.
- **Greedy intra-block** (`--greedy`): allocates physical registers within each basic block and spills when it runs out.

## Requirements

- JDK 14 or newer (`javac`, `java`)
- Bash (Git Bash on Windows)
- [SPIM](https://spimsimulator.sourceforge.net/) on your `PATH`, to run the generated assembly
- Maven, only for `clean.sh` and the unit tests

## Usage

```bash
./run.sh <input.ir> <output.s> [--naive|--greedy]
```

This script builds the project, compiles the IR file to MIPS, and runs the result with `spim -keepstats`, using [exceptions.s](exceptions.s) as the exception handler.

Example:

```bash
./run.sh test_cases/prime.ir out.s --greedy
```

Other scripts:

| Script | Purpose |
| --- | --- |
| `build.sh` | Compiles all Java sources into `build/` |
| `run_config.sh <in.ir> <out.s> <flag>` | Builds, then runs the compiler only (no SPIM) |
| `run_ir_interpreter.sh <in.ir>` | Builds, then runs the reference Tiger-IR interpreter |
| `clean.sh` | Removes `.class` files and runs `mvn clean` |

## Project layout

Sources are in `materials/mips-interpreter/src/main/java`:

| File | Role |
| --- | --- |
| `Main.java` | Compiler driver: parses IR, selects instructions, allocates registers, writes `.s` |
| `InstructionSelection.java` | Translates each IR instruction into MIPS instructions that still use virtual registers |
| `NaiveAllocator.java` | Naive stack-based register allocation |
| `IntrablockAllocator.java` | Greedy intra-block register allocation |
| `Intrinsics.java` | Emits the runtime routines for the IR library calls |
| `CFG.java`, `CFGBuilder.java`, `BasicBlock.java` | Control-flow graph construction |
| `ReachingDefinitions.java`, `DeadCodeElimination.java`, `IROptimizer.java` | Dataflow analysis and IR-level optimization |
| `IRInterpreter.java` | Reference interpreter for Tiger-IR |
| `ir/`, `mips/` | IR and MIPS data structures, readers and printers; includes a MIPS interpreter |

## Tests

- `test_cases/` has hand-written IR programs (arithmetic, branches, arrays, I/O, `prime`, `quicksort`).
- `materials/public_test_cases/` has the provided cases (`array_clobber`, `prime`, `quicksort`). Each has `.in` and `.out` files for input and expected output.
- `materials/mips-interpreter/tests/` has sample MIPS programs.
- Unit tests are in `materials/mips-interpreter/src/test/java`.

To check a program, compare SPIM's output with the matching `.out` file:

```bash
./run.sh materials/public_test_cases/prime/prime.ir out.s --greedy < materials/public_test_cases/prime/0.in
```

## Docs

- [project-3.pdf](project-3.pdf): assignment spec for the back end
- [CS4240_Project_2.pdf](CS4240_Project_2.pdf): spec for the earlier IR optimizer project
- [design.pdf](design.pdf): design write-up for the earlier IR optimizer (CFG, reaching definitions, dead code elimination), not the back end
