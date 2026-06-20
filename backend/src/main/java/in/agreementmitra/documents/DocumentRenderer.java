package in.agreementmitra.documents;

/** Renders a populated template to a PDF byte array. */
public interface DocumentRenderer {

  /**
   * @param templateId which agreement template to use
   * @param data values to merge into the template
   * @return rendered PDF bytes
   */
  byte[] renderPdf(String templateId, java.util.Map<String, Object> data);
}
