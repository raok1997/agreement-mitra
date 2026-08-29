package in.agreementmitra.signing.stamp;

import in.agreementmitra.InvalidUploadException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Validates the staff-uploaded certificate scan as <b>untrusted input</b>, fail-closed, before a
 * single byte is written to object storage or any state changes.
 *
 * <p>Three checks, in order:
 *
 * <ol>
 *   <li><b>Byte ceiling.</b> Bounds the raw upload.
 *   <li><b>Magic bytes.</b> Only JPEG and PNG. The declared content type and the filename are
 *       attacker-controlled and are never consulted - the file's own leading bytes decide.
 *   <li><b>Decoded pixel bounds.</b> Read from the image <em>header</em> via an {@link
 *       ImageReader}, so nothing is rasterised to learn them. This is the check the byte ceiling
 *       cannot make: a few-kilobyte PNG can legally declare a 60000x60000 raster that would need
 *       tens of gigabytes of heap to decode. A minimum edge is enforced too, because a 4x4 "scan"
 *       composites happily into a worthless legal instrument.
 * </ol>
 *
 * <p>Every failure raises {@link InvalidUploadException} (mapped to a constant 400 that never
 * echoes the submitted content) - never an unmapped server error and never an {@code
 * OutOfMemoryError}.
 */
@Component
public class CertificateScanValidator {

  /** Raw upload ceiling. Comfortably above a 300-dpi A4 scan, far below anything alarming. */
  static final int MAX_BYTES = 8 * 1024 * 1024;

  /** Decoded-raster ceiling: total pixels, and the longest single edge. */
  static final long MAX_PIXELS = 40_000_000L;

  static final int MAX_EDGE = 12_000;

  /** Below this an edge is not a usable scan of a legal certificate. */
  static final int MIN_EDGE = 200;

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

  private static final byte[] PNG_MAGIC = {
    (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
  };

  static final String CONTENT_TYPE_JPEG = "image/jpeg";

  static final String CONTENT_TYPE_PNG = "image/png";

  /**
   * Validate {@code scan} and return the content type its magic bytes prove it to be (never the
   * declared one).
   *
   * @throws InvalidUploadException if the scan is empty, oversize, not a JPEG/PNG, undecodable, or
   *     declares a raster outside the pixel bounds
   */
  public String validate(byte[] scan) {
    if (scan == null || scan.length == 0) {
      throw new InvalidUploadException("certificate scan is empty");
    }
    if (scan.length > MAX_BYTES) {
      throw new InvalidUploadException("certificate scan exceeds the byte ceiling");
    }
    String contentType = contentTypeFromMagicBytes(scan);
    checkPixelBounds(scan);
    return contentType;
  }

  /** JPEG or PNG by leading bytes only; anything else (PDF, TIFF, a renamed archive) is refused. */
  private static String contentTypeFromMagicBytes(byte[] scan) {
    if (startsWith(scan, JPEG_MAGIC)) {
      return CONTENT_TYPE_JPEG;
    }
    if (startsWith(scan, PNG_MAGIC)) {
      return CONTENT_TYPE_PNG;
    }
    throw new InvalidUploadException("certificate scan is not a JPEG or PNG (magic-byte mismatch)");
  }

  private static boolean startsWith(byte[] bytes, byte[] magic) {
    if (bytes.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (bytes[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Read width/height from the image header without decoding pixels. A {@link
   * MemoryCacheImageInputStream} is used explicitly so ImageIO never spills a temp file to disk for
   * an untrusted upload.
   */
  private static void checkPixelBounds(byte[] scan) {
    try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(scan))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new InvalidUploadException("certificate scan has no decodable image header");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < MIN_EDGE || height < MIN_EDGE) {
          throw new InvalidUploadException("certificate scan is below the minimum usable size");
        }
        if (width > MAX_EDGE || height > MAX_EDGE || (long) width * (long) height > MAX_PIXELS) {
          throw new InvalidUploadException("certificate scan exceeds the decoded pixel ceiling");
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException e) {
      // A truncated / structurally broken header. Fail closed as a client error, never a 500.
      throw new InvalidUploadException("certificate scan header could not be read");
    }
  }
}
