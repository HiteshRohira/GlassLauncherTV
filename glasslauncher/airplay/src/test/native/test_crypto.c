/* Test-only harness; link the real UxPlay crypto.c/srp.c/utils.c and libcrypto.
 * Vectors: NIST SP800-38A F.2/F.5; RFC8032 7.1 test 1; RFC7748 6.1;
 * SHA/MD5 "abc" standard vectors. GCM16 zero-input fixture independently
 * checked with Python cryptography AESGCM (16-byte IV, as UxPlay uses).
 * No replacement crypto, network traffic, or production key material.
 * Pass a NEW, nonexistent scratch PEM filename; it is deliberately retained.
 */
#include "crypto.h"
#include "srp.h"
#include "raop_buffer.h"
#include <openssl/bn.h>
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static unsigned checks, failures;
#define CHECK(c, label) do { ++checks; if (!(c)) { ++failures; \
    fprintf(stderr, "FAIL line %d: %s\n", __LINE__, label); ERR_print_errors_fp(stderr); } } while (0)
#define REQUIRE(c) do { if (!(c)) { fprintf(stderr, "FATAL line %d: %s\n", __LINE__, #c); \
    ERR_print_errors_fp(stderr); exit(2); } } while (0)
static size_t unhex(unsigned char *out, const char *s) {
    size_t n = strlen(s) / 2;
    REQUIRE(strlen(s) % 2 == 0);
    for (size_t i = 0; i < n; ++i) {
        unsigned v; REQUIRE(sscanf(s + i * 2, "%2x", &v) == 1); out[i] = (unsigned char)v;
    }
    return n;
}
static void eqhex(const unsigned char *out, size_t n, const char *hex, const char *label) {
    unsigned char want[1024]; REQUIRE(strlen(hex) / 2 <= sizeof(want));
    size_t wn = unhex(want, hex); CHECK(n == wn && memcmp(out, want, wn) == 0, label);
}

