'use strict';
const $=id=>document.getElementById(id);
const standalone=navigator.standalone===true||matchMedia('(display-mode: standalone)').matches;
let authenticated=false,connected=false,busy=false,pollTimer,lastStatus=null,polled=false,lastReconnect=0;
let performanceDiagnostics=null,performanceLoading=false;
const store={get:(k,d)=>{try{return localStorage.getItem(k)??d;}catch{return d;}},set:(k,v)=>{try{localStorage.setItem(k,v);}catch{}}};
let navMode=store.get('glass.nav','pad'),haptics=store.get('glass.haptics','on')==='on';
const feedback=text=>{$('feedback').textContent=text;};

// ── Haptics: Safari (iOS 18+) ticks when a switch control toggles; Android uses vibrate.
function haptic(){
  if(!haptics)return;
  if(navigator.vibrate){navigator.vibrate(8);return;}
  $('hapticLabel').click();
}

// ── Network ──
// Every request has a deadline, so a dropped connection (phone or TV asleep,
// Wi-Fi change) fails and recovers instead of holding the key queue forever.
async function api(path,data,timeoutMs=8000){
  const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),timeoutMs);
  let response;
  try{response=await fetch('/api/'+path,{method:data===undefined?'GET':'POST',headers:data===undefined?{}:{'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data),credentials:'same-origin',cache:'no-store',signal:abort.signal});}
  catch(e){throw new Error(e?.name==='AbortError'?'The TV did not answer. Check it is on and on the same Wi‑Fi.':'TV is unreachable. Keep it on and use the same Wi‑Fi.');}
  finally{clearTimeout(timer);}
  let result;try{result=await response.json();}catch{throw new Error('The TV could not complete that request.');}
  if(response.status===401){authenticated=false;render();throw new Error('Enter the pairing code from your TV.');}
  if(!response.ok)throw new Error(result.error||'The TV could not complete that request.');return result;
}
function render(){
  const ready=authenticated&&connected;
  document.querySelectorAll('[data-key]').forEach(b=>b.disabled=!ready);
  $('keyboardButton').disabled=!ready;
  $('pad').classList.toggle('disabled',!ready);
  $('status').classList.toggle('connected',ready);
  $('statusText').textContent=!authenticated?'Not paired':ready?'Connected':lastStatus?.pairing?'Approve on your TV':lastStatus?.paired?'Reconnecting…':'Set up TV controls';
  $('startNative').disabled=!!lastStatus?.pairing;
  $('reconnect').hidden=!(authenticated&&!connected&&lastStatus?.paired);
  $('pairButton').hidden=!polled||authenticated;
  $('openInstall').hidden=standalone;
  if(!polled)return;
  if(!authenticated&&!sheetOpen())openSheet('pairPanel');
  else if(authenticated&&!connected&&lastStatus&&!lastStatus.paired&&!sheetOpen())openSheet('nativePanel');
  else if(ready&&(isOpen('pairPanel')||isOpen('nativePanel'))){closeSheets();offerInstall();}
  else if(ready&&!sheetOpen())offerInstall();
}
let polling=false;
async function poll(){
  clearTimeout(pollTimer);
  if(polling)return;   // visibility changes can ask again while one is in flight
  polling=true;
  try{const was=connected;lastStatus=await api('status');authenticated=true;connected=lastStatus.connected;if(connected&&!was)feedback('');
    if(!connected&&lastStatus.message)$('nativeHint').textContent=lastStatus.message;
    // A paired phone reconnects on its own after the TV restarts.
    if(!connected&&lastStatus.paired&&!lastStatus.pairing&&!busy&&Date.now()-lastReconnect>10000){lastReconnect=Date.now();api('reconnect',{}).catch(()=>{});}
  }catch(error){connected=false;if(authenticated)feedback('TV is unreachable. Keep it on and use the same Wi‑Fi.');}
  polling=false;
  polled=true;
  render();
  if(connected&&lastStatus?.performanceEnabled&&!performanceLoading&&!performanceDiagnostics
      &&new URLSearchParams(location.search).get('perf')==='1'){
    performanceLoading=true;
    import('/perf.js').then(m=>m.create(api)).then(p=>{performanceDiagnostics=p;})
      .catch(()=>{}).finally(()=>{performanceLoading=false;});
  }
  pollTimer=setTimeout(poll,document.hidden?15000:connected?4000:2000);
}
async function action(fn){if(busy)return;busy=true;feedback('');try{await fn();}catch(e){feedback(e.message);}finally{busy=false;}}

// ── Sheets ──
const sheets=['pairPanel','nativePanel','settingsPanel','keyboardPanel','installPanel'];
const isOpen=id=>!$(id).hidden;
const sheetOpen=()=>sheets.some(isOpen);
// Sheets are modal dialogs: the page behind is inert for screen readers and
// focus returns to where it was when the sheet closes.
let sheetReturnFocus=null;
function openSheet(id){if(!sheetOpen())sheetReturnFocus=document.activeElement;sheets.forEach(s=>$(s).hidden=s!==id);$('scrim').hidden=false;$('app').inert=true;if(id==='pairPanel')setTimeout(()=>$('pin').focus(),250);if(id==='keyboardPanel')$('tvText').focus();}
function closeSheets(){sheets.forEach(s=>$(s).hidden=true);$('scrim').hidden=true;$('app').inert=false;document.activeElement?.blur?.();const back=sheetReturnFocus;sheetReturnFocus=null;if(back&&back!==document.body&&back.isConnected)back.focus?.({preventScroll:true});}
$('scrim').addEventListener('click',()=>{if(authenticated)closeSheets();});
addEventListener('keydown',e=>{if(e.key==='Escape'&&sheetOpen()&&authenticated){e.stopImmediatePropagation();closeSheets();}},true);
$('setupButton').addEventListener('click',()=>{haptic();openSheet('settingsPanel');});
$('closeSettings').addEventListener('click',closeSheets);
$('openSetup').addEventListener('click',()=>openSheet(authenticated?'nativePanel':'pairPanel'));
$('keyboardButton').addEventListener('click',()=>{haptic();openSheet('keyboardPanel');});
$('closeKeyboard').addEventListener('click',closeSheets);
$('pairForm').addEventListener('submit',event=>{event.preventDefault();const pin=$('pin').value.trim();action(async()=>{const result=await api('pair',{pin});
    if(afterPair(result))return;
    $('pin').value='';authenticated=true;closeSheets();await poll();});});
$('pairButton').addEventListener('click',()=>openSheet('pairPanel'));
$('startNative').addEventListener('click',()=>action(async()=>{await api('native/start',{});feedback('Approve the prompt on your TV.');setTimeout(poll,1500);}));
$('reconnect').addEventListener('click',()=>action(async()=>{await api('reconnect',{});setTimeout(poll,800);}));
$('textForm').addEventListener('submit',event=>{event.preventDefault();const text=$('tvText').value;if(!text)return;action(async()=>{const result=await api('text',{text},20000);$('tvText').value='';haptic();feedback(result.pending?'Text is still being delivered. Avoid sending it twice.':'Sent to the TV.');});});
$('tvText').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();$('textForm').requestSubmit();}});

