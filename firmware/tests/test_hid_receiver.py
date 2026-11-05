"""Exercise actual receiver/bond/storage/selection functions with deterministic IO.

OpenSSL supplies AES on the host; the sketch uses mbedTLS on ESP32. The address
resolution vector is Bluetooth Core Vol 3 Part H, Appendix D.7 (ah).
"""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]/'HID_Coexistence_Probe'
s=(root/'HID_Coexistence_Probe.ino').read_text()
def function(name):
    import re
    m=re.search(r'static [^\n]+\b'+name+r'\([^\n]*\) \{',s)
    assert m,name
    start=m.start();end=s.index('{',m.start())+1;depth=1
    while depth:
        depth+=(s[end]=='{')-(s[end]=='}');end+=1
    return s[start:end]
code=r'''
#include "ProbeSessions.h"
#include "ProbeKeyboard.h"
#include <openssl/evp.h>
#include <array>
#include <vector>
#include <string>
#include <functional>
#include <cstdio>
#include <cstdlib>
using namespace probe;
static unsigned checks=0;
#define CHECK(x) do{++checks;if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);exit(1);}}while(0)
#define portENTER_CRITICAL(x) ((void)(x))
#define portEXIT_CRITICAL(x) ((void)(x))
using String=std::string;
static uint32_t clockMs=0;
uint32_t millis(){return clockMs;}
static Sessions peers;
static Keyboard keyboard;
static int peerMux;
static auto snapshot(){return peers;}
static bool pairWindow=false, pairFromUsb=true, unbindAfterRelease=false;
static uint32_t pairStarted=0,lastIdentityCheck=0;
static constexpr uint32_t PAIR_WINDOW_MS=60000;
static Token pairOwner;
static ReceiverIdentity savedReceiver;
static OutputFormat outputFormat;
static bool receiverStorageReady=false;
static std::vector<std::string> replies;
void reply(Token,bool,bool,const String &text){replies.push_back(text);}
String receiverText(){return "RECEIVER_STATUS";}
struct {void println(const char*){} template<class... T> void printf(const char*,T...){} } Serial;
struct Preferences {
  std::vector<uint8_t> bytes;
  std::string format;
  bool fail=false;
  std::function<void()> duringWrite;
  bool begin(const char*,bool){return !fail;}
  size_t getBytesLength(const char*){return bytes.size();}
  size_t getBytes(const char*,void *out,size_t n){if(fail)return 0;if(n!=bytes.size())return 0;memcpy(out,bytes.data(),n);return n;}
  size_t putBytes(const char*,const void*data,size_t n){if(fail)return 0;bytes.assign((const uint8_t*)data,(const uint8_t*)data+n);if(duringWrite)duringWrite();return n;}
  String getString(const char*,const char*){return format;}
  size_t putString(const char*,const char*text){if(fail)return 0;format=text;return format.size();}
  bool isKey(const char*key){return std::string(key)=="receiver" ? !bytes.empty() : !format.empty();}
  bool remove(const char*){if(fail)return false;bytes.clear();return true;}
} receiverPreferences;
constexpr int ESP_OK=0, ESP_BLE_ID_KEY_MASK=2;
struct esp_ble_bond_dev_t {
 uint8_t bd_addr[6]={},bd_addr_type=0;
 struct {uint8_t key_mask=0;struct {uint8_t static_addr[6]={},addr_type=0,irk[16]={};} pid_key;} bond_key;
};
static std::vector<esp_ble_bond_dev_t> bonds;
int esp_ble_get_bond_device_num(){return int(bonds.size());}
int esp_ble_get_bond_device_list(int *count,esp_ble_bond_dev_t *out){for(int i=0;i<*count;++i)out[i]=bonds[i];return ESP_OK;}
static bool aesIdentity(const uint8_t *key,const uint8_t *input,uint8_t *output){
 EVP_CIPHER_CTX *ctx=EVP_CIPHER_CTX_new();if(!ctx)return false;int n=0;
 bool ok=EVP_EncryptInit_ex(ctx,EVP_aes_128_ecb(),nullptr,key,nullptr)==1 &&
   EVP_CIPHER_CTX_set_padding(ctx,0)==1 && EVP_EncryptUpdate(ctx,output,&n,input,16)==1 && n==16;
 EVP_CIPHER_CTX_free(ctx);return ok;
}
// FUNCTIONS
static const uint8_t rpa[6]={0x70,0x81,0x94,0x0d,0xfb,0xaa};
static esp_ble_bond_dev_t bond(){
 esp_ble_bond_dev_t b;uint8_t identity[6]={0xc1,2,3,4,5,6};memcpy(b.bd_addr,identity,6);b.bd_addr_type=1;
 b.bond_key.key_mask=2;b.bond_key.pid_key.addr_type=1;memcpy(b.bond_key.pid_key.static_addr,identity,6);
 // Native little-endian representation of Core D.7 IRK ec0234...397d9b.
 const uint8_t key[16]={0x9b,0x7d,0x39,0x0a,0xa6,0x10,0x10,0x34,0x05,0xad,0xc8,0x57,0xa3,0x34,0x02,0xec};
 memcpy(b.bond_key.pid_key.irk,key,16);return b;
}
static Token peer(uint16_t id,const uint8_t *addr,bool secure=true){auto t=peers.connect(id,addr);auto *p=peers.find(t);p->secure=secure;p->addressType=1;p->reportNotify=true;return t;}
static void tick(){clockMs+=250;receiverTick();}
static void reset(){peers={};keyboard={};pairWindow=false;unbindAfterRelease=false;pairFromUsb=true;pairOwner={};clockMs=0;pairStarted=0;lastIdentityCheck=0;savedReceiver={};receiverStorageReady=true;receiverPreferences={};bonds={bond()};replies.clear();}
int main(){
 reset();auto expected=bondIdentity(bonds[0]);CHECK(stableIdentity(expected));
 CHECK(resolvesRpa(expected,rpa,aesIdentity));
 uint8_t wrong[6];memcpy(wrong,rpa,6);wrong[5]^=1;CHECK(!resolvesRpa(expected,wrong,aesIdentity));
 wrong[0]=0xf0;CHECK(!resolvesRpa(expected,wrong,aesIdentity));
 auto changed=expected;changed.irk[0]^=1;CHECK(!resolvesRpa(changed,rpa,aesIdentity));
 CHECK(!resolvesRpa(expected,rpa,[](auto,auto,auto){return false;}));
 expected.subscriptionsKnown=true;expected.subscriptions=1;
 uint8_t encoded[receiverRecordSize];CHECK(encodeReceiver(expected,encoded));CHECK(sameIdentity(expected,decodeReceiver(encoded,sizeof(encoded))));
 CHECK(!decodeReceiver(encoded,sizeof(encoded)-1).valid);encoded[1]=9;CHECK(!decodeReceiver(encoded,sizeof(encoded)).valid);
 CHECK(!sameIdentity(expected,changed));
 // Initial explicit assignment, then a simulated reboot with flash retained.
 auto t=peer(0,rpa);tick();CHECK(peers.find(t)->identity.valid);CHECK(peers.role(Role::Keyboard).id==kNoConnection);
 pairWindow=true;pairTick();CHECK(!pairWindow);CHECK(peers.keyboardReady(t));CHECK(sameIdentity(savedReceiver,expected));CHECK(!receiverPreferences.bytes.empty());
 savedReceiver={};peers={};lastIdentityCheck=0;loadReceiver();CHECK(sameIdentity(savedReceiver,expected));
 t=peer(4,rpa,false);peers.find(t)->reportNotify=false;tick();CHECK(!peers.keyboardReady(t));
 // Bonded host sends no CCCD write after reboot: recover the confirmed selection.
 peers.find(t)->secure=true;tick();CHECK(peers.keyboardReady(t));CHECK(peers.find(t)->reportNotify);
 CHECK(!peers.find(t)->reportSeen);CHECK(!peers.authorized(t));
 // A fresh explicit disable overrides remembered report=1 and survives reboot.
 peers.find(t)->reportSeen=true;peers.find(t)->reportNotify=false;tick();CHECK(!peers.keyboardReady(t));
 CHECK(savedReceiver.subscriptions==0);CHECK(decodeReceiver(receiverPreferences.bytes.data(),receiverPreferences.bytes.size()).subscriptions==0);
 peers={};t=peer(0,rpa);peers.find(t)->reportNotify=false;tick();CHECK(!peers.keyboardReady(t));
 // Boot-only subscription also persists independently of the report CCCD.
 peers.find(t)->bootSeen=true;peers.find(t)->bootNotify=true;peers.find(t)->reportProtocol=false;tick();
 CHECK(savedReceiver.subscriptions==2);CHECK(peers.keyboardReady(t));CHECK(!peers.find(t)->reportNotify);
 peers.find(t)->reportSeen=true;peers.find(t)->reportNotify=true;peers.find(t)->reportProtocol=true;tick();CHECK(peers.keyboardReady(t));
 // Maintenance first: its authorization survives restoration of the receiver.
 peers={};uint8_t appAddr[6]={0xc2,2,3,4,5,7};auto app=peer(1,appAddr);peers.find(app)->uartNotify=true;
 CHECK(peers.claimMaintenance(app));CHECK(peers.authorize(app,true));t=peer(2,rpa);tick();CHECK(peers.keyboardReady(t));CHECK(peers.authorized(app));
 CHECK(!peers.claimMaintenance(t));CHECK(peers.uartReady(app));
 // Cached HID settings never enable the maintenance channel's HID subscriptions.
 peers.find(app)->identity=savedReceiver;peers.find(app)->reportNotify=false;peers.find(app)->bootNotify=false;
 CHECK(!peers.restoreSubscriptions(app,savedReceiver));CHECK(!peers.find(app)->reportNotify);CHECK(!peers.find(app)->bootNotify);
 // A failed persistence update must not overwrite an explicit disable in RAM.
 peers.find(t)->reportSeen=true;peers.find(t)->reportNotify=false;peers.find(t)->bootSeen=true;peers.find(t)->bootNotify=false;receiverPreferences.fail=true;tick();
 CHECK(!peers.keyboardReady(t));CHECK(!peers.find(t)->reportNotify);
 receiverPreferences.fail=false;tick();CHECK(savedReceiver.subscriptions==0);
 // A secure stranger, missing bond or replacement IRK must never inherit HID.
 peers={};t=peer(1,appAddr);tick();CHECK(!peers.keyboardReady(t));
 peers={};bonds.clear();t=peer(1,rpa);tick();CHECK(!peers.keyboardReady(t));
 peers={};bonds={bond()};bonds[0].bond_key.pid_key.irk[0]^=1;t=peer(1,bonds[0].bd_addr);tick();CHECK(peers.find(t)->identity.valid);CHECK(!peers.keyboardReady(t));
 // Without PID only a public/static identity may be retained, not an RPA.
 auto noPid=bond();noPid.bond_key.key_mask=0;CHECK(bondIdentity(noPid).valid);memcpy(noPid.bd_addr,rpa,6);CHECK(!bondIdentity(noPid).valid);
 // Storage failure must not grant a role or replace the previous selection.
 reset();t=peer(0,rpa);tick();receiverPreferences.fail=true;pairWindow=true;pairTick();CHECK(!peers.keyboardReady(t));CHECK(!savedReceiver.valid);CHECK(replies.back()=="ERR=HID_STORAGE");
 // Two eligible candidates are rejected rather than selecting the first.
 reset();t=peer(0,rpa);tick();peer(1,appAddr); // Even a candidate whose identity is still pending is ambiguous.
 pairWindow=true;pairTick();CHECK(!savedReceiver.valid);CHECK(replies.back()=="ERR=HID_AMBIGUOUS");
 // Expired and revoked windows cannot assign.
 reset();t=peer(0,rpa);tick();pairWindow=true;clockMs=60000;pairTick();CHECK(!savedReceiver.valid);CHECK(!pairWindow);
 pairWindow=true;pairStarted=clockMs;pairFromUsb=false;pairOwner={5,9};pairTick();CHECK(!savedReceiver.valid);
 // Flash write race: reused connection id cannot inherit an earlier token.
 reset();t=peer(0,rpa);tick();receiverPreferences.duringWrite=[](){peers.disconnect(0);peer(0,(uint8_t[6]){0xc3,2,3,4,5,8});};
 pairWindow=true;pairTick();CHECK(peers.role(Role::Keyboard).id==kNoConnection);CHECK(replies.back()=="ERR=HID_DISCONNECTED_AFTER_SAVE");
 // Forget is explicit and does not remove native Bluetooth bonds.
 receiverPreferences.duringWrite={};receiverPreferences.fail=true;CHECK(!forgetReceiver());CHECK(savedReceiver.valid);
 receiverPreferences.fail=false;CHECK(forgetReceiver());CHECK(!savedReceiver.valid);CHECK(clearedReceiverRecord(receiverPreferences.bytes.data(),receiverPreferences.bytes.size()));CHECK(bonds.size()==1);
 loadReceiver();CHECK(!savedReceiver.valid);
 peers={};t=peer(0,rpa);tick();CHECK(!peers.keyboardReady(t));
 // Legacy v1 carries identity but no subscription evidence: never guess enabled.
 reset();expected=bondIdentity(bonds[0]);expected.subscriptionsKnown=true;expected.subscriptions=3;
 CHECK(encodeReceiver(expected,encoded));uint8_t legacyBytes[26];memcpy(legacyBytes,encoded,26);legacyBytes[1]=1;
 receiverPreferences.bytes.assign(legacyBytes,legacyBytes+26);loadReceiver();CHECK(savedReceiver.valid);CHECK(!savedReceiver.subscriptionsKnown);
 t=peer(0,rpa);peers.find(t)->reportNotify=false;tick();CHECK(!peers.keyboardReady(t));CHECK(!peers.find(t)->bootNotify);
 peers.find(t)->reportSeen=true;peers.find(t)->reportNotify=true;tick();CHECK(peers.keyboardReady(t));
 CHECK(receiverPreferences.bytes.size()==27);CHECK(receiverPreferences.bytes[1]==2);
 CHECK(savedReceiver.subscriptionsKnown);CHECK(savedReceiver.subscriptions==1);
 // Corrupt/unknown CCCD masks cannot become an enabled subscription.
 CHECK(encodeReceiver(expected,encoded));encoded[26]=4;CHECK(!decodeReceiver(encoded,sizeof(encoded)).valid);
 expected.subscriptions=4;CHECK(!encodeReceiver(expected,encoded));
 uint8_t legacyEmpty[26]={};legacyEmpty[0]=0x48;legacyEmpty[1]=1;legacyEmpty[2]=0xff;
 CHECK(clearedReceiverRecord(legacyEmpty,sizeof(legacyEmpty)));
 // Corrupt storage fails closed, with no automatic assignment.
 receiverPreferences.bytes={1,2,3};loadReceiver();CHECK(!savedReceiver.valid);
 // Output format has its own key and commits only after successful persistence.
 reset();outputFormat={};OutputFormat changedFormat;changedFormat.fields=2;changedFormat.ending='\t';changedFormat.layout=KeyLayout::ES;
 CHECK(saveOutputFormat(changedFormat));CHECK(outputFormat.fields==2);
 outputFormat={};loadReceiver();CHECK(outputFormat.fields==2);CHECK(outputFormat.ending=='\t');CHECK(outputFormat.layout==KeyLayout::ES);
 auto before=outputWire(outputFormat);receiverPreferences.fail=true;changedFormat.fields=1;
 CHECK(!saveOutputFormat(changedFormat));CHECK(outputWire(outputFormat)==before);
 receiverPreferences.fail=false;receiverPreferences.format="HIDCFG=1;FIELDS=broken";loadReceiver();CHECK(outputWire(outputFormat)==outputWire(OutputFormat{}));
 receiverPreferences.format="";loadReceiver();CHECK(outputWire(outputFormat)==outputWire(OutputFormat{}));
 CHECK(!savedReceiver.valid);
 printf("PASS: %u receiver identity/persistence/reconnection/security assertions\n",checks);
}
'''
# Replace non-standard temporary C array in the race test.
code=code.replace('peer(0,(uint8_t[6]){0xc3,2,3,4,5,8});','const uint8_t other[6]={0xc3,2,3,4,5,8};peer(0,other);')
code=code.replace('// FUNCTIONS','\n'.join(function(n) for n in ('bondIdentity','authenticatedIdentity','loadOutputFormat','saveOutputFormat','loadReceiver','saveReceiver','forgetReceiver','receiverTick','pairTick')))
with tempfile.TemporaryDirectory(prefix='hid_receiver_') as tmp:
 cpp=Path(tmp)/'test.cpp';exe=Path(tmp)/'test';cpp.write_text(code)
 subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-omit-frame-pointer','-I',str(root),str(cpp),'-lcrypto','-o',str(exe)],check=True)
 env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0',UBSAN_OPTIONS='halt_on_error=1')
 subprocess.run([str(exe)],check=True,env=env)
