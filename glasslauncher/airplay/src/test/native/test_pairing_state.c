#include <assert.h>
#include <stdio.h>
#include "srp.c"
#include "pairing.c"
static void unhex(unsigned char *out,const char *s) {
    for(size_t i=0;i<strlen(s)/2;i++){unsigned int v;assert(sscanf(s+2*i,"%2x",&v)==1);out[i]=(unsigned char)v;}
}
static void derive(const char *salt,const unsigned char *secret,unsigned char *out) {
    unsigned char hash[64];sha_ctx_t *h=sha_init();
    sha_update(h,(const unsigned char *)salt,strlen(salt));
    sha_update(h,secret,40);sha_final(h,hash,NULL);sha_destroy(h);memcpy(out,hash,16);
}
int main(int argc,char **argv) {
    assert(argc==2);int generated=0;
    pairing_t *server=pairing_init_generate("audit-qa",argv[1],&generated);assert(server);
    pairing_session_t *session=pairing_session_init(server);assert(session);
    unsigned char a[256]={1},proof[64]={0},epk[32]={0},tag[16]={0};
    assert(!pairing_session_authorized(session));
    assert(srp_validate_proof(session,server,a,256,proof,20,64)<0);
    assert(srp_confirm_pair_setup(session,server,epk,tag)<0);
    const char *salt_out,*b_out;int salt_len,b_len;
    assert(!srp_new_user(session,server,"alice","1234",&salt_out,&salt_len,&b_out,&b_len));
    free((void *)b_out);
    unsigned char zero_secret[40]={0},zero_key[16],zero_iv[16],zero_pub[32]={1};
    derive("Pair-Setup-AES-Key",zero_secret,zero_key);
    derive("Pair-Setup-AES-IV",zero_secret,zero_iv);zero_iv[15]++;
    assert(gcm_encrypt(zero_pub,32,epk,zero_key,zero_iv,tag)==32);
    assert(srp_confirm_pair_setup(session,server,epk,tag)<0); // never accept the pre-proof zero session key
    assert(!pairing_session_authorized(session));
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

    strcpy(session->username,"alice");
    unhex(session->srp->salt,"beb25379d1a8581eb5a727673a2441ee");
    unhex(session->srp->verifier,vh);
    unhex(session->srp->private_key,"e487cb59d31ac550471e81f00f6928e01dda08e974a004f49e61f5d105284d20");
    unhex(a,ah);
    const unsigned char *B=NULL;int length=0;
    struct SRPVerifier *expected=srp_verifier_new(SRP_SHA,SRP_NG_2048,"alice",
        session->srp->salt,16,session->srp->verifier,256,a,256,
        session->srp->private_key,32,&B,&length,NULL,NULL,1);
    assert(expected);memcpy(proof,expected->M,20);srp_verifier_delete(expected);
    assert(!srp_validate_proof(session,server,a,256,proof,20,64));
    assert(!pairing_session_authorized(session));
    unsigned char key[16],iv[16],pub[32];
    unhex(pub,"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    derive("Pair-Setup-AES-Key",session->srp->session_key,key);
    derive("Pair-Setup-AES-IV",session->srp->session_key,iv);iv[15]++;
    assert(gcm_encrypt(pub,32,epk,key,iv,tag)==32);
    assert(srp_confirm_pair_setup(session,server,epk,tag)==32);
    assert(pairing_session_authorized(session));
    assert(srp_confirm_pair_setup(session,server,epk,tag)<0); // state was consumed
    pairing_session_destroy(session);pairing_destroy(server);
    puts("test_pairing_state: valid proof/GCM and invalid sequences passed");return 0;
}
