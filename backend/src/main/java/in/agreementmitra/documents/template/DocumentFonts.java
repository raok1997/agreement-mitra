package in.agreementmitra.documents.template;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code @font-face} CSS block that embeds the Noto faces as data-URIs, so the single
 * compiled HTML shapes Latin + Devanagari/Telugu (complex Indic scripts) the same way in a browser
 * live pane and in Gotenberg. Relocated here from the retired root-package {@code HtmlFontEmbedder}
 * (which only embedded on the HTML pane): the CSS is now injected into the {@link TemplateCompiler}
 * output so <b>one</b> compiled document is self-contained for the browser <b>and</b> identical for
 * both tiers -- the parity non-negotiable (design D-A). Package-private -- internal to the {@code
 * documents} module.
 *
 * <p>Faces are loaded from the classpath under {@code documents/fonts/} (the binary TTFs are an
 * ops/deploy drop-in, mirroring how the Gotenberg image installs {@code fonts-noto-core} rather
 * than committing font blobs to source; see {@code documents/fonts/README.txt}). A face that is not
 * present is simply skipped -- the render never fails for a missing font; it degrades to the
 * family-name reference for that script, which is the pre-existing behaviour. The loaded CSS is
 * built once at bean init and handed to the compiler; unit tests construct the compiler with no
 * faces (pure, no I/O).
 */
final class DocumentFonts {

  /** CSS family name -> classpath resource of the TTF face. Order preserved in the emitted CSS. */
  private static final Map<String, String> DEFAULT_FACES = defaultFaces();

  private DocumentFonts() {}

  private static Map<String, String> defaultFaces() {
    Map<String, String> faces = new LinkedHashMap<>();
    faces.put("Noto Sans", "documents/fonts/NotoSans.ttf");
    faces.put("Noto Sans Devanagari", "documents/fonts/NotoSansDevanagari.ttf");
    return faces;
  }

  /** The {@code @font-face} CSS for the default Noto faces present on the classpath. */
  static String faceCss() {
    return faceCss(DEFAULT_FACES);
  }

  /**
   * Build the {@code @font-face} CSS for {@code faces}, one rule per face present on the classpath.
   * Returns {@code ""} when no face is present (the compiler then emits only its family-name style,
   * unchanged).
   */
  static String faceCss(Map<String, String> faces) {
    StringBuilder css = new StringBuilder();
    for (Map.Entry<String, String> face : faces.entrySet()) {
      byte[] bytes = load(face.getValue());
      if (bytes != null && bytes.length > 0) {
        css.append(fontFaceRule(face.getKey(), bytes));
      }
    }
    return css.toString();
  }

  /** Build one {@code @font-face} rule embedding {@code bytes} as a base64 {@code font/ttf} URI. */
  static String fontFaceRule(String family, byte[] bytes) {
    String base64 = Base64.getEncoder().encodeToString(bytes);
    return "@font-face{font-family:'"
        + family
        + "';font-style:normal;font-weight:400;"
        + "src:url(data:font/ttf;base64,"
        + base64
        + ") format('truetype');}\n";
  }

  private static byte[] load(String resource) {
    try (InputStream in = DocumentFonts.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      return in.readAllBytes();
    } catch (IOException e) {
      // A font read failure must not break the render -- degrade to the family-name reference.
      return null;
    }
  }
}
