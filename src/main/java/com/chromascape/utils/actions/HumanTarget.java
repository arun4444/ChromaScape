package com.chromascape.utils.actions;

import java.awt.Point;
import java.awt.Rectangle;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Chooses click points and movement speeds the way a particular person tends to, rather than
 * sampling every click independently around the exact centre of a target.
 *
 * <p>Two effects are modelled on top of a centre-biased Gaussian:
 *
 * <ul>
 *   <li><b>Habits:</b> people consistently favour a slightly off-centre spot on a given button or
 *       object. Each target key gets a small offset, drawn once per instance (i.e. per session),
 *       that every click on that target is scattered around.
 *   <li><b>Approach undershoot:</b> aimed movements tend to land a little short, on the side the
 *       cursor is coming from.
 * </ul>
 *
 * <p>Create one instance per script run so habits stay stable within a session and differ between
 * sessions.
 */
public class HumanTarget {

  /** Relative standard deviation of each target's habit offset (fraction of its size). */
  private static final double HABIT_SPREAD = 0.12;

  /** Largest habit offset allowed, as a fraction of the target's size. */
  private static final double HABIT_LIMIT = 0.25;

  /** How far the aim point shifts towards the approach side, as a fraction of target size. */
  private static final double UNDERSHOOT = 0.07;

  /** Attempts to find an acceptable point before giving up. */
  private static final int MAX_ATTEMPTS = 60;

  private final Random random;
  private final Map<String, double[]> habits = new HashMap<>();

  /** Creates a targeter with a securely seeded random source and fresh habits. */
  public HumanTarget() {
    this(new Random(new SecureRandom().nextLong()));
  }

  /**
   * Creates a targeter with the given random source. Intended for tests.
   *
   * @param random The random source
   */
  public HumanTarget(Random random) {
    this.random = random;
  }

  /**
   * Picks a click point inside {@code rect}.
   *
   * @param key Identifies the target, e.g. {@code "depositButton"}. Clicks on the same key share
   *     a habit offset.
   * @param rect The clickable area in client coordinates
   * @param from The cursor's current position, or {@code null} if unknown
   * @return A point inside {@code rect}
   */
  public Point pick(String key, Rectangle rect, Point from) {
    Point p = pick(key, rect, from, point -> true);
    return p != null ? p : new Point((int) rect.getCenterX(), (int) rect.getCenterY());
  }

  /**
   * Picks a click point inside {@code rect} that also satisfies {@code accept}, for example being
   * inside an object's outline rather than just its bounding box.
   *
   * @param key Identifies the target; clicks on the same key share a habit offset
   * @param rect The bounding area in client coordinates
   * @param from The cursor's current position, or {@code null} if unknown
   * @param accept Extra condition a point must meet
   * @return A suitable point, or {@code null} if none was found
   */
  public Point pick(String key, Rectangle rect, Point from, Predicate<Point> accept) {
    double[] habit =
        habits.computeIfAbsent(key, k -> new double[] {habitOffset(), habitOffset()});

    double meanX = rect.getCenterX() + habit[0] * rect.width;
    double meanY = rect.getCenterY() + habit[1] * rect.height;

    if (from != null) {
      double dx = from.x - meanX;
      double dy = from.y - meanY;
      double dist = Math.hypot(dx, dy);
      if (dist > 1) {
        meanX += dx / dist * UNDERSHOOT * rect.width;
        meanY += dy / dist * UNDERSHOOT * rect.height;
      }
    }

    double sigmaX = rect.width / divisor(rect.width);
    double sigmaY = rect.height / divisor(rect.height);

    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      Point p =
          new Point(
              (int) Math.round(meanX + random.nextGaussian() * sigmaX),
              (int) Math.round(meanY + random.nextGaussian() * sigmaY));
      if (rect.contains(p) && accept.test(p)) {
        return p;
      }
    }
    Point centre = new Point((int) rect.getCenterX(), (int) rect.getCenterY());
    return accept.test(centre) ? centre : null;
  }

  /**
   * Chooses a movement speed profile for a move. Long moves are usually fast and short hops
   * usually slower, with some randomness either way.
   *
   * @param from The start of the move, or {@code null} if unknown
   * @param to The destination
   * @return {@code "slow"}, {@code "medium"} or {@code "fast"}
   */
  public String speedFor(Point from, Point to) {
    double dist = from == null ? 200 : from.distance(to);
    double r = random.nextDouble();
    if (dist > 220) {
      return r < 0.7 ? "fast" : "medium";
    }
    if (dist > 80) {
      return r < 0.15 ? "slow" : r < 0.85 ? "medium" : "fast";
    }
    return r < 0.6 ? "slow" : "medium";
  }

  private double habitOffset() {
    return Math.max(-HABIT_LIMIT, Math.min(HABIT_LIMIT, random.nextGaussian() * HABIT_SPREAD));
  }

  /**
   * Divisor for the standard deviation, so small targets get a relatively tighter spread and
   * fewer samples fall outside them. Mirrors {@code ClickDistribution}'s heuristic.
   */
  private static double divisor(int length) {
    if (length >= 50) {
      return 4.5;
    } else if (length >= 25) {
      return 6.5;
    }
    return 7.5;
  }
}
