package org.project.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stubbed OCR — no vendor is called.
 * - .txt upload: the file content is the OCR text.
 * - image/PDF upload: treated as an already-known fixture; the text comes from
 *   {fixtures-dir}/{base filename}.txt (e.g. receipt-clean.png -> receipt-clean.txt).
 */
@Component
public class StubOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(StubOcrEngine.class);

    private final Path fixturesDir;

    public StubOcrEngine(@Value("${app.fixtures-dir}") String fixturesDir) {
        this.fixturesDir = Paths.get(fixturesDir).toAbsolutePath().normalize();
    }

    @Override
    public String extractText(String originalFilename, byte[] content) {
        if (originalFilename.toLowerCase().endsWith(".txt")) {
            return new String(content, StandardCharsets.UTF_8);
        }
        String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
        Path fixture = fixturesDir.resolve(baseName + ".txt").normalize();
        if (!fixture.startsWith(fixturesDir) || !Files.isRegularFile(fixture)) {
            log.warn("Stub OCR has no fixture for this image/PDF; returning empty text");
            return "";
        }
        try {
            return new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
