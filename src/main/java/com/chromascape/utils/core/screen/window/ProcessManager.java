package com.chromascape.utils.core.screen.window;

/**
 * An interface to be implemented by Windows, Mac and Linux implementors. The sole responsibility of
 * each implementor is to provide an OS native method to return the Process ID of RuneLite.
 */
public interface ProcessManager {
  /**
   * To provide an OS native way of grabbing and returning the Process ID of RuneLite. This is to be
   * used by RemoteInput.
   *
   * @return An integer Process ID
   */
  int getPid();

  /**
   * Returns the Java2D UI scale the target was launched with ({@code -Dsun.java2d.uiScale}). Java
   * draws the client canvas at this multiple of its logical size, so RemoteInput captures frames
   * that are this many times larger than the coordinates input events use.
   *
   * <p>Implementors that cannot inspect the target's launch arguments return {@code 1.0}.
   *
   * @param pid The process ID of the target
   * @return The UI scale, or {@code 1.0} if unknown
   */
  default double getUiScale(int pid) {
    return 1.0;
  }
}
