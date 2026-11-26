package com.chromascape.utils.actions;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * An actions utility to provide a high level API for MouseOverText. Allows the user to get the text
 * of the MouseOverText zone regardless of colour.
 *
 * <p>Allows the user to grab the MouseOverText as a string, excluding spaces.
 */
public class MouseOver {

  // A text shadow pixel has no channel brighter than this. The client blends the hover text a
  // little, so shadows read as near-black (up to ~20 over a light floor) rather than pure black.
  private static final int SHADOW_MAX = 50;

  // A text pixel is far brighter than its shadow, and text colours are never dull
  private static final int TEXT_MIN_CHANNEL = 120;

  // A text colour must sit above a shadow at least this many times. Each letter gives several, but
  // a background colour that happens to touch something dark rarely does.
  private static final int MIN_SHADOW_HITS = 3;

  // Lower -> stricter tolerance on similar colours
  private static final int COLOUR_TOLERANCE = 20;

  // Looser tolerance for pixels touching text, which OCR treats as unsure: text tinted by the
  // background behind it
  private static final int UNSURE_TOLERANCE = 40;

  private static final ColourObj ORANGE =
      new ColourObj("Orange", new Scalar(16, 224, 255, 0), new Scalar(16, 224, 255, 0));

  private static final Mat OLIVE = new Mat(1, 1, CV_8UC3, new Scalar(17, 73, 73, 0));

  /**
   * Extracts text from the MouseOverText zone. Does not include spaces.
   *
   * @param baseScript {@code this} the script that the user is running.
   * @return a String of all text found.
   */
  public static String getText(BaseScript baseScript) {
    // Get image of MouseOverText
    Rectangle zone = baseScript.controller().zones().getMouseOver();
    return getText(ScreenManager.captureZone(zone));
  }

  /**
   * Extracts text from an image of the MouseOverText zone, such as a saved frame. Does not include
   * spaces.
   *
   * @param capture an image of the MouseOverText zone.
   * @return a String of all text found.
   */
  public static String getText(BufferedImage capture) {
    Mat mask = textMask(capture);
    try {
      return Ocr.extractTextFromMask(mask, "Bold 12", true);
    } finally {
      mask.release();
    }
  }

  /**
   * Builds the text mask that is read by OCR: 255 where text is, {@link Ocr#UNSURE} where it may
   * be, 0 elsewhere.
   *
   * @param capture an image of the MouseOverText zone.
   * @return a CV_8UC1 mask, which the caller must release.
   */
  static Mat textMask(BufferedImage capture) {
    // BGR image of the zone
    Mat image = TemplateMatching.bufferedImageToMat(capture);
    try {
      // Replace orange to olive to exclude interface colours near zone
      Mat orangeMask = ColourContours.extractColours(image, ORANGE);
      image.setTo(OLIVE, orangeMask);
      orangeMask.release();
      // Extract possible character colours based on shadow
      Object[] uniqueTextColours = extractTextPalette(image);
      // Set non character pixels to black
      return maskText(uniqueTextColours, image);
    } finally {
      image.release();
    }
  }

