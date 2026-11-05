"""Actual format parser, bounded payload and keyboard reports; no radio claims."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]/'HID_Coexistence_Probe'
code=r'''
#include "ProbeKeyboard.h"
#include <cstdio>
#include <cstdlib>
#include <vector>
using namespace probe;
unsigned checks=0;
#define CHECK(x) do{++checks;if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}}while(0)
int main(){
 OutputFormat f;std::string text;
 CHECK(outputWire(f)=="HIDCFG=1;FIELDS=PU;SEPHEX=09;END=ENTER;LAYOUT=US");
 CHECK(formatReading(f,"PALLET123","LOC01",text));CHECK(text=="PALLET123\tLOC01\n");
 CHECK(!formatReading(f,"PALLET123",nullptr,text));CHECK(text.empty());
 CHECK(!formatReading(f,"-","LOC01",text));CHECK(text.empty());
 f.fields=1;CHECK(formatReading(f,"ABC",nullptr,text));CHECK(text=="ABC\n");
 f.fields=2;f.ending=0;CHECK(formatReading(f,nullptr,"XYZ",text));CHECK(text=="XYZ");
 const std::string cfg="HIDCFG=1;FIELDS=PU;SEPHEX=0A0A3B3D097C;END=TAB;LAYOUT=ES";
 CHECK(parseOutputFormat(cfg,f));CHECK(outputWire(f)==cfg);
 CHECK(formatReading(f,"A","B",text));CHECK(text=="A\n\n;=\t|B\t");
 for(auto suffix:{";",";END=NONE",";X=1"}){auto old=outputWire(f);CHECK(!parseOutputFormat(cfg+suffix,f));CHECK(outputWire(f)==old);}
 for(auto hex:{"00","0D","7F","84","0a","1","","41414141414141414141414141414141414141414141414141414141414141414141"})
  CHECK(!parseOutputFormat(std::string("HIDCFG=1;FIELDS=PU;SEPHEX=")+hex+";END=NONE;LAYOUT=US",f));
 CHECK(parseOutputFormat("HIDCFG=1;FIELDS=PU;SEPHEX=-;END=NONE;LAYOUT=US",f));
 CHECK(formatReading(f,"A","B",text));CHECK(text=="AB");
 std::string huge(513,'A');CHECK(!formatReading(f,huge.c_str(),"B",text));CHECK(text.empty());
 CHECK(!formatReading(f,"A\r","B",text));
 uint8_t report[8];
 for(auto layout:{KeyLayout::US,KeyLayout::ES})for(int c=32;c<=126;++c){CHECK(asciiReport(char(c),false,report,layout));CHECK(report[2]!=0);}
 CHECK(!asciiReport(0,false,report));CHECK(!asciiReport(127,false,report));
 CHECK(asciiReport('!',false,report));CHECK(report[0]==2&&report[2]==30);
 CHECK(asciiReport(')',false,report));CHECK(report[0]==2&&report[2]==39);
 CHECK(asciiReport('@',false,report,KeyLayout::ES));CHECK(report[0]==0x40&&report[2]==31);
 CHECK(asciiReport(';',false,report,KeyLayout::ES));CHECK(report[0]==2&&report[2]==54);
 Sessions s;uint8_t addr[6]={1,2,3,4,5,6};auto hid=s.connect(1,addr);s.secureAddress(addr,true);s.find(hid)->reportNotify=true;CHECK(s.bindKeyboard(hid));
 Keyboard k;CHECK(!k.startText(hid,{},true,0,s,"",KeyLayout::US));
 CHECK(!k.startText(hid,{},true,0,s,huge.c_str(),KeyLayout::US));CHECK(!k.busy());
 CHECK(!k.startText(hid,{},true,0,s,"x\r",KeyLayout::US));CHECK(!k.busy());
 CHECK(k.startText(hid,{},true,0,s,"^`\n\n",KeyLayout::ES));
 std::vector<unsigned> keys;
 for(unsigned now=0;now<1000;now+=30)k.tick(now,s,[&](Token target,const uint8_t(&r)[8]){CHECK(target==hid);if(r[2])keys.push_back(r[2]);return SendResult::Accepted;});
 CHECK(k.takeResult()==TypingResult::Done);CHECK(keys==std::vector<unsigned>({47,44,47,44,40,40}));CHECK(!k.busy());
 CHECK(k.startText(hid,{},true,2000,s,"\t\n",KeyLayout::US));k.tick(2000,s,[](Token,const uint8_t(&)[8]){return SendResult::Accepted;});
 k.tick(2030,s,[](Token,const uint8_t(&)[8]){return SendResult::Accepted;});k.cancel();
 k.tick(2060,s,[](Token,const uint8_t(&r)[8]){CHECK(!r[2]);return SendResult::Accepted;});CHECK(k.takeResult()==TypingResult::Cancelled);
 const std::string arrows="HIDCFG=1;FIELDS=PU;SEPHEX=8081828309;END=ENTER;LAYOUT=ES";
 CHECK(parseOutputFormat(arrows,f));CHECK(outputWire(f)==arrows);
 CHECK(formatReading(f,"A","B",text));CHECK(text=="A\x80\x81\x82\x83\tB\n");
 CHECK(k.startText(hid,{},true,3000,s,text.c_str(),f.layout));
 keys.clear();
 for(unsigned now=3000;now<4000;now+=30)k.tick(now,s,[&](Token,const uint8_t(&r)[8]){
   keys.push_back(r[2]);
   if(r[2]>=0x4f&&r[2]<=0x52)CHECK(r[0]==0);
   return SendResult::Accepted;
 });
 CHECK(k.takeResult()==TypingResult::Done);
 CHECK(keys==std::vector<unsigned>({0,4,0,0x4f,0,0x50,0,0x51,0,0x52,0,43,0,5,0,40,0}));
 CHECK(!formatReading(f,"\x82","B",text));CHECK(text.empty()); // Barcode bytes are never interpreted as arrows.
 printf("PASS: %u HID format/payload/report assertions\n",checks);
}
'''
for sketch in (root, root.parent / 'PLC_Scanner_v3'):
 with tempfile.TemporaryDirectory(prefix='hid_format_') as tmp:
  cpp=Path(tmp)/'test.cpp';exe=Path(tmp)/'test';cpp.write_text(code)
  subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-omit-frame-pointer','-I',str(sketch),str(cpp),'-o',str(exe)],check=True)
  subprocess.run([str(exe)],check=True,env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0',UBSAN_OPTIONS='halt_on_error=1'))
# The firmware routes the setter after the maintenance gate and refuses mid-text updates.
s=(root/'HID_Coexistence_Probe.ino').read_text()
process=s[s.index('static void processCommand'):s.index('static void serialTick')]
assert process.index('!s.authorized(command.source)') < process.index('text.startsWith("HIDCFG=")')
assert process.index('text == "HIDCFG?"') < process.index('!s.authorized(command.source)')
setter=process[process.index('text.startsWith("HIDCFG=")'):process.index('text == "HIDFORGET=1"')]
assert setter.index('keyboard.busy()') < setter.index('saveOutputFormat(candidate)')
assert 'ERR=HIDCFG_STORAGE' in setter and 'ERR=HIDCFG_INVALID' in setter
print('PASS: firmware format query/authentication/busy/storage routing')
