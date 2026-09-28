package com.faceswap.storage;

import com.faceswap.common.ApiException;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams a stored object to the client with single-range HTTP Range support,
 * so browsers can seek in videos without downloading the whole file.
 */
@Component
public class MediaStreamer {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final StorageService storage;

    public MediaStreamer(StorageService storage) {
        this.storage = storage;
    }

    public record ByteRange(long start, long end) {
        long length() {
            return end - start + 1;
        }
    }

    public void stream(String key, String downloadName, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        StatObjectResponse stat;
        try {
            stat = storage.stat(key);
        } catch (ErrorResponseException e) {
            throw ApiException.notFound("Dosya");
        }
        long size = stat.size();
        ByteRange range = parseRange(request.getHeader(HttpHeaders.RANGE), size);

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        response.setContentType(stat.contentType());
        if (downloadName != null) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"");
        }

        if (range == null) {
            range = new ByteRange(0, size - 1);
            response.setStatus(HttpStatus.OK.value());
        } else {
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.start() + "-" + range.end() + "/" + size);
        }
        response.setContentLengthLong(range.length());

        if (size == 0) {
            return;
        }
        try (InputStream in = storage.get(key, range.start(), range.length());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /** Returns null when no (or an unsupported multi-) range is requested; throws 416 when unsatisfiable. */
    static ByteRange parseRange(String header, long size) {
        if (header == null || header.contains(",")) {
            return null;
        }
        Matcher m = RANGE.matcher(header.trim());
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            return null;
        }
        long start;
        long end;
        if (m.group(1).isEmpty()) {
            // Suffix range: last N bytes.
            long suffix = Long.parseLong(m.group(2));
            start = Math.max(0, size - suffix);
            end = size - 1;
        } else {
            start = Long.parseLong(m.group(1));
            end = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
        }
        if (start >= size || start > end) {
            throw new ApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Geçersiz aralık");
        }
        return new ByteRange(start, end);
    }
}
