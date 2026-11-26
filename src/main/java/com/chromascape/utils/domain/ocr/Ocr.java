package com.chromascape.utils.domain.ocr;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_32S;
import static org.bytedeco.opencv.global.opencv_core.CV_8U;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.add;
import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_core.subtract;
import static org.bytedeco.opencv.global.opencv_imgproc.CC_STAT_AREA;
import static org.bytedeco.opencv.global.opencv_imgproc.CC_STAT_HEIGHT;
import static org.bytedeco.opencv.global.opencv_imgproc.CC_STAT_LEFT;
import static org.bytedeco.opencv.global.opencv_imgproc.CC_STAT_TOP;
import static org.bytedeco.opencv.global.opencv_imgproc.CC_STAT_WIDTH;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.FILLED;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV;
import static org.bytedeco.opencv.global.opencv_imgproc.TM_CCOEFF_NORMED;
import static org.bytedeco.opencv.global.opencv_imgproc.TM_CCORR;
import static org.bytedeco.opencv.global.opencv_imgproc.boundingRect;
import static org.bytedeco.opencv.global.opencv_imgproc.connectedComponentsWithStats;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.dilate;
import static org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement;
import static org.bytedeco.opencv.global.opencv_imgproc.matchTemplate;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;

import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.zones.MaskZones;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Provides Ocr (Optical Character Recognition) functionality using JavaCV/OpenCV. Allows for
 * font-based glyph matching in screen-captured images to extract text.
 */
public class Ocr {

  /** Stores successful character matches during Ocr extraction. */
  private static final List<CharMatch> matches = new ArrayList<>();

  /** Cached zero scalar to prevent CPU allocation fatigue. */
  private static final Scalar ZERO_SCALAR = new Scalar(0);

  /** Glyphs prepared for the lenient pass, per font. */
  private static final Map<String, Map<String, GlyphShape>> shapeCache = new HashMap<>();

  /** The lenient pass ignores glyphs with fewer lit pixels than this (punctuation). */
  private static final int LENIENT_MIN_PIXELS = 8;

  /** The lenient pass lets a glyph miss one pixel per this many lit pixels. */
  private static final int LENIENT_PIXELS_PER_MISS = 12;

  /** Leftover blobs smaller than this many pixels are noise, not part of a missed letter. */
  private static final int LENIENT_MIN_BLOB = 4;

  /** The lenient pass needs at least this share of a glyph's pixels to be confirmed text. */
  private static final int LENIENT_MIN_CONFIRMED_PERCENT = 75;

  /**
   * Mask value for a pixel that may or may not be text, for example a letter pixel tinted by the
   * background behind it. See {@link #extractTextFromMask(Mat, String, boolean)}.
   */
  public static final int UNSURE = 128;

  /** Cache for loaded fonts to prevent disk I/O on every OCR call. */
  private static final Map<String, Map<String, Mat>> fontCache = new HashMap<>();

