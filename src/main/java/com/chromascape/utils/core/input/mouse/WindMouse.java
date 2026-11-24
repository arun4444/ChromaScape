/**
 * Copyright 2006-2013 by Benjamin J. Land (a.k.a. BenLand100)
 *
 * <p>This file is part of the SMART Minimizing Autoing Resource Thing (SMART)
 *
 * <p>SMART is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * <p>SMART is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * <p>You should have received a copy of the GNU General Public License along with SMART. If not,
 * see <http://www.gnu.org/licenses/>.
 */
package com.chromascape.utils.core.input.mouse;

import static java.util.concurrent.locks.LockSupport.parkNanos;

import java.awt.Point;
import java.util.Random;
import java.util.function.Consumer;

/**
 *
 *
 * <blockquote>
 *
 * "The WindMouse algorithm is inspired by highschool physics that me-of-fifteen-years-ago was just
 * getting interested in. The cursor is modeled as an object with some inertia (mass) that is acted
 * on by two forces:
 *
 * <ul>
 *   <li>Gravity, which is constant in magnitude (a configurable parameter) and always points
 *       towards the final destination.
 *   <li>Wind, which exerts a random force in a random direction, and smoothly changes in both
 *       magnitude and direction over time." - BenLand100
 * </ul>
 *
 * </blockquote>
 *
 * <p>This modified impl has been tuned to 60hz as opposed to 30
 *
 * <p>Original Algorithm by <a href=
 * "https://ben.land/post/2021/04/25/windmouse-human-mouse-movement/">BenLand100</a>. Tweaked
 * "WindMouse2" implementation by <a href=
 * "https://dreambot.org/forums/index.php?/topic/21147-windmouse-custom-mouse-movement-algorithm/">holic</a>.
 * Adapted for ChromaScape.
 */
public class WindMouse {

  private final Random random = new Random();

  /**
   * Moves the mouse from a starting point to a destination using the WindMouse physics model.
   *
   * <p>This method selects a speed profile and delegates to the internal physics engine.
   *
   * @param start The current coordinates of the mouse cursor.
   * @param target The destination coordinates.
   * @param speedProfile A string constant determining movement characteristics ("slow", "medium",
   *     "fast"). Defaults to "medium" if the profile is unrecognized.
   * @param moveMouseImpl A {@link Consumer} that accepts a {@link Point} for every step of the
   *     path. This is typically used to trigger the actual hardware or robot input.
   */
  public void move(Point start, Point target, String speedProfile, Consumer<Point> moveMouseImpl) {
    double mouseSpeed = 30;
    double mouseGravity = 4.5;
    double mouseWind = 1.5;

    switch (speedProfile.toLowerCase()) {
      case "slow" -> {
        mouseSpeed = 20;
        mouseGravity = 5.0;
        mouseWind = 1.0;
      }
      case "fast" -> {
        mouseSpeed = 50;
        mouseGravity = 6.0;
        mouseWind = 2.0;
      }
      case "medium", "default" -> {
        // Keeps defaults
      }
    }

    // Vary each parameter per move so no two movements share identical physics.
    mouseSpeed *= jitter(0.12);
    mouseGravity *= jitter(0.12);
    mouseWind *= jitter(0.12);

    windMouse2(start, target, mouseGravity, mouseWind, mouseSpeed, moveMouseImpl);
  }

  /**
   * Returns a multiplicative jitter factor drawn from a normal distribution around 1.
   *
   * @param stdDev The relative standard deviation, e.g. {@code 0.12} for 12%
   * @return A factor clamped to [0.7, 1.3]
   */
  private double jitter(double stdDev) {
    return Math.max(0.7, Math.min(1.3, 1 + random.nextGaussian() * stdDev));
  }

