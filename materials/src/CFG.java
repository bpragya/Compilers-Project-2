import ir.IRFunction;
import java.util.List;

public class CFG {
  private List<BasicBlock> blocks;
  IRFunction fn;

  public CFG(List<BasicBlock> blocks, IRFunction fn) {
    this.blocks = blocks;
    this.fn = fn;
  }

  /**
   * Returns the entry block of this CFG.
   *
   * <p>The entry block is always {@code blocks.get(0)} and corresponds to the
   * first instruction of the function. It has no predecessor edges.
   *
   * @return the entry {@link BasicBlock}
   */
  public BasicBlock entry() {
    return blocks.getFirst();
  }

  /**
   * Returns the list of BasicBlocks for this CFG
   *
   * @return the list of BasicBlocks in this CFG
   * */
  public List<BasicBlock> getBlocks() {
    return blocks;
  }
}