// Settings toggles
function paintSettings(){
  document.querySelectorAll('[data-mode]').forEach(b=>b.setAttribute('aria-checked',String(b.dataset.mode===navMode)));
  document.querySelectorAll('[data-haptics]').forEach(b=>b.setAttribute('aria-checked',String((b.dataset.haptics==='on')===haptics)));
  $('pad').classList.toggle('dpad',navMode==='dpad');
  $('padHint').textContent=navMode==='dpad'?'Tap the arrows · centre to select · hold it for options':'Swipe to move · tap to select · hold for options';
}
document.querySelectorAll('[data-mode]').forEach(b=>b.addEventListener('click',()=>{navMode=b.dataset.mode;store.set('glass.nav',navMode);paintSettings();haptic();}));
document.querySelectorAll('[data-haptics]').forEach(b=>b.addEventListener('click',()=>{haptics=b.dataset.haptics==='on';store.set('glass.haptics',haptics?'on':'off');paintSettings();haptic();}));

// ── Keys: one in-flight queue so presses arrive in order; drop floods.
// The TV accepts a key at once and presses it in order, so this queue drains
// quickly; it only backs up when the TV is unreachable.
let commandQueue=Promise.resolve(),queued=0;
function sendKey(key,long=false){
  if(!connected||queued>12)return;
  queued++;haptic();
  const body=long?{key,long:true}:{key};
  const stamp=performanceDiagnostics?.capture();
  commandQueue=commandQueue.then(()=>api('key',performanceDiagnostics?.decorate(body,stamp)??body,4000)).catch(e=>feedback(e.message)).finally(()=>queued--);
}
const LONG_PRESS_MS=600;
const repeatable=new Set(['volumeUp','volumeDown','delete','rewind','forward']);
let holdTimer,repeatTimer,heldButton;
function stopHold(){clearTimeout(holdTimer);clearInterval(repeatTimer);heldButton?.classList.remove('pressed');heldButton=null;}
for(const button of document.querySelectorAll('button[data-key]:not(.sr-only)')){
  button.addEventListener('pointerdown',event=>{if(button.disabled)return;event.preventDefault();stopHold();heldButton=button;button.classList.add('pressed');button.setPointerCapture?.(event.pointerId);sendKey(button.dataset.key);
    if(repeatable.has(button.dataset.key))holdTimer=setTimeout(()=>{repeatTimer=setInterval(()=>sendKey(button.dataset.key),150);},420);});
  button.addEventListener('pointerup',stopHold);button.addEventListener('pointercancel',stopHold);button.addEventListener('lostpointercapture',stopHold);
  button.addEventListener('click',event=>{if(event.detail===0)sendKey(button.dataset.key);});
}
// Screen-reader buttons inside the pad
document.querySelectorAll('.pad .sr-only[data-key]').forEach(b=>b.addEventListener('click',()=>sendKey(b.dataset.key)));

