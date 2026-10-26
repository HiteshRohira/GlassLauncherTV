/* Host test for the upstream ec20312 backport (patch 0018): "no-data" audio
 * packets keep their RTP sequence slot, so silence no longer leaves gaps that
 * trigger resend requests and hold up the next real audio. AES is stubbed. */
#include "raop_buffer.c"
#include "byteutils.c"
#include "logger.c"
#include "utils.c"

struct aes_ctx_s { int unused; };
static struct aes_ctx_s stub;
aes_ctx_t *aes_cbc_init(const uint8_t *key, const uint8_t *iv, aes_direction_t direction) { return &stub; }
void aes_cbc_reset(aes_ctx_t *ctx) { }
void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) { memcpy(out, in, len); }
void aes_cbc_destroy(aes_ctx_t *ctx) { }

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } } while (0)

static void packet(unsigned char *p, unsigned short seq, int nodata) {
    memset(p, 0, 48);
    p[2] = seq >> 8; p[3] = seq & 0xff;
    if (nodata) { p[12] = 0x00; p[13] = 0x68; p[14] = 0x34; p[15] = 0x00; }
    else memset(p + 12, 0xAB, 36);
}

static int resends;
static int on_resend(void *opaque, unsigned short seq, unsigned short count) { resends += count; return 0; }

static void log_cb(void *cls, int level, const char *msg) { if (level <= LOGGER_ERR) fprintf(stderr, "[uxplay] %.*s\n", (int) strcspn(msg, "\n"), msg); }

int main(void) {
    unsigned char key[16] = { 0 }, iv[16] = { 0 };
    logger_t *logger = logger_init();
    logger_set_callback(logger, log_cb, NULL);
    raop_buffer_t *b = raop_buffer_init(logger, key, iv);
    unsigned char p[48];
    /* audio, two silent slots, audio */
    packet(p, 100, 0); CHECK(raop_buffer_enqueue(b, p, 48, 1) == 1, "enqueue 100");
    packet(p, 101, 1); CHECK(raop_buffer_enqueue(b, p, 16, 1) == 1, "silent 101 must occupy its slot");
    packet(p, 102, 1); CHECK(raop_buffer_enqueue(b, p, 16, 1) == 1, "silent 102 must occupy its slot");
    packet(p, 103, 0); CHECK(raop_buffer_enqueue(b, p, 48, 1) == 1, "enqueue 103");
    raop_buffer_handle_resends(b, on_resend, NULL);
    CHECK(resends == 0, "silent packets caused %d resend request(s)", resends);

    unsigned int len; uint32_t ts; unsigned short seq;
    int audio = 0, silent = 0;
    void *payload;
    while ((payload = raop_buffer_dequeue(b, &len, &ts, &seq, 0))) {
        if (len == 4 && !memcmp(payload, "\x00\x68\x34\x00", 4)) silent++;
        else audio++;
        free(payload);
    }
    CHECK(audio == 2 && silent == 2, "dequeued %d audio / %d silent, want 2 / 2", audio, silent);
    raop_buffer_destroy(b);
    logger_destroy(logger);
    if (failures) { fprintf(stderr, "test_raop_buffer_nodata: %d failure(s)\n", failures); return 1; }
    printf("test_raop_buffer_nodata: ok\n");
    return 0;
}
