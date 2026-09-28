package com.faceswap.storage;

import com.faceswap.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaStreamerTest {

    @Test
    void noHeaderMeansFullBody() {
        assertNull(MediaStreamer.parseRange(null, 100));
    }

    @Test
    void openEndedRange() {
        assertEquals(new MediaStreamer.ByteRange(10, 99), MediaStreamer.parseRange("bytes=10-", 100));
    }

    @Test
    void endIsClampedToSize() {
        assertEquals(new MediaStreamer.ByteRange(0, 99), MediaStreamer.parseRange("bytes=0-5000", 100));
    }

    @Test
    void suffixRange() {
        assertEquals(new MediaStreamer.ByteRange(80, 99), MediaStreamer.parseRange("bytes=-20", 100));
    }

    @Test
    void multiRangeFallsBackToFullBody() {
        assertNull(MediaStreamer.parseRange("bytes=0-1,5-6", 100));
    }

    @Test
    void startBeyondSizeIsUnsatisfiable() {
        assertThrows(ApiException.class, () -> MediaStreamer.parseRange("bytes=200-", 100));
    }
}