// ── Touchpad ──
// Touchpad mode: swipe moves one step per STEP px travelled; holding after a
// swipe keeps repeating that way; a tap anywhere selects.
// Buttons mode: tap the outer ring for a direction, the centre for OK; hold repeats.
const pad=$('pad'),center=$('padCenter'),ripple=$('ripple');
const STEP=46,TAP_SLOP=12;
let touch=null;
function lit(dir){pad.querySelectorAll('.edge').forEach(e=>e.classList.toggle('lit',e.classList.contains(dir)));center.classList.toggle('lit',dir==='ok');}
function unlit(){setTimeout(()=>lit(''),120);}
function showRipple(x,y){const r=pad.getBoundingClientRect();ripple.style.left=(x-r.left)+'px';ripple.style.top=(y-r.top)+'px';ripple.classList.remove('go');void ripple.offsetWidth;ripple.classList.add('go');}
function zoneAt(x,y){
  const r=pad.getBoundingClientRect(),dx=x-(r.left+r.width/2),dy=y-(r.top+r.height/2);
  if(Math.hypot(dx,dy)<r.width*0.19)return 'ok';
  return Math.abs(dx)>Math.abs(dy)?(dx>0?'right':'left'):(dy>0?'down':'up');
}
function step(dir){lit(dir);sendKey(dir);unlit();}
pad.addEventListener('pointerdown',e=>{
  if(!connected)return;e.preventDefault();pad.setPointerCapture?.(e.pointerId);
  touch={x:e.clientX,y:e.clientY,ax:e.clientX,ay:e.clientY,moved:false,dir:null,t:performance.now()};
  if(navMode==='dpad'){const z=zoneAt(e.clientX,e.clientY);showRipple(e.clientX,e.clientY);touch.dir=z;
    if(z!=='ok'){step(z);holdTimer=setTimeout(()=>{repeatTimer=setInterval(()=>step(z),140);},420);}
    // Centre: a tap selects on release; holding it is a long press (options menus).
    else{lit('ok');holdTimer=setTimeout(()=>{touch.long=true;sendKey('ok',true);feedback('Long press');unlit();},LONG_PRESS_MS);}
  }else{
    // Touchpad: resting a finger without moving is a long press.
    holdTimer=setTimeout(()=>{if(touch&&!touch.moved){touch.long=true;showRipple(touch.x,touch.y);lit('ok');sendKey('ok',true);feedback('Long press');unlit();}},LONG_PRESS_MS);
  }
});
pad.addEventListener('pointermove',e=>{
  if(!touch||navMode==='dpad')return;
  const dx=e.clientX-touch.ax,dy=e.clientY-touch.ay;
  if(Math.hypot(e.clientX-touch.x,e.clientY-touch.y)>TAP_SLOP)touch.moved=true;
  if(Math.max(Math.abs(dx),Math.abs(dy))<STEP)return;
  const dir=Math.abs(dx)>Math.abs(dy)?(dx>0?'right':'left'):(dy>0?'down':'up');
  if(touch.long)return;
  step(dir);touch.dir=dir;touch.ax=e.clientX;touch.ay=e.clientY;
  // Keep moving while the finger rests after a swipe.
  clearTimeout(holdTimer);clearInterval(repeatTimer);
  holdTimer=setTimeout(()=>{repeatTimer=setInterval(()=>step(touch?.dir||dir),150);},480);
});
function endTouch(e){
  if(!touch)return;clearTimeout(holdTimer);clearInterval(repeatTimer);
  if(!touch.long){
    if(navMode==='pad'&&!touch.moved&&performance.now()-touch.t<LONG_PRESS_MS){showRipple(e.clientX,e.clientY);step('ok');}
    else if(navMode==='dpad'&&touch.dir==='ok'){step('ok');}
  }
  touch=null;
}
pad.addEventListener('pointerup',endTouch);
pad.addEventListener('pointercancel',()=>{clearTimeout(holdTimer);clearInterval(repeatTimer);touch=null;lit('');});

