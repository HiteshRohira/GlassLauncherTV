#include <cstdio>
#include "timeline_buffer.h"

int main() {
    constexpr int rate=44100, channels=2;
    const auto ms=[](size_t samples){return samples*1000/rate/channels;};
    DelayTracker adaptive(rate,channels,0,99);
    adaptive.noteOutputBufferFrames(512);
    for(int i=0;i<4000;i++) {
        const int64_t pts=1'000'000'000LL+i*10'000'000LL;
        adaptive.observe(pts,pts+15'000'000LL,10'000'000LL);
    }
    if(ms(adaptive.target())<80 || ms(adaptive.target())>85)return 1;
    for(int i=0;i<4000;i++) {
        const int64_t pts=50'000'000'000LL+i*10'000'000LL;
        adaptive.observe(pts,pts+15'000'000LL+(i%5==0?120'000'000LL:0),10'000'000LL);
    }
    if(ms(adaptive.target())<100 || ms(adaptive.target())>1000)return 2;
    DelayTracker fixed(rate,channels,20,99);
    fixed.observe(1'000'000'000LL,2'000'000'000LL,10'000'000LL);
    if(ms(fixed.target())!=20)return 3;
    std::puts("adaptive headroom: quiet floor, burst response, and fixed override passed");
}
