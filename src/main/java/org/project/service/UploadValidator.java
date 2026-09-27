package org.project.service;

import org.project.error.ApiException;
import org.project.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks name, extension, declared content type, real content (magic bytes) and size.
 * Max size is also enforced by spring.servlet.multipart.max-file-size.
 */
@Component
public class UploadValidator {

    private static final Logger log = LoggerFactory.getLogger(UploadValidator.class);

    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,200}\\.[A-Za-z0-9]{1,5}$");
    private static final Map<String, Set<String>> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put("png", types("image/png"));
        CONTENT_TYPES.put("jpg", types("image/jpeg"));
        CONTENT_TYPES.put("jpeg", types("image/jpeg"));
        CONTENT_TYPES.put("pdf", types("application/pdf"));
        CONTENT_TYPES.put("txt", types("text/plain"));
    }
    private static final String GENERIC_TYPE = "application/octet-stream";

    /** Returns the lower-case extension of a validated file. */
    public String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw reject(ErrorCode.FILE_REQUIRED);
        }
        if (file.getSize() > MAX_BYTES) {
            throw reject(ErrorCode.FILE_TOO_LARGE);
        }
        String name = file.getOriginalFilename();
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw reject(ErrorCode.INVALID_FILE_NAME);
        }
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        Set<String> allowedTypes = CONTENT_TYPES.get(ext);
        if (allowedTypes == null) {
            throw reject(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        String contentType = declaredContentType(file);
        if (!allowedTypes.contains(contentType) && !GENERIC_TYPE.equals(contentType)) {
            throw reject(ErrorCode.FILE_CONTENT_MISMATCH);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw reject(ErrorCode.FILE_REQUIRED);
        }
        if (!contentMatches(ext, bytes)) {
            throw reject(ErrorCode.FILE_CONTENT_MISMATCH);
        }
        log.debug("Upload accepted: ext={}, bytes={}", ext, bytes.length);
        return ext;
    }

    /** The Content-Type the client sent, without parameters ("text/plain; charset=UTF-8" -> "text/plain"). */
    private static String declaredContentType(MultipartFile file) {
        String header = file.getContentType();
        if (header == null) {
            return GENERIC_TYPE;
        }
        String[] parts = header.split(";");
        return parts[0].trim();
    }

    private static boolean contentMatches(String ext, byte[] b) {
        switch (ext) {
            case "png":
                return startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg":
            case "jpeg":
                return startsWith(b, 0xFF, 0xD8, 0xFF);
            case "pdf":
                return startsWith(b, '%', 'P', 'D', 'F', '-');
            case "txt":
                return isUtf8Text(b);
            default:
                return false;
        }
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUtf8Text(byte[] b) {
        if (b.length == 0) {
            return false;
        }
        for (byte x : b) {
            if (x == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Set<String> types(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /** Logs only the error code — the user's filename is never written to logs. */
    private static ApiException reject(ErrorCode code) {
        log.info("Upload rejected: {}", code);
        return new ApiException(code);
    }
}
