package in.agreementmitra.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Synthetic certificate-scan fixtures for tests, and the local development flow's stand-in for a
 * real SHCIL certificate scan.
 *
 * <p>Removing the synthetic stamp adapter removed the only stamping path that needed no human, so a
 * fixture image is what keeps the developer experience whole: {@link #certificateScan()} produces a
 * plausible A4-ish raster that passes the intake validator and composites cleanly. Nothing here
 * resembles a real certificate, and every certificate number used with it is fabricated - no SHCIL
 * artefact, credential, or endpoint is involved anywhere in this repository.
 */
public final class TestImages {

  /** Default fixture size, comfortably above the validator's minimum edge. */
  private static final int DEFAULT_WIDTH = 1240;

  private static final int DEFAULT_HEIGHT = 1754;

  private TestImages() {}

  /** A valid PNG "certificate scan" at a realistic A4-at-150dpi size. */
  public static byte[] certificateScan() {
    return png(DEFAULT_WIDTH, DEFAULT_HEIGHT);
  }

  /** A valid PNG of the given pixel dimensions. */
  public static byte[] png(int width, int height) {
    return encode(render(width, height), "png");
  }

  /** A valid JPEG of the given pixel dimensions. */
  public static byte[] jpeg(int width, int height) {
    return encode(render(width, height), "jpg");
  }

  /**
   * A PNG whose header is intact but whose pixel data is cut off - decodable enough to read the
   * dimensions from, undecodable as an image. Exercises the fail-closed composition path.
   */
  public static byte[] truncatedPng(int width, int height) {
    byte[] full = png(width, height);
    return Arrays.copyOf(full, Math.max(64, full.length / 3));
  }

  /**
   * A <b>decompression bomb</b>: a few dozen bytes of PNG whose IHDR declares an enormous raster.
   * Nothing is compressed here - the point is that the declared dimensions alone would demand
   * gigabytes of heap if anything tried to decode them, which is exactly why the validator reads
   * the header instead of the pixels. Structure is signature + IHDR + empty IDAT + IEND, the
   * minimum an ImageIO reader needs to report dimensions.
   */
  public static byte[] bombPng(int width, int height) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      out.write(
          new byte[] {
            (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
          });
      byte[] ihdr = new byte[13];
      writeInt(ihdr, 0, width);
      writeInt(ihdr, 4, height);
      ihdr[8] = 8; // bit depth
      ihdr[9] = 2; // colour type: truecolour
      chunk(out, "IHDR", ihdr);
      chunk(out, "IDAT", new byte[0]);
      chunk(out, "IEND", new byte[0]);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void chunk(ByteArrayOutputStream out, String type, byte[] data)
      throws IOException {
    byte[] length = new byte[4];
    writeInt(length, 0, data.length);
    out.write(length);
    byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
    out.write(typeBytes);
    out.write(data);
    CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    byte[] crcBytes = new byte[4];
    writeInt(crcBytes, 0, (int) crc.getValue());
    out.write(crcBytes);
  }

  private static void writeInt(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  private static BufferedImage render(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, width, height);
      g.setColor(Color.DARK_GRAY);
      g.drawRect(8, 8, Math.max(1, width - 16), Math.max(1, height - 16));
      g.drawString("SYNTHETIC FIXTURE - NOT A CERTIFICATE", 24, Math.min(40, height - 4));
    } finally {
      g.dispose();
    }
    return image;
  }

  private static byte[] encode(BufferedImage image, String format) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(image, format, out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
