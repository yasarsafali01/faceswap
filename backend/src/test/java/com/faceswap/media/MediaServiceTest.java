package com.faceswap.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaServiceTest {

    private static byte[] ftyp(String brand) {
        return ("\0\0\0 ftyp" + brand).getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void isomBrandIsMp4() {
        assertEquals("video/mp4", MediaService.refineIsoBmff("video/quicktime", ftyp("isom")));
    }

    @Test
    void qtBrandStaysQuicktime() {
        assertEquals("video/quicktime", MediaService.refineIsoBmff("video/mp4", ftyp("qt  ")));
    }

    @Test
    void m4vBrand() {
        assertEquals("video/x-m4v", MediaService.refineIsoBmff("video/quicktime", ftyp("M4V ")));
    }

    @Test
    void nonIsoTypesAreUntouched() {
        assertEquals("video/webm", MediaService.refineIsoBmff("video/webm", ftyp("isom")));
    }
}
