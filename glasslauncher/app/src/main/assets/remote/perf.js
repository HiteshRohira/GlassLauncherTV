// Loaded only by an authenticated diagnostic session opened with ?perf=1.
// Browser and TV monotonic clocks differ. The minimum-RTT midpoint estimate
// carries an explicit ±RTT/2 uncertainty; it is never an exact network delay.
export async function create(api){
  let clock=null,sequence=0,calibrating=false;
  async function calibrate(){
    if(calibrating)return;calibrating=true;
    try{
      let best=null;
      for(let i=0;i<7;i++){
        const before=performance.now(),reply=await api('performance/clock'),after=performance.now();
        const rtt=after-before,offset=reply.server_ms-(before+after)/2;
        if(Number.isFinite(offset)&&(!best||rtt<best.rtt))best={rtt,offset,at:after};
      }
      clock=best;
    }finally{calibrating=false;}
  }
  await calibrate();
  return {
    capture(){
      const pressed=performance.now();
      if(!clock||pressed-clock.at>60000){calibrate().catch(()=>{});return null;}
      return {sequence:++sequence,pressed,clock};
    },
    decorate(body,stamp){
      if(!stamp)return body;
      const sent=performance.now();
      return {...body,_perf:{sequence:stamp.sequence,pressed_tv_ms:stamp.pressed+stamp.clock.offset,
        sent_tv_ms:sent+stamp.clock.offset,uncertainty_ms:stamp.clock.rtt/2,clock_age_ms:sent-stamp.clock.at}};
    }
  };
}
