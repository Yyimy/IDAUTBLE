#pragma once
#include <cstdint>
#include <cstring>
#include <string>
namespace probe {
enum class KeyLayout : uint8_t { US, ES };
struct OutputFormat {
  uint8_t fields=3; // 1 pallet, 2 location, 3 both
  uint8_t separator[32]={'\t'};
  uint8_t separatorLength=1;
  char ending='\n';
  KeyLayout layout=KeyLayout::US;
};
inline bool validOutputByte(unsigned char c) {return c=='\t'||c=='\n'||(c>=32&&c<=126);}
inline int outputHex(char c) {return c>='0'&&c<='9'?c-'0':c>='A'&&c<='F'?c-'A'+10:-1;}
inline std::string outputWire(const OutputFormat &f) {
  std::string s="HIDCFG=1;FIELDS=";s+=f.fields==1?"P":f.fields==2?"U":"PU";s+=";SEPHEX=";
  if(!f.separatorLength)s+='-';
  for(unsigned i=0;i<f.separatorLength;++i){s+="0123456789ABCDEF"[f.separator[i]>>4];s+="0123456789ABCDEF"[f.separator[i]&15];}
  s+=";END=";s+=f.ending=='\n'?"ENTER":f.ending=='\t'?"TAB":"NONE";
  s+=";LAYOUT=";s+=f.layout==KeyLayout::US?"US":"ES";return s;
}
inline bool parseOutputFormat(const std::string &s,OutputFormat &out) {
  if(s.size()>160||s.rfind("HIDCFG=1;",0)!=0)return false;
  OutputFormat f;unsigned seen=0;size_t start=9;
  while(start<s.size()) {
    size_t end=s.find(';',start);if(end==std::string::npos)end=s.size();
    auto field=s.substr(start,end-start);size_t split=field.find('=');if(split==std::string::npos)return false;
    auto key=field.substr(0,split),value=field.substr(split+1);unsigned bit=0;
    if(key=="FIELDS") {bit=1;if(value=="P")f.fields=1;else if(value=="U")f.fields=2;else if(value=="PU")f.fields=3;else return false;}
    else if(key=="SEPHEX") {bit=2;f.separatorLength=0;if(value!="-") {
      if(value.empty()||value.size()%2||value.size()>64)return false;
      for(size_t i=0;i<value.size();i+=2){int a=outputHex(value[i]),b=outputHex(value[i+1]);if(a<0||b<0)return false;
        auto c=uint8_t(a*16+b);if(!validOutputByte(c))return false;f.separator[f.separatorLength++]=c;}
    }}
    else if(key=="END") {bit=4;if(value=="ENTER")f.ending='\n';else if(value=="TAB")f.ending='\t';else if(value=="NONE")f.ending=0;else return false;}
    else if(key=="LAYOUT") {bit=8;if(value=="US")f.layout=KeyLayout::US;else if(value=="ES")f.layout=KeyLayout::ES;else return false;}
    else return false;
    if(seen&bit)return false;
    seen|=bit;
    if(end==s.size())break;
    start=end+1;if(start==s.size())return false;
  }
  if(seen!=15)return false;
  out=f;return true;
}
// A missing selected field rejects the whole reading; never substitute stale data.
inline bool formatReading(const OutputFormat &f,const char *pallet,const char *location,std::string &out) {
  out.clear();
  if(f.fields<1||f.fields>3||f.separatorLength>32||
      (f.ending && f.ending!='\n' && f.ending!='\t')||
      (f.layout!=KeyLayout::US && f.layout!=KeyLayout::ES))return false;
  for(unsigned i=0;i<f.separatorLength;++i)if(!validOutputByte(f.separator[i]))return false;
  const auto missing=[](const char *p){return !p||!*p||!std::strcmp(p,"-");};
  if(((f.fields&1)&&missing(pallet))||((f.fields&2)&&missing(location)))return false;
  std::string text;
  if(f.fields&1)text+=pallet;
  if(f.fields==3)text.append((const char*)f.separator,f.separatorLength);
  if(f.fields&2)text+=location;
  if(f.ending)text+=f.ending;
  if(text.size()>512)return false;
  for(unsigned char c:text)if(!validOutputByte(c))return false;
  out=text;return true;
}
} // namespace probe