  /**
   * Moves the mouse from the current position to the specified position. Approximates human
   * movement in a way where smoothness and accuracy are relative to speed, as it should be.
   *
   * <p>Algorithm by BenLand100, modified by holic and later ChromaScape.
   *
   * @param start The starting point.
   * @param target The final destination.
   * @param gravity The gravitational pull towards the target.
   * @param wind The magnitude of random perturbations.
   * @param speed The timing speed factor.
   * @param moveMouseImpl The callback for cursor updates.
   */
  private void windMouse2(
      Point start,
      Point target,
      double gravity,
      double wind,
      double speed,
      Consumer<Point> moveMouseImpl) {

    double totalDistance = distance(target, start);
    Point intermediate =
        (totalDistance > 250 && random.nextInt(2) == 1) ? pointNearPath(start, target) : null;

    if (intermediate != null) {
      windMouseImpl(
          start.x,
          start.y,
          intermediate.x,
          intermediate.y,
          gravity,
          wind,
          speed,
          random.nextInt(10, 25),
          moveMouseImpl);

      // Small pause between each movement
      sleepPrecise(random.nextInt(1, 150));
      start = intermediate; // Continue from intermediate
    }

    // Occasionally overshoot the target slightly and correct, as fast human movements do
    Point overshoot = shouldOvershoot(totalDistance, speed) ? overshootPoint(start, target) : null;
    if (overshoot != null) {
      windMouseImpl(
          start.x,
          start.y,
          overshoot.x,
          overshoot.y,
          gravity,
          wind,
          speed,
          random.nextInt(10, 25),
          moveMouseImpl);
      // Brief reaction time before the corrective movement
      sleepPrecise(random.nextInt(30, 120));
      start = overshoot;
      // The correction is a short, careful movement
      speed *= 0.6;
      wind *= 0.5;
    }

    // Move to final target
    windMouseImpl(
        start.x,
        start.y,
        target.x,
        target.y,
        gravity,
        wind,
        speed,
        random.nextInt(10, 25),
        moveMouseImpl);
  }

  /**
   * Decides whether a movement overshoots. Only longer movements overshoot, and faster ones do so
   * more often.
   *
   * @param totalDistance The straight-line distance of the whole movement
   * @param speed The movement speed parameter
   * @return {@code true} if this movement should overshoot
   */
  private boolean shouldOvershoot(double totalDistance, double speed) {
    if (totalDistance < 150) {
      return false;
    }
    double chance = speed >= 40 ? 0.25 : speed >= 25 ? 0.12 : 0.04;
    return random.nextDouble() < chance;
  }

  /**
   * Returns a point slightly past {@code target} along the direction of travel, with a little
   * sideways error: 3-8% of the remaining distance, capped at 12 pixels.
   *
   * @param from Where the final segment starts
   * @param target The intended destination
   * @return The overshoot point
   */
  private Point overshootPoint(Point from, Point target) {
    double dist = distance(from, target);
    if (dist < 1) {
      return target;
    }
    double ux = (target.x - from.x) / dist;
    double uy = (target.y - from.y) / dist;
    double extra = Math.min(12, dist * (0.03 + 0.05 * random.nextDouble()));
    double side = random.nextGaussian() * 3;
    return new Point(
        (int) Math.round(target.x + ux * extra - uy * side),
        (int) Math.round(target.y + uy * extra + ux * side));
  }

  /**
   * Returns a waypoint 55-85% of the way along the straight path, offset sideways by a normal
   * amount proportional to the distance. A hand's primary movement lands near the path rather than
   * anywhere in the bounding box of start and target.
   *
   * @param start The movement start
   * @param target The movement end
   * @return The waypoint
   */
  private Point pointNearPath(Point start, Point target) {
    double dist = distance(start, target);
    double t = 0.55 + 0.3 * random.nextDouble();
    double side = Math.max(-0.2, Math.min(0.2, random.nextGaussian() * 0.08)) * dist;
    double ux = (target.x - start.x) / dist;
    double uy = (target.y - start.y) / dist;
    return new Point(
        (int) Math.round(start.x + (target.x - start.x) * t - uy * side),
        (int) Math.round(start.y + (target.y - start.y) * t + ux * side));
  }

