package org.project.ocr;

/**
 * Turns an uploaded receipt file into raw text (Strategy).
 * Swap the implementation to use a real vendor; nothing downstream changes.
 */
public interface OcrEngine {

    /** Returns the OCR text, or "" when nothing can be read. */
    String extractText(String originalFilename, byte[] content);
}
