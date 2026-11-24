package com.chromascape.controller;

import com.chromascape.utils.core.input.keyboard.VirtualKeyboardUtils;
import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.chromascape.utils.core.input.remoteinput.RemoteInput;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import com.chromascape.utils.domain.walker.Walker;
import com.chromascape.utils.domain.zones.ZoneManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The central controller managing the lifecycle and access to core stateful utilities for input
 * simulation, screen capture, zone management, and hotkey listening.
 *
 * <p>Responsible for initializing and shutting down resources and enforcing runtime state checks to
 * prevent access to utilities when inactive.
 *
 * <p>This class abstracts and coordinates lower-level modules required for automation scripts.
 */
public class Controller {

  /** Represents the current running state of the controller. */
  private enum ControllerState {
    STOPPED,
    RUNNING
  }

  private ControllerState state;

  private RemoteInput remoteInput;
  private VirtualMouseUtils virtualMouseUtils;
  private VirtualKeyboardUtils virtualKeyboardUtils;
  private ZoneManager zoneManager;
  private Walker walker;
  private static final Logger logger = LogManager.getLogger(Controller.class);

  /**
   * System property that overrides the detected client UI scale, e.g. {@code
   * -Dchromascape.uiScale=2}.
   */
  private static final String UI_SCALE_PROPERTY = "chromascape.uiScale";

  /**
   * Determines the integer factor captured frames must be reduced by. Uses the {@value
   * #UI_SCALE_PROPERTY} system property if set, otherwise the scale the client was launched with.
   * Fractional scales cannot be reversed exactly, so they fall back to {@code 1} with a warning.
   *
   * @param processManager The OS process manager used to inspect the client
   * @param pid The client's process ID
   * @return The integer UI scale, at least {@code 1}
   */
  private static int resolveUiScale(ProcessManager processManager, int pid) {
    String override = System.getProperty(UI_SCALE_PROPERTY);
    double scale;
    if (override != null) {
      try {
        scale = Double.parseDouble(override);
      } catch (NumberFormatException e) {
        logger.warn("Ignoring invalid {}={}", UI_SCALE_PROPERTY, override);
        scale = processManager.getUiScale(pid);
      }
    } else {
      scale = processManager.getUiScale(pid);
    }

    long rounded = Math.round(scale);
    if (rounded < 1 || Math.abs(scale - rounded) > 1e-6) {
      logger.warn(
          "Client UI scale {} is not a whole number; captures will not match input coordinates."
              + " Set the RuneLite launcher scale to a whole number such as 1, 2 or 3.",
          scale);
      return 1;
    }
    if (rounded > 1) {
      logger.info("Client UI scale is {}x; captures will be reduced to logical size", rounded);
    }
    return (int) rounded;
  }

  /** Constructs a new Controller instance. */
  public Controller() {
    this.state = ControllerState.STOPPED;
  }

  /**
   * Initializes and starts the controller, setting up all core utilities needed for the bot to
   * operate, including input devices, hotkey listener, screen capture, and zone management.
   *
   * <p>This method queries the target client window, configures input hooks, and prepares the
   * internal state for running.
   */
  public void init() {
    logger.info("Setting up Font masks...");
    Ocr.loadFont("Plain 11");
    Ocr.loadFont("Plain 12");
    Ocr.loadFont("Bold 12");

    logger.info("Setting up Remote Input Library...");
    // Obtain process ID of the target window to initialize input injection
    ProcessManager processManager = ProcessManagerFactory.getProcessManager();
    int pid = processManager.getPid();
    remoteInput = new RemoteInput(pid, resolveUiScale(processManager, pid));
    // Give screen manager access to Remote Input to grab screen buffer
    ScreenManager.setRemoteInput(remoteInput);

    // Initialize virtual input utilities with current window bounds and fullscreen status
    logger.info("Initialising mouse and keyboard utils...");
    virtualMouseUtils = new VirtualMouseUtils(remoteInput);
    virtualKeyboardUtils = new VirtualKeyboardUtils(remoteInput);

    logger.info("Pre-loading and instantiating zones...");
    // Initialize zone management with fixed mode option
    zoneManager = new ZoneManager();
    // Initialise gameView instead of LazyLoading, to improve startup overhead
    zoneManager.getGameView();

    state = ControllerState.RUNNING;

    // Initialises a walker to provide the script with Walking functionality through the DAX API
    walker = new Walker(this);
    logger.info("Controller State: {}", state);
  }

  /**
   * Shuts down the controller and releases all resources.
   *
   * <p>This stops input injection, stops hotkey listening, and prevents further access to stateful
   * utilities until re-initialized.
   */
  public void shutdown() {
    remoteInput.close();
    state = ControllerState.STOPPED;
    logger.info("Shutting down");
  }

  /**
   * Provides access to the virtual mouse utility.
   *
   * @return The virtual mouse utility for simulated mouse actions.
   * @throws IllegalStateException if called while the controller is not running.
   */
  public VirtualMouseUtils mouse() {
    assertRunning("VirtualMouseUtils");
    return virtualMouseUtils;
  }

  /**
   * Provides access to the virtual keyboard utility.
   *
   * @return The virtual keyboard utility for simulated keyboard actions.
   * @throws IllegalStateException if called while the controller is not running.
   */
  public VirtualKeyboardUtils keyboard() {
    assertRunning("VirtualKeyboardUtils");
    return virtualKeyboardUtils;
  }

  /**
   * Provides access to the zone manager utility.
   *
   * <p>The ZoneManager maintains mappings of UI sub-zones to support interaction with different
   * client interface areas.
   *
   * @return The ZoneManager instance.
   * @throws IllegalStateException if called while the controller is not running.
   */
  public ZoneManager zones() {
    assertRunning("ZoneManager");
    return zoneManager;
  }

  /**
   * Provides access to the walker domain utility.
   *
   * @return The walker utility, to be able to pathfind in-game.
   */
  public Walker walker() {
    assertRunning("Walker");
    return walker;
  }

  /**
   * Checks that the controller is currently running before allowing access to any stateful utility,
   * logging and throwing an exception if not.
   *
   * @param component The name of the utility being accessed.
   * @throws IllegalStateException if the controller is not running.
   */
  private void assertRunning(String component) {
    if (state != ControllerState.RUNNING) {
      if (logger != null) {
        logger.info("{} accessed while bot is not running.", component);
      }
      throw new IllegalStateException(component + " accessed while bot is not running.");
    }
  }
}