// iOS overlays the keyboard on a fixed layout; lift sheets above it using the
// visual viewport so the text field and Send stay visible while typing.
function fitKeyboard(){
  const vv=window.visualViewport;if(!vv)return;
  const covered=Math.max(0,window.innerHeight-vv.height-vv.offsetTop);
  document.documentElement.style.setProperty('--kb',covered+'px');
  document.documentElement.style.setProperty('--vvh',vv.height+'px');
  if(covered>0)window.scrollTo(0,0);
}
window.visualViewport?.addEventListener('resize',fitKeyboard);
window.visualViewport?.addEventListener('scroll',fitKeyboard);
// Fit the square pad to whatever height is left on this phone.
function fitPad(){const wrap=pad.parentElement,hint=$('padHint');const h=wrap.clientHeight-(hint.offsetParent?hint.offsetHeight+10:0)-24;pad.style.setProperty('--pad-size',Math.max(180,Math.min(wrap.clientWidth,h))+'px');}
addEventListener('resize',fitPad);

// ── Lifecycle ──
// Block page gestures (pull-to-refresh, rubber-band) outside sheets that scroll.
document.addEventListener('touchmove',e=>{if(!e.target.closest('.sheet'))e.preventDefault();},{passive:false});
document.addEventListener('gesturestart',e=>e.preventDefault());
document.addEventListener('dblclick',e=>e.preventDefault(),{passive:false});
addEventListener('blur',stopHold);addEventListener('pagehide',stopHold);
document.addEventListener('visibilitychange',()=>{stopHold();if(!document.hidden)poll();});
addEventListener('keydown',event=>{if(['INPUT','TEXTAREA'].includes(event.target.tagName))return;const key={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right',Enter:'ok',Escape:'back',Backspace:'back',' ':'playPause'}[event.key];if(key){event.preventDefault();sendKey(key);}});
// ── Pairing from the QR code and Home Screen install ──
// The TV's QR code carries its current code (?pair=123456), so scanning pairs
// at once. Each pairing gives this phone its own connection and the TV a new
// code, so the code is never kept in a URL. An iOS Home Screen web app has its
// own cookies: after pairing, the page reloads with a one-time install token
// (?install=…) that the TV also puts in the manifest's start URL, and the
// Home Screen app trades it for its own connection on first launch.
const params=new URLSearchParams(location.search);
let pairPin=params.get('pair');
if(pairPin&&!/^[0-9]{6}$/.test(pairPin))pairPin=null;
const installToken=/^[A-Za-z0-9_-]{43}$/.test(params.get('install')||'')?params.get('install'):null;
// Reload in Safari so the manifest carries the install token; returns true when reloading.
function afterPair(result){
  if(!standalone&&result?.install){location.replace('/?install='+encodeURIComponent(result.install));return true;}
  if(pairPin)history.replaceState(null,'','/');
  return false;
}
async function autoPair(){
  try{
    if(installToken){await api('install',{token:installToken});authenticated=true;return true;}
    if(pairPin){const result=await api('pair',{pin:pairPin});authenticated=true;afterPair(result);return true;}
  }catch(e){feedback(e.message);}
  return false;
}
function offerInstall(){
  if(standalone||store.get('glass.installOffered','')==='1')return;
  store.set('glass.installOffered','1');openSheet('installPanel');
}
$('openInstall').addEventListener('click',()=>openSheet('installPanel'));
$('installLater').addEventListener('click',closeSheets);
$('shareNow').addEventListener('click',async()=>{
  // The Web Share sheet is Safari's own; if it lacks Add to Home Screen,
  // the toolbar Share button has it.
  try{await navigator.share({title:'Mirage Remote',url:location.href});}
  catch(e){if(e?.name!=='AbortError')feedback('Use the Share button in Safari’s toolbar.');}
});
if(!navigator.share)$('shareNow').hidden=true;

paintSettings();fitPad();render();
(async()=>{
  try{await api('status');authenticated=true;if(pairPin)history.replaceState(null,'','/');}
  catch{if(!authenticated)await autoPair();}
  poll();
})();
