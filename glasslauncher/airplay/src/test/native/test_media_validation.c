#include <assert.h>
#include <stdio.h>
#include "mirror_config.h"
#include "control_query.h"
int main(void) {
    unsigned char output[1024], data[256] = {0};
    size_t n; int hevc; double v;
    assert(http_control_number("/rate?value=0", "value", &v) && v == 0);
    assert(http_control_number("/scrub?other=1&position=12.5", "position", &v) && v == 12.5);
    const char *invalid[] = {"/rate?value", "/rate?value=", "/rate?value=nan",
        "/rate?value=inf", "/rate?value=2oops", "/rate?wrong=1", "/rate"};
    for (size_t i=0; i<sizeof(invalid)/sizeof(invalid[0]); i++)
        assert(!http_control_number(invalid[i],"value",&v));
    data[7]=2; data[8]=0x67; data[9]=1; data[10]=1; data[12]=2; data[13]=0x68; data[14]=1;
    assert(mirror_config_parse(data,15,output,sizeof(output),&n,&hevc) && !hevc && n==12);
    assert(output[3]==1 && output[4]==0x67 && output[9]==1 && output[10]==0x68);
    for (size_t i=0; i<15; i++) assert(!mirror_config_parse(data,i,output,sizeof(output),&n,&hevc));
    data[6]=0xff; data[7]=0xfc;
    assert(!mirror_config_parse(data,15,output,sizeof(output),&n,&hevc));
    memset(data,0,sizeof(data)); memcpy(data+4,"hvc1",4);
    size_t pos=0x75;
    for (int i=0;i<3;i++) { data[pos]=0xa0+i; data[pos+2]=1; data[pos+4]=1; data[pos+5]=0x40+i; pos+=6; }
    assert(mirror_config_parse(data,pos,output,sizeof(output),&n,&hevc) && hevc && n==15);
    for (size_t i=0;i<pos;i++) assert(!mirror_config_parse(data,i,output,sizeof(output),&n,&hevc));
    for (int i=0;i<20000;i++) {
        for (size_t j=0;j<sizeof(data);j++) data[j]=(unsigned char)rand();
        size_t size=rand()%sizeof(data);
        mirror_config_parse(data,size,output,sizeof(output),&n,&hevc);
        assert(n<=sizeof(output));
    }
    puts("test_media_validation: ok"); return 0;
}