  /**
   * Compares each pixel in an image against a palette of unique colours that represent text colours
   * in an image. Creates a mask with white pixels where text should be and the background black.
   *
   * <p>The client draws hover text slightly blended with what is behind it, so letter pixels over a
   * bright or colourful background (a player's arm) drift from the palette colour. Pixels within
   * {@link #COLOUR_TOLERANCE} are text (255). Pixels within {@link #UNSURE_TOLERANCE} that touch
   * text may be tinted letter pixels or background touching a letter, so they are marked {@link
   * Ocr#UNSURE} for OCR to ignore.
   *
   * @param colours a list of unique colours not within the colour threshold of each other.
   * @param image the image containing text to mask.
   * @return a CV_8UC1 mask: 255 for text, {@link Ocr#UNSURE} for unsure, 0 for background.
   */
  private static Mat maskText(Object[] colours, Mat image) {
    int rows = image.rows();
    int cols = image.cols();
    // 2 = text, 1 = near-text colour, 0 = background
    byte[] level = new byte[rows * cols];
    UByteIndexer img = image.createIndexer();
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int b = img.get(y, x, 0) & 0xFF;
        int g = img.get(y, x, 1) & 0xFF;
        int r = img.get(y, x, 2) & 0xFF;
        int packed = (r << 16) | (g << 8) | b;
        int best = Integer.MAX_VALUE;
        for (Object colour : colours) {
          best = Math.min(best, distanceSquared((Integer) colour, packed));
        }
        if (best <= COLOUR_TOLERANCE * COLOUR_TOLERANCE) {
          level[y * cols + x] = 2;
        } else if (best <= UNSURE_TOLERANCE * UNSURE_TOLERANCE) {
          level[y * cols + x] = 1;
        }
      }
    }
    img.release();

    // Near-text pixels touching text may be tinted letter pixels, or background touching a letter
    for (int i = 0; i < level.length; i++) {
      if (level[i] == 1 && !touchesText(level, i, rows, cols)) {
        level[i] = 0;
      }
    }

    Mat mask = new Mat(rows, cols, CV_8UC1, new Scalar(0));
    UByteIndexer out = mask.createIndexer();
    for (int i = 0; i < level.length; i++) {
      if (level[i] != 0) {
        out.put(i / cols, i % cols, level[i] == 2 ? 255 : Ocr.UNSURE);
      }
    }
    out.release();
    return mask;
  }

  private static boolean touchesText(byte[] level, int i, int rows, int cols) {
    int y = i / cols;
    int x = i % cols;
    for (int ny = Math.max(0, y - 1); ny <= Math.min(rows - 1, y + 1); ny++) {
      for (int nx = Math.max(0, x - 1); nx <= Math.min(cols - 1, x + 1); nx++) {
        if (level[ny * cols + nx] == 2) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Compares the Euclidean distance between the RGB values of two pixels against a static tolerance
   * value to gauge if they are similar enough.
   *
   * @param colour1 one of the colours to compare.
   * @param colour2 one of the colours to compare.
   * @return whether the colours are similar enough to consider part of the same text colour.
   */
  private static boolean isInThreshold(int colour1, int colour2) {
    return distanceSquared(colour1, colour2) <= COLOUR_TOLERANCE * COLOUR_TOLERANCE;
  }

  /** Squared Euclidean distance between two packed RGB colours. */
  private static int distanceSquared(int colour1, int colour2) {
    // Unpack first colour
    int r = (colour1 >> 16) & 0xFF;
    int g = (colour1 >> 8) & 0xFF;
    int b = colour1 & 0xFF;
    // Unpack second
    int r2 = (colour2 >> 16) & 0xFF;
    int g2 = (colour2 >> 8) & 0xFF;
    int b2 = colour2 & 0xFF;
    // Calculate difference
    int dr = r - r2;
    int dg = g - g2;
    int db = b - b2;
    // Calculate distance^2
    return dr * dr + dg * dg + db * db;
  }

  private static boolean isShadow(int r, int g, int b) {
    return r <= SHADOW_MAX && g <= SHADOW_MAX && b <= SHADOW_MAX;
  }

  /**
   * Finds the colours of the text. Hover text has a 1px shadow down and to the right of every
   * letter pixel, so a colour counts as text when its pixels are bright and often sit just above
   * and left of a near-black shadow. Colours within {@link #COLOUR_TOLERANCE} of each other are
   * grouped, and a group needs {@link #MIN_SHADOW_HITS} such pixels. This keeps background colours
   * (a player's skin, the floor) out of the palette even when they happen to touch something dark.
   *
   * @param image the BGR input image which may contain text.
   * @return packed RGB integers, one per text colour group.
   */
  private static Object[] extractTextPalette(Mat image) {
    int width = image.cols();
    int height = image.rows();
    UByteIndexer indexer = image.createIndexer();

    Map<Integer, Integer> hits = new HashMap<>();
    for (int y = 1; y < height; y++) {
      for (int x = 1; x < width; x++) {
        int blue = indexer.get(y, x, 0) & 0xFF;
        int green = indexer.get(y, x, 1) & 0xFF;
        int red = indexer.get(y, x, 2) & 0xFF;
        if (!isShadow(red, green, blue)) {
          continue;
        }
        int b = indexer.get(y - 1, x - 1, 0) & 0xFF;
        int g = indexer.get(y - 1, x - 1, 1) & 0xFF;
        int r = indexer.get(y - 1, x - 1, 2) & 0xFF;
        if (Math.max(r, Math.max(g, b)) < TEXT_MIN_CHANNEL) {
          continue;
        }
        hits.merge((r << 16) | (g << 8) | b, 1, Integer::sum);
      }
    }
    indexer.release();

    // Group similar colours, most frequent first so each group is centred on a real text colour
    List<Map.Entry<Integer, Integer>> byCount = new ArrayList<>(hits.entrySet());
    byCount.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
    List<int[]> groups = new ArrayList<>(); // {colour, hits}
    for (Map.Entry<Integer, Integer> e : byCount) {
      int[] home = null;
      for (int[] group : groups) {
        if (isInThreshold(group[0], e.getKey())) {
          home = group;
          break;
        }
      }
      if (home == null) {
        groups.add(new int[] {e.getKey(), e.getValue()});
      } else {
        home[1] += e.getValue();
      }
    }

    List<Integer> palette = new ArrayList<>();
    for (int[] group : groups) {
      if (group[1] >= MIN_SHADOW_HITS) {
        palette.add(group[0]);
      }
    }
    return palette.toArray();
  }
}