static void test_aes(void) {
    unsigned char key[16], iv[16], p[64], c[64], out[80], back[80];
    unhex(key, "2b7e151628aed2a6abf7158809cf4f3c");
    unhex(p, "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
             "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710");
    unhex(iv, "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
    unhex(c, "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff"
             "5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee");
    aes_ctx_t *ctr = aes_ctr_init(key, iv);
    aes_ctr_encrypt(ctr, p, out, 64); CHECK(memcmp(c, out, 64) == 0, "CTR NIST four blocks");
    aes_ctr_reset(ctr);
    aes_ctr_encrypt(ctr, p, out, 1); aes_ctr_encrypt(ctr, p+1, out+1, 14);
    aes_ctr_encrypt(ctr, p+15, out+15, 18); aes_ctr_encrypt(ctr, p+33, out+33, 31);
    CHECK(memcmp(c, out, 64) == 0, "CTR fragmented encrypt");
    aes_ctr_reset(ctr); aes_ctr_decrypt(ctr, c, back, 7); aes_ctr_decrypt(ctr, c+7, back+7, 57);
    CHECK(memcmp(p, back, 64) == 0, "CTR fragmented decrypt/reset");
    /* Isolate the fresh-block alignment checks in a fresh context. */
    aes_ctr_destroy(ctr); ctr = aes_ctr_init(key, iv);
    aes_ctr_encrypt(ctr, p, out, 5); aes_ctr_start_fresh_block(ctr);
    aes_ctr_encrypt(ctr, p+16, out+16, 16);
    CHECK(memcmp(c+16, out+16, 16) == 0, "CTR fresh block skips remainder");
    aes_ctr_start_fresh_block(ctr); aes_ctr_encrypt(ctr, p+32, out+32, 16);
    CHECK(memcmp(c+32, out+32, 16) == 0, "CTR fresh block aligned no-op");
    aes_ctr_destroy(ctr);

    unhex(iv, "000102030405060708090a0b0c0d0e0f");
    unhex(c, "7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2"
             "73bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7");
    aes_ctx_t *enc = aes_cbc_init(key, iv, AES_ENCRYPT), *dec = aes_cbc_init(key, iv, AES_DECRYPT);
    aes_cbc_encrypt(enc, p, out, 64); CHECK(memcmp(c, out, 64) == 0, "CBC NIST four blocks");
    aes_cbc_decrypt(dec, c, back, 64); CHECK(memcmp(p, back, 64) == 0, "CBC NIST decrypt");
    aes_cbc_reset(enc); aes_cbc_encrypt(enc, p, out, 64);
    CHECK(memcmp(c, out, 64) == 0, "CBC encrypt packet reset");
    aes_cbc_reset(dec); memset(back, 0xa5, sizeof(back)); aes_cbc_decrypt(dec, c, back, 16);
    CHECK(memcmp(p, back, 16) == 0, "CBC decrypt packet reset");
    CHECK(back[16] == 0xa5 && back[79] == 0xa5, "CBC writes only encrypted blocks");
    aes_cbc_destroy(enc); aes_cbc_destroy(dec);
    /* Exercise the real RAOP wrapper too: two packets reset the CBC IV and
     * retain an unencrypted tail. No AES stub from the no-data test is used. */
    logger_t *logger = logger_init(); REQUIRE(logger);
    raop_buffer_t *raop = raop_buffer_init(logger, key, iv); REQUIRE(raop);
    unsigned char packet[12+35] = {0}; unsigned outlen = 0;
    memcpy(packet+12, c, 32); memcpy(packet+44, "end", 3);
    for (int i = 0; i < 2; ++i) {
        CHECK(raop_buffer_decrypt(raop, packet, back, 35, &outlen) == 1 && outlen == 35 &&
              memcmp(back, p, 32) == 0 && memcmp(back+32, "end", 3) == 0,
              "real RAOP CBC reset/clear tail");
    }
    raop_buffer_destroy(raop); logger_destroy(logger);

    unsigned char zero[16] = {0}, tag[16], bad[16];
    CHECK(gcm_encrypt(zero, 16, out, zero, zero, tag) == 16, "GCM16 encrypt length");
    eqhex(out, 16, "a3b22b8449afafbcd6c09f2cfa9de2be", "GCM16 fixed ciphertext");
    eqhex(tag, 16, "d8b820bab954bd1647d8a9c3d534e7a3", "GCM16 fixed tag");
    CHECK(gcm_decrypt(out, 16, back, zero, zero, tag) == 16 && memcmp(back, zero, 16) == 0, "GCM16 roundtrip");
    memcpy(bad, tag, 16); bad[0] ^= 1;
    CHECK(gcm_decrypt(out, 16, back, zero, zero, bad) == -1, "GCM rejects modified tag");
    out[0] ^= 1; CHECK(gcm_decrypt(out, 16, back, zero, zero, tag) == -1, "GCM rejects modified ciphertext");
    CHECK(gcm_encrypt(zero, 0, out, zero, zero, tag) == 0 &&
          gcm_decrypt(out, 0, back, zero, zero, tag) == 0, "GCM empty roundtrip");
    puts("AES CTR/CBC/GCM: complete");
}

static void test_hashes(void) {
    static const char *vectors[] = {
        "a9993e364706816aba3e25717850c26c9cd0d89d",
        "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7",
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
    };
    const EVP_MD *md[] = {EVP_sha1(), EVP_sha224(), EVP_sha256(), EVP_sha384(), EVP_sha512()};
    unsigned char out[64]; unsigned len;
    for (size_t i = 0; i < 5; ++i) {
        REQUIRE(EVP_Digest("abc", 3, out, &len, md[i], NULL) == 1);
        eqhex(out, len, vectors[i], "SHA abc fixed vector");
    }
    sha_ctx_t *sha = sha_init();
    for (int i = 0; i < 2; ++i) {
        sha_update(sha, (const unsigned char *)"a", 1); sha_update(sha, (const unsigned char *)"bc", 2);
        sha_final(sha, out, &len); eqhex(out, len, vectors[4], "SHA512 wrapper/reset"); sha_reset(sha);
    }
    sha_destroy(sha);
    md5_ctx_t *md5 = md5_init();
    for (int i = 0; i < 2; ++i) {
        md5_update(md5, (const unsigned char *)"abc", 3); md5_final(md5, out, &len);
        eqhex(out, len, "900150983cd24fb0d6963f7d28e17f72", "MD5 wrapper/reset"); md5_reset(md5);
    }
    md5_destroy(md5); char *s = get_md5("abc");
    CHECK(s && strcmp(s, "900150983cd24fb0d6963f7d28e17f72") == 0, "MD5 string wrapper"); free(s);
    puts("SHA1/224/256/384/512 and MD5: complete");
}

static void test_keys(const char *pem) {
    unsigned char seed[32], pk[32], expectedpk[32], sig[64], expected[64], secret[32];
    unhex(seed, "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    unhex(expectedpk, "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    unhex(expected, "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
    EVP_PKEY *raw = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, seed, 32); REQUIRE(raw);
    EVP_MD_CTX *m = EVP_MD_CTX_new(); REQUIRE(m); size_t n = 64, pn = 32;
    REQUIRE(EVP_PKEY_get_raw_public_key(raw, pk, &pn) == 1);
    CHECK(pn == 32 && memcmp(pk, expectedpk, 32) == 0, "RFC8032 public key");
    REQUIRE(EVP_DigestSignInit(m, NULL, NULL, NULL, raw) == 1);
    REQUIRE(EVP_DigestSign(m, sig, &n, (const unsigned char *)"", 0) == 1);
    CHECK(n == 64 && memcmp(sig, expected, 64) == 0, "RFC8032 deterministic signature");
    ed25519_key_t *pub = ed25519_key_from_raw(pk);
    CHECK(ed25519_verify(sig, 64, (const unsigned char *)"", 0, pub) == 1, "Ed25519 wrapper RFC verify");
    CHECK(ed25519_verify(sig, 64, (const unsigned char *)"x", 1, pub) == 0, "Ed25519 rejects changed message");
    sig[0] ^= 1; CHECK(ed25519_verify(sig, 64, (const unsigned char *)"", 0, pub) == 0, "Ed25519 rejects changed signature");
    CHECK(ed25519_verify(expected, 63, (const unsigned char *)"", 0, pub) == 0, "Ed25519 rejects short signature");
    ed25519_key_destroy(pub); EVP_MD_CTX_free(m); EVP_PKEY_free(raw);

    unhex(seed, "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    unhex(pk, "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
    raw = EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, NULL, seed, 32); REQUIRE(raw);
    EVP_PKEY *peer = EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519, NULL, pk, 32); REQUIRE(peer);
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(raw, NULL); REQUIRE(ctx);
    REQUIRE(EVP_PKEY_derive_init(ctx) == 1 && EVP_PKEY_derive_set_peer(ctx, peer) == 1); n = 32;
    REQUIRE(EVP_PKEY_derive(ctx, secret, &n) == 1);
    eqhex(secret, n, "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", "RFC7748 shared secret");
    EVP_PKEY_CTX_free(ctx); EVP_PKEY_free(peer);
    memset(pk, 0, 32); peer = EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519, NULL, pk, 32); REQUIRE(peer);
    ctx = EVP_PKEY_CTX_new(raw, NULL); REQUIRE(ctx);
    REQUIRE(EVP_PKEY_derive_init(ctx) == 1 && EVP_PKEY_derive_set_peer(ctx, peer) == 1); n = 32;
    CHECK(EVP_PKEY_derive(ctx, secret, &n) <= 0, "X25519 rejects all-zero peer"); ERR_clear_error();
    EVP_PKEY_CTX_free(ctx); EVP_PKEY_free(peer); EVP_PKEY_free(raw);
    x25519_key_t *a = x25519_key_generate(), *b = x25519_key_generate();
    unsigned char ap[32], bp[32], ab[32], ba[32]; x25519_key_get_raw(ap, a); x25519_key_get_raw(bp, b);
    x25519_key_t *apub = x25519_key_from_raw(ap), *bpub = x25519_key_from_raw(bp);
    x25519_derive_secret(ab, a, bpub); x25519_derive_secret(ba, b, apub);
    CHECK(memcmp(ab, ba, 32) == 0 && memcmp(ab, (unsigned char[32]){0}, 32) != 0, "X25519 wrapper mutual agreement");
    x25519_key_destroy(a); x25519_key_destroy(b); x25519_key_destroy(apub); x25519_key_destroy(bpub);

    REQUIRE(access(pem, F_OK) != 0); int result = -1;
    ed25519_key_t *generated = ed25519_key_generate("test-only-device", pem, &result);
    CHECK(result == 1, "fresh random Ed25519 PEM written"); ed25519_key_get_raw(ap, generated);
    printf("PEM public: "); for (size_t i = 0; i < 32; ++i) printf("%02x", ap[i]); putchar('\n');
    ed25519_key_t *copy = ed25519_key_copy(generated); ed25519_key_destroy(generated);
    ed25519_sign(sig, sizeof(sig), (const unsigned char *)"persist", 7, copy); ed25519_key_destroy(copy);
    generated = ed25519_key_generate("different-device-id", pem, &result);
    ed25519_key_get_raw(bp, generated);
    CHECK(result == 0 && memcmp(ap, bp, 32) == 0, "PEM reload preserves public identity");
    CHECK(ed25519_verify(sig, 64, (const unsigned char *)"persist", 7, generated) == 1, "PEM reload verifies previous signature");
    ed25519_key_destroy(generated);
    unsigned char r1[64], r2[64];
    CHECK(RAND_status() == 1 && get_random_bytes(r1, 64) == 1 && get_random_bytes(r2, 64) == 1,
          "CSPRNG initialized and successful");
    CHECK(memcmp(r1, r2, 64) != 0 && memcmp(r1, (unsigned char[64]){0}, 64) != 0, "CSPRNG basic liveness (not entropy certification)");
    puts("Ed25519/X25519/PEM/CSPRNG: complete");
}

