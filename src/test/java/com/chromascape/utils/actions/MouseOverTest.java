package com.chromascape.utils.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;

/** Reads hover text from frames captured from a live client at the Grand Exchange. */
class MouseOverTest {

  private static BufferedImage load(String name) throws IOException {
    try (InputStream in = MouseOverTest.class.getResourceAsStream("/ocr/" + name)) {
      BufferedImage img = ImageIO.read(in);
      BufferedImage bgr =
          new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
      Graphics2D g = bgr.createGraphics();
      g.drawImage(img, 0, 0, null);
      g.dispose();
      return bgr;
    }
  }

  @Test
  void readsTextOverPlainBackground() throws IOException {
    assertEquals("Inventory", MouseOver.getText(load("hover_inventory.png")));
    assertEquals("Walkhere", MouseOver.getText(load("hover_walk_here.png")));
  }

  @Test
  void readsTextOverPlayer() throws IOException {
    // The second "Cadantine" crosses a player's arm and leg: skin coloured like the text, and
    // letter pixels tinted by it. These used to read "Cadtine" (the failure behind "Cln").
    assertEquals("UseCadantine->Cadantine", MouseOver.getText(load("hover_use_cadantine.png")));
    assertEquals(
        "UseCadantine->Cadantine", MouseOver.getText(load("hover_cadantine_over_arm.png")));
  }

  @Test
  void readsTextWithNoiseTouchingLetters() throws IOException {
    // Over a player in pink: skin pixels touch the "w" of "water", and the text is tinted
    assertEquals(
        "UseLantadyme->Vialofwater", MouseOver.getText(load("hover_use_on_vial_over_player.png")));
  }

  @Test
  void readsNothingWithoutHoverText() throws IOException {
    assertEquals("", MouseOver.getText(load("hover_none.png")));
  }

  @Test
  void ignoresStrayPixelsNearLetters() throws IOException {
    BufferedImage img = load("hover_inventory.png");
    Mat mask = MouseOver.textMask(img);
    boolean[][] nearText = new boolean[img.getHeight()][img.getWidth()];
    try (UByteIndexer ix = mask.createIndexer()) {
      for (int y = 0; y < img.getHeight(); y++) {
        for (int x = 0; x < img.getWidth(); x++) {
          if (ix.get(y, x) == 255) {
            // Letter pixels, their ring and their shadow are off limits
            for (int dy = -2; dy <= 2; dy++) {
              for (int dx = -2; dx <= 2; dx++) {
                int ny = y + dy;
                int nx = x + dx;
                if (ny >= 0 && nx >= 0 && ny < img.getHeight() && nx < img.getWidth()) {
                  nearText[ny][nx] = true;
                }
              }
            }
          }
        }
      }
    }
    mask.release();

    // Scatter single text-coloured pixels into the letters' blank padding (the zone around the
    // word), which the strict glyph match alone cannot tolerate
    int textColour = 0xCCCCCC;
    Random rng = new Random(7);
    for (int trial = 0; trial < 20; trial++) {
      BufferedImage noisy = load("hover_inventory.png");
      int placed = 0;
      while (placed < 6) {
        int x = 5 + rng.nextInt(85);
        int y = 4 + rng.nextInt(18);
        if (!nearText[y][x]) {
          noisy.setRGB(x, y, textColour);
          placed++;
        }
      }
      assertEquals("Inventory", MouseOver.getText(noisy), "trial " + trial);
    }
  }
}
