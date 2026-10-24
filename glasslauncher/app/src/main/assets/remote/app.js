'use strict';
const $=id=>document.getElementById(id);
let authenticated=false,connected=false,busy=false,pollTimer,lastStatus=null;
const feedback=text=>{$('feedback').textContent=text;};
async function api(path,data){
  const response=await fetch('/api/'+path,{method:data===undefined?'GET':'POST',headers:data===undefined?{}:{'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data),credentials:'same-origin',cache:'no-store'});
  let result;try{result=await response.json();}catch{throw new Error('The TV could not complete that request.');}
  if(response.status===401){authenticated=false;render();throw new Error('Enter the pairing code from Glass.');}
  if(!response.ok)throw new Error(result.error||'The TV could not complete that request.');return result;
}
function render(){
  $('pairPanel').hidden=authenticated;
  $('nativePanel').hidden=!authenticated||(lastStatus?.paired&&!lastStatus?.pairing);
  $('startNative').disabled=!!lastStatus?.pairing;
  $('remoteControls').querySelectorAll('button').forEach(b=>b.disabled=!connected);
  $('textForm').querySelectorAll('button').forEach(b=>b.disabled=!connected);
  document.querySelector('.status').classList.toggle('connected',connected);
  $('statusText').textContent=!authenticated?'Pair with your TV':connected?'Connected to your TV':lastStatus?.pairing?'Waiting for TV authorization':lastStatus?.paired?'TV controls disconnected':'One-time TV setup';
  $('reconnect').hidden=!authenticated||connected||!lastStatus?.paired;
}
async function poll(){
  clearTimeout(pollTimer);
  try{const wasConnected=connected;lastStatus=await api('status');authenticated=true;connected=lastStatus.connected;if(connected&&!wasConnected)feedback('');render();
    if(authenticated&&!connected&&lastStatus.message)$('nativeHint').textContent=lastStatus.message;
  }catch(error){connected=false;render();if(authenticated)feedback('TV is unreachable. Keep it awake and use the same Wi-Fi.');}
  pollTimer=setTimeout(poll,document.hidden?15000:2500);
}
async function action(fn){if(busy)return;busy=true;feedback('');try{await fn();}catch(e){feedback(e.message);}finally{busy=false;}}
$('pairForm').addEventListener('submit',event=>{event.preventDefault();action(async()=>{await api('pair',{pin:$('pin').value.trim()});$('pin').value='';authenticated=true;await poll();});});
$('startNative').addEventListener('click',()=>action(async()=>{await api('native/start',{});feedback('Approve the Android authorization on your TV.');setTimeout(poll,1500);}));
$('reconnect').addEventListener('click',()=>action(async()=>{await api('reconnect',{});setTimeout(poll,1000);}));
$('setupButton').addEventListener('click',()=>{if(!authenticated)$('pairPanel').hidden=false;else{$('nativePanel').hidden=!$('nativePanel').hidden;$('nativeHint').textContent=lastStatus?.message||'Connect TV controls once.';}});
function keyboard(open){$('remoteControls').hidden=open;$('keyboardPanel').hidden=!open;if(open){$('tvText').focus();$('keyboardPanel').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth',block:'center'});}else{$('tvText').blur();$('keyboardButton').focus();}}
$('keyboardButton').addEventListener('click',()=>keyboard(true));$('closeKeyboard').addEventListener('click',()=>keyboard(false));
$('textForm').addEventListener('submit',event=>{event.preventDefault();const text=$('tvText').value;if(!text)return;action(async()=>{await api('text',{text});$('tvText').value='';feedback('Text sent to the selected TV field.');$('tvText').focus();});});
let commandQueue=Promise.resolve(),queued=0;
function sendKey(key){if(!connected||queued>4)return;queued++;commandQueue=commandQueue.then(()=>api('key',{key})).catch(e=>feedback(e.message)).finally(()=>queued--);}
let holdTimer,repeatTimer,heldButton;
function stopHold(){clearTimeout(holdTimer);clearInterval(repeatTimer);heldButton?.classList.remove('pressed');heldButton=null;}
for(const button of document.querySelectorAll('[data-key]')){
  button.addEventListener('pointerdown',event=>{if(button.disabled)return;event.preventDefault();stopHold();heldButton=button;button.classList.add('pressed');button.setPointerCapture?.(event.pointerId);sendKey(button.dataset.key);
    if(['up','down','left','right','volumeUp','volumeDown','delete'].includes(button.dataset.key))holdTimer=setTimeout(()=>{repeatTimer=setInterval(()=>sendKey(button.dataset.key),160);},420);
  });
  button.addEventListener('pointerup',stopHold);button.addEventListener('pointercancel',stopHold);button.addEventListener('lostpointercapture',stopHold);
  button.addEventListener('click',event=>{if(event.detail===0)sendKey(button.dataset.key);});
}
window.addEventListener('blur',stopHold);window.addEventListener('pagehide',stopHold);
document.addEventListener('visibilitychange',()=>{stopHold();if(!document.hidden)poll();});
window.addEventListener('keydown',event=>{if(['INPUT','TEXTAREA'].includes(event.target.tagName))return;const key={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right',Enter:'ok',Escape:'back',' ':'playPause'}[event.key];if(key){event.preventDefault();sendKey(key);}});
render();poll();