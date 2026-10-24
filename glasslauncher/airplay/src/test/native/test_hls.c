/* Host tests for the HLS master-playlist rewrite (patch 0015). Run by run.sh
 * under ASan/UBSan. The original adjust_master_playlist returned an
 * uninitialized buffer when no URI used the sender's prefix. */
#include "airplay_video.c"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } } while (0)

static char *adjust(const char *in, const char *prefix, const char *local) {
    char *copy = strdup(in);
    char *out = adjust_master_playlist(copy, (int) strlen(copy), prefix, (char *) local);
    free(copy);
    return out;
}

static void test_adjust(void) {
    const char *plain = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/v1.m3u8\n";
    char *out = adjust(plain, "mlhls://localhost", "http://localhost:7000");
    CHECK(out && !strcmp(out, plain), "no-match case must return an exact copy, got [%s]", out ? out : "(null)");
    free(out);

    out = adjust("A mlhls://localhost/x.m3u8 B mlhls://localhost/y.m3u8", "mlhls://localhost", "http://localhost:7000");
    CHECK(out && !strcmp(out, "A http://localhost:7000/x.m3u8 B http://localhost:7000/y.m3u8"), "grow: [%s]", out);
    free(out);

    out = adjust("mlhls://localhost/x.m3u8\nmlhls://localhost/y.m3u8", "mlhls://localhost", "h:/");
    CHECK(out && !strcmp(out, "h://x.m3u8\nh://y.m3u8"), "shrink: [%s]", out);
    free(out);

    out = adjust("", "mlhls://localhost", "http://localhost:7000");
    CHECK(out && !strcmp(out, ""), "empty input");
    free(out);

    out = adjust("mlhls://localhostmlhls://localhost", "mlhls://localhost", "L");
    CHECK(out && !strcmp(out, "LL"), "adjacent: [%s]", out);
    free(out);
}

static void expect_resolve(const char *in, const char *base, const char *want) {
    char *got = resolve_relative_uris(in, base);
    CHECK(got && !strcmp(got, want), "resolve(%s)\n  want [%s]\n  got  [%s]", base, want, got ? got : "(null)");
    free(got);
}

static void test_resolve(void) {
    const char *base = "mlhls://localhost/itag/master.m3u8?sig=abc";
    expect_resolve("#EXTM3U\nv1/index.m3u8\n", base, "#EXTM3U\nmlhls://localhost/itag/v1/index.m3u8\n");
    expect_resolve("/root/index.m3u8\n", base, "mlhls://localhost/root/index.m3u8\n");
    expect_resolve("https://cdn.example/a.m3u8\n", base, "https://cdn.example/a.m3u8\n");
    expect_resolve("//cdn.example/a.m3u8\n", base, "mlhls://cdn.example/a.m3u8\n");
    expect_resolve("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/en.m3u8\",NAME=\"en\"\n", base,
                   "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"mlhls://localhost/itag/audio/en.m3u8\",NAME=\"en\"\n");
    expect_resolve("#EXT-X-MEDIA:URI=\"mlhls://localhost/abs.m3u8\"\n", base, "#EXT-X-MEDIA:URI=\"mlhls://localhost/abs.m3u8\"\n");
    expect_resolve("#EXTM3U\r\nv.m3u8\r\n", base, "#EXTM3U\r\nmlhls://localhost/itag/v.m3u8\r\n");
    expect_resolve("# comment with URI=\"x\"\n", base, "# comment with URI=\"x\"\n");
    expect_resolve("v.m3u8", "mlhls://localhost", "mlhls://localhost/v.m3u8");
    expect_resolve("", base, "");
    expect_resolve("#EXT-X-MEDIA:URI=\"unterminated\n", base, "#EXT-X-MEDIA:URI=\"unterminated\n");
}

/* Random and mutated playlists: no crashes, leaks or out-of-bounds access. */
static void fuzz(void) {
    static const char *atoms[] = { "#EXTM3U", "#EXT-X-STREAM-INF:BANDWIDTH=5", "URI=\"", "\"", "mlhls://localhost",
        "/", "a.m3u8", "../", "\r", "\n", "?q=1", "#", "://", " ", "m3u8", "x" };
    srand(12345);
    for (int i = 0; i < 200000; i++) {
        char buf[512];
        size_t n = 0;
        int parts = rand() % 24;
        for (int k = 0; k < parts; k++) {
            const char *a = atoms[rand() % (sizeof(atoms) / sizeof(*atoms))];
            size_t l = strlen(a);
            if (n + l >= sizeof(buf) - 1) break;
            memcpy(buf + n, a, l);
            n += l;
        }
        if (rand() % 4 == 0 && n) buf[rand() % n] = (char) (1 + rand() % 255);
        buf[n] = '\0';
        char *r = resolve_relative_uris(buf, i % 2 ? "mlhls://localhost/d/master.m3u8" : "nonsense");
        if (r) {
            char *out = adjust(r, "mlhls://localhost", i % 3 ? "http://localhost:7000" : "L");
            CHECK(out != NULL, "adjust returned NULL");
            free(out);
            char **table = NULL;
            int num = 0;
            if (create_media_uri_table("mlhls://localhost", r, (int) strlen(r), &table, &num) == 0) {
                for (int k = 0; k < num; k++) free(table[k]);
                free(table);
            }
            free(r);
        }
    }
}

int main(void) {
    test_adjust();
    test_resolve();
    fuzz();
    if (failures) { fprintf(stderr, "test_hls: %d failure(s)\n", failures); return 1; }
    printf("test_hls: ok\n");
    return 0;
}
