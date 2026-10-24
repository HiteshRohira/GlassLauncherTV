// Host test for the audio FLUSH path (review item 18): after requestFlush()
// the consumer drops the old tail and re-primes, and the producer re-anchors,
// so audio from the new position plays without the stale buffered audio.
#include <cstdio>
#include <vector>
#include "timeline_buffer.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } } while (0)

static const int RATE = 44100, CH = 2, FRAMES = 352;  // one ALAC-sized packet

static void writePacket(TimelineBuffer &t, int16_t value, int64_t ptsNs) {
    std::vector<int16_t> pcm(FRAMES * CH, value);
    t.write(pcm.data(), pcm.size(), ptsNs);
}

// reads n frames, returns how many samples equal value
static size_t readCount(TimelineBuffer &t, int frames, int16_t value) {
    std::vector<int16_t> out(frames * CH);
    t.read(out.data(), frames);
    size_t n = 0;
    for (int16_t s : out) if (s == value) n++;
    return n;
}

int main() {
    TimelineBuffer t(RATE, CH, /*staticCushionMs=*/20, 95);
    const int64_t pktNs = (int64_t) FRAMES * NS_PER_SEC / RATE;
    int64_t pts = NS_PER_SEC;
    for (int i = 0; i < 20; i++) { writePacket(t, 111, pts); pts += pktNs; }
    // play a little of the old position
    CHECK(readCount(t, 256, 111) > 0, "old audio should play before the flush");

    t.requestFlush();
    // the consumer must not play any more of the old position
    CHECK(readCount(t, 4096, 111) == 0, "stale audio played after FLUSH");

    // new position, far away on the sender's timeline (a seek)
    int64_t seekPts = 60 * NS_PER_SEC;
    for (int i = 0; i < 20; i++) { writePacket(t, 222, seekPts); seekPts += pktNs; }
    size_t fresh = 0;
    for (int i = 0; i < 8; i++) fresh += readCount(t, 256, 222);
    CHECK(fresh > 0, "audio from the new position did not play after FLUSH");

    // a flush with nothing buffered is harmless
    t.requestFlush();
    std::vector<int16_t> out(256 * CH, 7);
    t.read(out.data(), 256);
    CHECK(out[0] == 0, "empty flush should output silence");

    if (failures) { fprintf(stderr, "test_timeline_flush: %d failure(s)\n", failures); return 1; }
    printf("test_timeline_flush: ok\n");
    return 0;
}