  /**
   * Allowed characters for OCR to remove runtime overhead for unnecessary glyphs. Most common
   * characters found.
   */
  private static final String ALLOWED_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()[],&-:/*'_\"?<>";

  /**
   * Loads a font glyph set from disk, converts each glyph to grayscale, and stores in a map. Uses
   * an internal cache to avoid repeated disk I/O. Only allows whitelisted glyphs ( please add if
   * necessary).
   *
   * @param font Name of the font folder inside resources.
   * @return A map from character string to Mat (glyph image).
   */
  public static synchronized Map<String, Mat> loadFont(String font) {
    // computeIfAbsent allows for the calculation of a value if it doesn't exist in a Map
    return fontCache.computeIfAbsent(
        font,
        f -> {
          Map<String, Mat> fontMap = new HashMap<>();
          String basePath = "/fonts/" + f + "/";
          String indexPath = basePath + f + ".index";

          try (InputStream indexStream = Ocr.class.getResourceAsStream(indexPath)) {
            if (indexStream == null) {
              // Throw runtime unchecked exception to fail if fonts are downloaded incorrectly and
              // are unavailable
              throw new UncheckedIOException(new IOException("Font index not found: " + indexPath));
            }

            // Stream the index file and load the files listed into the fontMap
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream))) {
              String fontFileName;
              while ((fontFileName = reader.readLine()) != null) {
                processFontFile(basePath, fontFileName, fontMap);
              }
            }
          } catch (IOException e) {
            // It's necessary for the project files to exist, so fail fast
            throw new RuntimeException(
                "Failed to load font library "
                    + f
                    + " essential for runtime execution with error: "
                    + e);
          }
          return fontMap;
        });
  }

  /**
   * Private helper for loading a specified font bitmap into a font library. Mutates the given map,
   * does not return anything, intended to be called in a loop. Expects the bitmap to be named as
   * ascii codepoints and to be stored as resources. Loads each glyph as a greyscale mat with its
   * corresponding character in String form.
   *
   * @param path {@link String} path of the font bitmap inside resources
   * @param fileName the name of the file including type (e.g., 68.bmp)
   * @param map the map to mutate and add the name + Mat object to
   * @throws IOException In the case that a glyph fails to load
   */
  private static void processFontFile(String path, String fileName, Map<String, Mat> map)
      throws IOException {
    // Get the name ASCII codepoint from the filename
    String cleanName = fileName.replace(".bmp", "");
    int codePoint = Integer.parseInt(cleanName);
    String character = Character.toString(codePoint);

    if (!ALLOWED_CHARS.contains(character)) {
      return;
    }

    try (InputStream is = Ocr.class.getResourceAsStream(path + fileName)) {
      if (is == null) {
        // It's necessary for the project files to exist, so fail fast
        throw new FileNotFoundException("Font file not found: " + fileName);
      }

      // Add the character and Mat to the map
      Mat img = TemplateMatching.bufferedImageToMat(ImageIO.read(is));
      cvtColor(img, img, COLOR_BGR2GRAY);
      map.put(character, img);
    }
  }

  /**
   * Extracts a string of text from a screen region ({@link Rectangle} zone) by template-matching
   * glyphs from a font. Note: this will not include any spaces.
   *
   * @param zone Rectangle on screen to extract text from.
   * @param font Font name to use for glyph matching.
   * @param colour ColourObj specifying the color to isolate.
   * @param clean Whether to clear internal match storage after use.
   * @return The extracted text string from the zone.
   */
  public static String extractText(Rectangle zone, String font, ColourObj colour, boolean clean) {
    Map<String, Mat> fontMap = loadFont(font);
    matches.clear();
    BufferedImage zoneImage = ScreenManager.captureZone(zone);
    Mat zoneMat = ColourContours.extractColours(zoneImage, colour);
    return extraction(fontMap, zoneMat, null, font, clean);
  }

  /**
   * Extracts a string of text from a screen region by template-matching glyphs from a font. Note:
   * this will not include any spaces.
   *
   * @param mask Mat CU81 mask to extract text from: 255 for text and 0 for background. Pixels set
   *     to {@link #UNSURE} may or may not be text; they are ignored when deciding whether a letter
   *     is present.
   * @param font Font name to use for glyph matching.
   * @param clean Whether to clear internal match storage after use.
   * @return The extracted text string from the zone.
   */
  public static String extractTextFromMask(Mat mask, String font, boolean clean) {
    Map<String, Mat> fontMap = loadFont(font);
    matches.clear();
    Mat text = new Mat();
    threshold(mask, text, UNSURE, 255, THRESH_BINARY);
    Mat unsure = new Mat();
    try (Mat any = new Mat()) {
      threshold(mask, any, 0, 255, THRESH_BINARY);
      subtract(any, text, unsure);
    }
    try {
      return extraction(fontMap, text, unsure, font, clean);
    } finally {
      unsure.release();
    }
  }

  /**
   * Internal function to perform Template matched OCR. Iterates over a font map, zeroing out the
   * convolution as it goes.
   *
   * @param fontMap List of glyphs, string character & Mat bitmap.
   * @param zoneMat Mat image of the source being searched within, which this method releases.
   * @param unsure where text may or may not be (255), or {@code null} if nowhere.
   * @param font Type of font.
   * @param clean Delete matches?
   * @return String of extracted letters, no spaces.
   */
  private static String extraction(
      Map<String, Mat> fontMap, Mat zoneMat, Mat unsure, String font, boolean clean) {
    double threshold = 0.99;
    // Supports (CV_8UC1) binary greyscale.
    // Holds pointers and correlation as reusable memory allocation to avoid JNI overhead
    try (DoublePointer minVal = new DoublePointer(1);
        DoublePointer maxVal = new DoublePointer(1);
        Point minLoc = new Point();
        Point maxLoc = new Point();
        Mat correlation = new Mat()) {
      // Template match each glyph in the font to the zoneMat.
      for (String glyph : fontMap.keySet()) {
        // These are to store the glyph sizes outside of try with resources scope.
        int glyphImgRows;
        int glyphImgCols;

        // We are trimming the font images and template matching -
        // Based on the font type and how the image is stored.
        int ycropModifier = getCropModifierForFont(font);

        try (Rect roi =
                new Rect(
                    0,
                    ycropModifier,
                    fontMap.get(glyph).arrayWidth(),
                    fontMap.get(glyph).arrayHeight() - ycropModifier);
            Mat croppedGlyph = new Mat(fontMap.get(glyph), roi)) {
          // Match template with cropped glyph and store size outside try-with-resources.
          matchTemplate(zoneMat, croppedGlyph, correlation, TM_CCOEFF_NORMED);
          glyphImgRows = croppedGlyph.rows();
          glyphImgCols = croppedGlyph.cols();
        }
        // Call minMaxLoc repeatedly, zero out the area based on glyph size, save locations as
        // CharMatch objs.
        while (true) { // Loop breaks when threshold is not met.
          minMaxLoc(correlation, minVal, maxVal, minLoc, maxLoc, null);

          if (maxVal.get() < threshold) {
            break;
          }

          Rectangle matchLocation =
              new Rectangle(maxLoc.x(), maxLoc.y(), glyphImgCols, glyphImgRows);
          matches.add(
              new CharMatch(glyph, matchLocation.x, matchLocation.y, glyphImgCols, glyphImgRows));

          zeroOutRegion(correlation, matchLocation);

          Mat oldZoneMat = zoneMat;
          zoneMat = MaskZones.maskZonesMat(zoneMat.clone(), matchLocation);
          oldZoneMat.release();
        }
      }
      // Letters the strict pass missed, usually because of a stray pixel in their blank padding
      lenientPass(fontMap, zoneMat, unsure, font);
    } finally {
      zoneMat.release();
    }

    // Sort CharMatch objects based on left-most positions.
    matches.sort(Comparator.comparingInt(CharMatch::y).thenComparingInt(CharMatch::x));

    StringBuilder result = new StringBuilder();
    for (CharMatch match : matches) {
      result.append(match.character());
    }

    if (clean) {
      matches.clear();
    }

    return result.toString();
  }

  /**
   * A second, more forgiving matching pass over whatever the strict pass left unmatched.
   *
   * <p>The strict pass correlates each glyph together with its blank padding, so one stray
   * background pixel anywhere in that padding (common for short letters such as "a" and "e", which
   * are mostly padding) hides the letter. Here a glyph is compared only on its own pixels plus a
   * 1px ring around them: the ring must not be text, which still tells "l" from "h" or "-" from
   * "e", but pixels further out are ignored. Unsure pixels count neither for nor against a glyph,
   * but at least {@value #LENIENT_MIN_CONFIRMED_PERCENT}% of its pixels must be confirmed text. A
   * glyph may also miss {@code 1} pixel per {@value #LENIENT_PIXELS_PER_MISS} of its lit pixels.
   *
   * <p>Candidates are accepted most confirmed pixels first and may not overlap a glyph already
   * found, so a fragment of a big letter is never read as a small one. Glyphs with fewer than
   * {@value #LENIENT_MIN_PIXELS} lit pixels (punctuation) are left to the strict pass, since they
   * are too easy to find in noise.
   *
   * @param fontMap the font's glyphs.
   * @param zoneMat the text mask, with letters found by the strict pass already blacked out.
   * @param unsure where text may or may not be (255), or {@code null} if nowhere.
   * @param font the font name.
   */
  private static void lenientPass(
      Map<String, Mat> fontMap, Mat fullZone, Mat fullUnsure, String font) {
    Map<String, GlyphShape> shapes = glyphShapes(font, fontMap);
    int maxW = 0;
    int maxH = 0;
    for (GlyphShape shape : shapes.values()) {
      maxW = Math.max(maxW, shape.on().cols());
      maxH = Math.max(maxH, shape.on().rows());
    }
    Rect area = leftoverArea(fullZone, maxW, maxH);
    if (area == null) {
      return;
    }
    int offsetX = area.x();
    int offsetY = area.y();
    List<Candidate> candidates = new ArrayList<>();
    try (Mat zoneMat = new Mat(fullZone, area);
        Mat unsure = fullUnsure == null ? null : new Mat(fullUnsure, area);
        Mat text = new Mat();
        Mat notBackground = new Mat();
        Mat background = new Mat();
        Mat missOn = new Mat();
        Mat missRing = new Mat();
        Mat miss = new Mat();
        Mat hits = new Mat();
        Mat tooFew = new Mat();
        Mat tooFew8 = new Mat();
        Scalar never = new Scalar(Float.MAX_VALUE);
        DoublePointer minVal = new DoublePointer(1);
        DoublePointer maxVal = new DoublePointer(1);
        Point minLoc = new Point();
        Point maxLoc = new Point()) {
      // 0/1 maps: confirmed text, and confirmed background (neither text nor unsure)
      zoneMat.convertTo(text, CV_32F, 1 / 255.0, 0);
      if (unsure == null) {
        text.copyTo(notBackground);
      } else {
        try (Mat u = new Mat()) {
          unsure.convertTo(u, CV_32F, 1 / 255.0, 0);
          add(text, u, notBackground);
        }
      }
      notBackground.convertTo(background, CV_32F, -1.0, 1.0);

      for (Map.Entry<String, GlyphShape> entry : shapes.entrySet()) {
        GlyphShape shape = entry.getValue();
        if (shape.lit() < LENIENT_MIN_PIXELS
            || shape.on().cols() > zoneMat.cols()
            || shape.on().rows() > zoneMat.rows()) {
          continue;
        }
        // Misses: glyph pixels on background, plus ring pixels on text. Hits: glyph pixels on text.
        matchTemplate(background, shape.on(), missOn, TM_CCORR);
        matchTemplate(text, shape.ring(), missRing, TM_CCORR);
        add(missOn, missRing, miss);
        matchTemplate(text, shape.on(), hits, TM_CCORR);
        double needed = Math.ceil(shape.lit() * LENIENT_MIN_CONFIRMED_PERCENT / 100.0);
        threshold(hits, tooFew, needed - 0.5, 1, THRESH_BINARY_INV);
        tooFew.convertTo(tooFew8, CV_8U);
        miss.setTo(new Mat(never), tooFew8);

        double allowed = shape.lit() / LENIENT_PIXELS_PER_MISS + 0.5;
        try (FloatIndexer missIx = miss.createIndexer();
            FloatIndexer hitIx = hits.createIndexer()) {
          while (true) {
            minMaxLoc(miss, minVal, maxVal, minLoc, maxLoc, null);
            if (minVal.get() > allowed) {
              break;
            }
            int x = minLoc.x();
            int y = minLoc.y();
            candidates.add(
                new Candidate(
                    entry.getKey(),
                    x + offsetX,
                    y + offsetY,
                    shape,
                    Math.round(hitIx.get(y, x)),
                    (int) Math.round(minVal.get())));
            missIx.put(y, x, Float.MAX_VALUE);
          }
        }
      }
    } finally {
      area.close();
    }
    if (candidates.isEmpty()) {
      return;
    }

    // Space already taken by letters, as the tight boxes around their lit pixels
    List<Rectangle> taken = new ArrayList<>();
    for (CharMatch m : matches) {
      GlyphShape shape = shapes.get(m.character());
      if (shape != null) {
        taken.add(shape.litBoxAt(m.x(), m.y()));
      }
    }
    candidates.sort(
        Comparator.comparingInt((Candidate c) -> -c.hits())
            .thenComparingInt(c -> c.shape().lit() - c.hits())
            .thenComparingInt(Candidate::misses)
            .thenComparingInt(Candidate::x));
    for (Candidate c : candidates) {
      Rectangle box = c.shape().litBoxAt(c.x(), c.y());
      if (taken.stream().anyMatch(box::intersects)) {
        continue;
      }
      taken.add(box);
      matches.add(
          new CharMatch(c.glyph(), c.x(), c.y(), c.shape().on().cols(), c.shape().on().rows()));
    }
  }

  /**
   * Returns the part of the mask worth searching again: the box around every leftover blob big
   * enough to be part of a letter, padded by a glyph's size. Lone specks of noise are ignored, so a
   * clean read usually skips the lenient pass entirely.
   *
   * @return the area, or {@code null} if nothing is left that could be a letter.
   */
  private static Rect leftoverArea(Mat zoneMat, int padX, int padY) {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = -1;
    int maxY = -1;
    try (Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat()) {
      int count = connectedComponentsWithStats(zoneMat, labels, stats, centroids, 8, CV_32S);
      try (IntIndexer st = stats.createIndexer()) {
        for (int i = 1; i < count; i++) { // 0 is the background
          if (st.get(i, CC_STAT_AREA) < LENIENT_MIN_BLOB) {
            continue;
          }
          int x = st.get(i, CC_STAT_LEFT);
          int y = st.get(i, CC_STAT_TOP);
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x + st.get(i, CC_STAT_WIDTH));
          maxY = Math.max(maxY, y + st.get(i, CC_STAT_HEIGHT));
        }
      }
    }
    if (maxX < 0) {
      return null;
    }
    int x0 = Math.max(0, minX - padX);
    int y0 = Math.max(0, minY - padY);
    int x1 = Math.min(zoneMat.cols(), maxX + padX);
    int y1 = Math.min(zoneMat.rows(), maxY + padY);
    return new Rect(x0, y0, x1 - x0, y1 - y0);
  }

  /** Returns the binarised, cropped glyphs of a font with their match masks, cached per font. */
  private static synchronized Map<String, GlyphShape> glyphShapes(
      String font, Map<String, Mat> fontMap) {
    return shapeCache.computeIfAbsent(
        font,
        f -> {
          Map<String, GlyphShape> shapes = new HashMap<>();
          int ycrop = getCropModifierForFont(f);
          try (Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3))) {
            for (Map.Entry<String, Mat> entry : fontMap.entrySet()) {
              Mat src = entry.getValue();
              if (src.rows() <= ycrop) {
                continue;
              }
              Mat glyph = new Mat();
              try (Rect roi = new Rect(0, ycrop, src.cols(), src.rows() - ycrop);
                  Mat cropped = new Mat(src, roi)) {
                threshold(cropped, glyph, 127, 255, THRESH_BINARY);
              }
              int lit = countNonZero(glyph);
              if (lit == 0) {
                glyph.release();
                continue;
              }
              Rectangle litBox;
              try (Rect r = boundingRect(glyph)) {
                litBox = new Rectangle(r.x(), r.y(), r.width(), r.height());
              }
              Mat on = new Mat();
              Mat ring = new Mat();
              try (Mat grown = new Mat();
                  Mat ring8 = new Mat()) {
                dilate(glyph, grown, kernel);
                subtract(grown, glyph, ring8);
                glyph.convertTo(on, CV_32F, 1 / 255.0, 0);
                ring8.convertTo(ring, CV_32F, 1 / 255.0, 0);
              }
              glyph.release();
              shapes.put(entry.getKey(), new GlyphShape(on, ring, lit, litBox));
            }
          }
          return shapes;
        });
  }

  /**
   * A glyph prepared for the lenient pass.
   *
   * @param on the cropped glyph's lit pixels, as a 0/1 float image.
   * @param ring the 1px ring around the lit pixels, as a 0/1 float image.
   * @param lit how many lit pixels the glyph has.
   * @param litBox the tight box around the lit pixels, relative to the glyph's top-left.
   */
  private record GlyphShape(Mat on, Mat ring, int lit, Rectangle litBox) {
    Rectangle litBoxAt(int x, int y) {
      return new Rectangle(x + litBox.x, y + litBox.y, litBox.width, litBox.height);
    }
  }

  /** A possible letter found by the lenient pass. */
  private record Candidate(String glyph, int x, int y, GlyphShape shape, int hits, int misses) {}

  /**
   * Returns a BufferedImage mask representing matched glyph positions within a screen region. This
   * is useful for clicking text. You are intended to extract contours from this and use it as a
   * ChromaObj.
   *
   * @param zone Rectangle on screen to perform Ocr in.
   * @param font Font name to use for glyph matching.
   * @param text Expected string result; skips mask generation if mismatched.
   * @param colour ColourObj specifying the color to isolate.
   * @return A BufferedImage mask of the matched character zones, or null if text doesn't match.
   */
  public static BufferedImage extractTextLocationMask(
      Rectangle zone, String font, String text, ColourObj colour) {
    // Get the full window bounds (this must match the screen capture bounds)
    Rectangle window = ScreenManager.getWindowBounds();

    // Create a black mask matching the window size
    Mat fullScreenMask = new Mat(window.height, window.width, CV_8UC1, new Scalar(0));

    // Early exit: text doesn't match expected
    if (!extractText(zone, font, colour, false).equals(text)) {
      return null;
    }

    // Create a zone-sized mask where matched characters will be drawn
    Mat zoneMask = new Mat(zone.height, zone.width, CV_8UC1, new Scalar(0));

    // Draw rectangles for matched characters
    for (CharMatch match : matches) {
      rectangle(
          zoneMask,
          new Point(match.x(), match.y()),
          new Point(match.x() + match.width(), match.y() + match.height()),
          new Scalar(255),
          FILLED,
          LINE_8,
          0);
    }

    // Convert screen-relative zone to window-relative position
    Mat roiMat = getMat(zone, window, fullScreenMask);
    zoneMask.copyTo(roiMat);

    // Release temporary mats
    zoneMask.release();
    roiMat.release();

    return TemplateMatching.matToBufferedImage(fullScreenMask);
  }

  /**
   * Converts a zone-relative rectangle to a window-relative Mat region for masking.
   *
   * @param zone Ocr region.
   * @param window Full window bounds from capture.
   * @param fullScreenMask The full-screen output mask.
   * @return A Mat region of interest inside the full screen mask.
   * @throws IllegalArgumentException if the zone is outside the screen bounds.
   */
  private static Mat getMat(Rectangle zone, Rectangle window, Mat fullScreenMask) {
    int relX = zone.x - window.x;
    int relY = zone.y - window.y;

    // Validate bounds to avoid OpenCV crash
    if (relX < 0
        || relY < 0
        || relX + zone.width > window.width
        || relY + zone.height > window.height) {
      throw new IllegalArgumentException(
          "Zone is outside the window bounds: zone=" + zone + ", window=" + window);
    }

    // Create region of interest in the full mask and copy the zone mask into it
    Rect roi = new Rect(relX, relY, zone.width, zone.height);
    return new Mat(fullScreenMask, roi);
  }

  /**
   * Sets all values in a rectangular region of a correlation matrix to zero. This prevents repeated
   * template matches in the same area.
   *
   * @param correlation The template match result matrix.
   * @param match The rectangle area to zero out.
   */
  public static void zeroOutRegion(Mat correlation, Rectangle match) {
    // Make sure the rectangle is within bounds of the correlation Mat
    int x = Math.max(match.x, 0);
    int y = Math.max(match.y, 0);
    int width = Math.min(match.width, correlation.cols() - x);
    int height = Math.min(match.height, correlation.rows() - y);

    if (width <= 0 || height <= 0) {
      return;
    }

    Rect roi = new Rect(x, y, width, height);
    Mat subMat = new Mat(correlation, roi);
    // Set all pixels in this region to 0 (lowest confidence)
    subMat.setTo(new Mat(ZERO_SCALAR));
    subMat.release();
  }

  /**
   * Returns a vertical crop offset used when slicing glyph images, depending on font type.
   *
   * @param font Font name.
   * @return Crop offset in pixels.
   */
  private static int getCropModifierForFont(String font) {
    return Objects.equals(font, "Plain 12") ? 2 : 1;
  }
}