static void test_srp(void) {
    /* SRP-2048 fixed fixture, unpadded Apple variant, SHA1. N/g from RFC5054
     * Appendix A; salt/a/b from Appendix B, used with the 2048-bit group.
     * Expected v/A/B/K/M/HAMK computed OFFLINE using Python int pow/hashlib,
     * independently of UxPlay and OpenSSL. No expected result is derived here.
     * This exercises actual srp.c's BN operations, proof and rejection paths.
     */
    const char *vh =
      "960c64fa1148b0074457e3eb45db6f7929b368cd06c6c582fb39e5961178c8946d940da78bdc3e73f1a60cdbc7bba2fbd83d31bc3906e986038455b81fb881fed4"
      "f8119b312138ce17afc09b12ba91c9a49f2ab593993255138f6ec39e95f67294248df9d95aae72ace37b95a747c6b35112e68b0f33a3c57563e0f75415084b5"
      "c6594179cb97a10aceac6338d1def7dce73a0bd3689d5fef55ebed63cbb4ac5b049e53a9d9b5075ab32f771f5ea881b92d29cd27348328f3f9235b2a58cf432"
      "62365c1b1dd6b7d96bc2df3ae70e1009e2cfea30115dc2260c17c54bbf4af223c773ee4bcf6dbee2990cb484e38addfd0df6be7727ce1875ebccf15f538b310c";
    const char *ah =
      "4b700f8d48e69c9aae40c684ac7c7c03121e2b7602eb4c3514804ccada0ed4019193a351ecc65a6f854ede91eb096e721b22d701c7adc64e9cedacd75f2e26bb"
      "2f5e45dd53dc8dbeafffe82aa49fca0573444691212537a73cf80e25039258205a7edf4749b30adaf25877c62fcd09d6613598bcd4baf2a9727a53706a2781489"
      "92b2abb23ad5d512d269e16ca11bc0895b5a3b5ec4721cde40a8c39c796e94f0be86dbbeb33da7037018983921aba3f5053195d5ac1da4e567e3c0e75d9e0609"
      "f92e850657b2be4771f415b9cacc5c1ecedc30133bf6474f5022c6519d780760ca4d8d3b966b034bd73877c1b3b33f474b9c3c5299a1968f3e6cd3bfe84445a";
    const char *bh =
      "4a0348510e6600a73b03274b0f10de5fe340553935def488ebfcaccddf7ae10390d99a0d0389e8cb4d91187d395295cccfe0a60a91ee0385ceda10b2cdb331ba"
      "8e90a1640d251631e4e346f7d27a78c4d4e1f2d3fb11cd00b87949b9225564aabebdd52dba40f5c283c06fedea7eb6325f235abb4df8944f6bf55b433c2f523"
      "2fde6ecfaa5a6dba3ca095175342c943d117b638c1f701699333e9eba39f0899c2fd76d9044cf8b0d70c86d1a19e90de672af9a75206eb4a1f1e51fe2df95db3"
      "23d52b894a5e998401c372f55be9919a7c45796dd49ee131614d86dac80d7fec5d8513aeb22ea75acee6ea17dd2b9d463dfe76506d5c5efb24ad0b35f9ae31fe"
      "e18c3e1f67b7e5c2877ff8b0bede6b009cb57e5c3";
    unsigned char v[256], a[256], b[32], salt[16], proof[20], bad[20];
    unhex(v, vh); unhex(a, ah); unhex(b, "e487cb59d31ac550471e81f00f6928e01dda08e974a004f49e61f5d105284d20");
    unhex(salt, "beb25379d1a8581eb5a727673a2441ee");
    const unsigned char *B = NULL, *hamk = NULL; int blen = 0, klen = 0;
    srp_create_server_ephemeral_key(SRP_SHA1, SRP_NG_2048, v, 256, b, 32, &B, &blen, NULL, NULL, 0);
    REQUIRE(B); eqhex(B, blen, bh, "SRP2048 fixed server ephemeral"); free((void *)B);
    struct SRPVerifier *ver = srp_verifier_new(SRP_SHA1, SRP_NG_2048, "alice", salt, 16, v, 256,
                                             a, 256, b, 32, &B, &blen, NULL, NULL, 0);
    REQUIRE(ver && B); eqhex(B, blen, bh, "SRP2048 verifier fixed ephemeral");
    const unsigned char *K = srp_verifier_get_session_key(ver, &klen);
    eqhex(K, klen, "82cf34884ad93db0dcdd62ccf1c2e19c60f6ad180bf872a06336e2ee8be1cb24e594416b224a2abc", "SRP2048 Apple session key");
    unhex(proof, "64ac32db3e4a3c95c683572225ee8c13513c6c26"); memcpy(bad, proof, 20); bad[0] ^= 1;
    srp_verifier_verify_session(ver, bad, &hamk);
    CHECK(!hamk && !srp_verifier_is_authenticated(ver), "SRP rejects bad client proof");
    srp_verifier_verify_session(ver, proof, &hamk); REQUIRE(hamk);
    CHECK(srp_verifier_is_authenticated(ver), "SRP accepts fixed valid client proof");
    eqhex(hamk, 20, "690fda14c1c89f54257a302935f8509cc9529874", "SRP fixed server proof");
    srp_verifier_delete(ver);
    unsigned char zero = 0;
    ver = srp_verifier_new(SRP_SHA1, SRP_NG_2048, "alice", salt, 16, v, 256, &zero, 1,
                           b, 32, &B, &blen, NULL, NULL, 0);
    CHECK(!ver && !B && blen == 0, "SRP rejects zero client public key"); srp_verifier_delete(ver);
    /* Direct BN known-answer independent of SRP exchange. */
    BN_CTX *ctx = BN_CTX_new(); BIGNUM *base = BN_new(), *exp = BN_new(), *mod = BN_new(), *out = BN_new();
    REQUIRE(ctx && base && exp && mod && out);
    REQUIRE(BN_set_word(base, 4) && BN_set_word(exp, 13) && BN_set_word(mod, 497));
    REQUIRE(BN_mod_exp(out, base, exp, mod, ctx)); CHECK(BN_get_word(out) == 445, "BN modular exponent fixed answer");
    BN_free(base); BN_free(exp); BN_free(mod); BN_free(out); BN_CTX_free(ctx);
    puts("BN and SRP2048 fixed exchange/rejection: complete");
}

int main(int argc, char **argv) {
    if (argc == 4 && strcmp(argv[1], "--reload") == 0) {
        REQUIRE(access(argv[2], F_OK) == 0); int result; unsigned char pk[32];
        ed25519_key_t *key = ed25519_key_generate("another-process", argv[2], &result);
        ed25519_key_get_raw(pk, key); CHECK(result == 0, "second process reads existing PEM");
        eqhex(pk, 32, argv[3], "second process public identity"); ed25519_key_destroy(key);
        printf("PEM subprocess reload: %u checks, %u failures\n", checks, failures); return failures ? 1 : 0;
    }
    if (argc != 2) { fprintf(stderr, "usage: %s NEW_SCRATCH_PEM_PATH\n", argv[0]); return 2; }
    printf("libcrypto runtime: %s; headers: %s\n", OpenSSL_version(OPENSSL_VERSION), OPENSSL_VERSION_TEXT);
    fflush(stdout); test_aes(); test_hashes(); test_keys(argv[1]); test_srp();
    printf("crypto harness: %u checks, %u failures; scratch PEM retained\n", checks, failures);
    return failures ? 1 : 0;
}