  /**
   * Internal mouse movement algorithm. Do not use this without credit to either Benjamin J. Land or
   * BenLand100. This is synchronized to prevent multiple motions and bannage.
   *
   * @param xs The x start
   * @param ys The y start
   * @param xe The x destination
   * @param ye The y destination
   * @param gravity Strength pulling the position towards the destination
   * @param wind Strength pulling the position in random directions
   * @param speed Influences the rate of sleeps, speeding up or slowing down the routine
   * @param targetArea Radius of area around the destination that should trigger slowing, prevents
   *     spiraling
   */
  private void windMouseImpl(
      double xs,
      double ys,
      double xe,
      double ye,
      double gravity,
      double wind,
      double speed,
      double targetArea,
      Consumer<Point> onMove) {

    double dist, veloX = 0, veloY = 0, windX = 0, windY = 0;

    double sqrt2 = Math.sqrt(2);
    double sqrt3 = Math.sqrt(3);
    double sqrt5 = Math.sqrt(5);

    int tDist = (int) distance(new Point((int) xs, (int) ys), new Point((int) xe, (int) ye));
    long t = System.currentTimeMillis() + 10000; // 10-second timeout safety

    while ((dist = Math.hypot((xs - xe), (ys - ye))) >= 3) {
      if (System.currentTimeMillis() > t) break;

      wind = Math.min(wind, dist);

      long d = (Math.round((Math.round(((double) (tDist))) * 0.3)) / 7);
      if (d > 20) d = 20;
      if (d < 5) d = 5;

      if (random.nextInt(6) == 0) {
        d = 2;
      }

      double maxStep = (Math.min(d, Math.round(dist))) * 1.5;

      if (dist >= targetArea) {
        // Apply normal wind
        int windRange = (int) (Math.round(wind) * 2) + 1;
        windX = (windX / sqrt3) + ((random.nextInt(windRange) - wind) / sqrt5);
        windY = (windY / sqrt3) + ((random.nextInt(windRange) - wind) / sqrt5);
      } else {

        windX = (windX / sqrt2);
        windY = (windY / sqrt2);

        veloX *= 0.64;
        veloY *= 0.64;
      }

      veloX += windX + gravity * (xe - xs) / dist;
      veloY += windY + gravity * (ye - ys) / dist;

      if (Math.hypot(veloX, veloY) > maxStep) {
        maxStep = ((maxStep / 2) < 1) ? 2 : maxStep;
        double randomDist = (maxStep / 2) + random.nextInt((int) (Math.round(maxStep) / 2));
        double veloMag = Math.sqrt(((veloX * veloX) + (veloY * veloY)));
        veloX = (veloX / veloMag) * randomDist;
        veloY = (veloY / veloMag) * randomDist;
      }

      int lastX = ((int) (Math.round(xs)));
      int lastY = ((int) (Math.round(ys)));
      xs += veloX;
      ys += veloY;

      if ((lastX != Math.round(xs)) || (lastY != Math.round(ys))) {
        Point newP = new Point((int) Math.round(xs), (int) Math.round(ys));
        if (onMove != null) onMove.accept(newP);
      }

      sleepPrecise(stepDelayMillis(speed));
    }

    if ((Math.round(xe) != Math.round(xs)) || (Math.round(ye) != Math.round(ys))) {
      Point finalP = new Point((int) Math.round(xe), (int) Math.round(ye));
      if (onMove != null) onMove.accept(finalP);
    }
  }

  /**
   * Precisely sleeps for a given length of time, as other approaches aren't as accurate.
   *
   * @param millis The duration to sleep in milliseconds.
   */
  private void sleepPrecise(long millis) {
    long end = System.nanoTime() + millis * 1_000_000L;
    long timeLeft = end - System.nanoTime();
    while (timeLeft > 2_000_000L) {
      parkNanos(timeLeft - 1_000_000L);
      timeLeft = end - System.nanoTime();
    }

    while (System.nanoTime() < end) {
      try {
        java.lang.Thread.onSpinWait();
      } catch (NoSuchMethodError e) {
        // Fallback for older JDKs (implicitly just busy-waits)
      }
    }
  }

  /**
   * Calculates distance between 2 points.
   *
   * @param p1 The first point.
   * @param p2 The second point.
   * @return The distance.
   */
  private double distance(Point p1, Point p2) {
    return Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
  }

  /**
   * Returns the pause between two path steps. The median scales inversely with speed, roughly
   * matching the old timing on average (~23ms slow, ~16ms medium, ~9ms fast), but is drawn from a
   * continuous log-normal distribution. The previous implementation could only produce a few
   * discrete values, which is an easily recognised timing signature.
   *
   * @param speed The movement speed parameter
   * @return The pause in milliseconds, clamped to [6, 70]
   */
  private long stepDelayMillis(double speed) {
    double median = 470.0 / speed;
    double sample = median * Math.exp(random.nextGaussian() * 0.3);
    return Math.round(Math.max(6, Math.min(70, sample)));
  }
}
