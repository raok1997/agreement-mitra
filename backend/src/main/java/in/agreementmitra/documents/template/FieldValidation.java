package in.agreementmitra.documents.template;

/**
 * Declarative-only validation metadata for a field. {@code min}/{@code max} bound numeric ({@code
 * int}/{@code money}) values; {@code minLength}/{@code maxLength}/{@code pattern} bound text. All
 * parts are optional (null = unbounded). This is data, never an expression or executable code --
 * the model records the bounds; enforcing them against real values is a later projector's job.
 */
record FieldValidation(Long min, Long max, Integer minLength, Integer maxLength, String pattern) {}
