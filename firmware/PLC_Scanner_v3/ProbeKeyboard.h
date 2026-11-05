#pragma once
#include "ProbeSessions.h"
#include "HidOutputFormat.h"

namespace probe {
static constexpr char kTestText[] = "PALLET123\tLOC01\n";
enum class SendResult : uint8_t { Accepted, Retry, Failed };
enum class TypingResult : uint8_t { None, Done, Cancelled, Failed };

inline bool asciiReport(char c, bool capsLock, uint8_t (&report)[8], KeyLayout layout=KeyLayout::US) {
  std::memset(report, 0, sizeof(report));
  const auto byte = static_cast<unsigned char>(c);
  if (isArrowSeparator(byte)) {
    report[2] = 0x4f + (byte - 0x80); // Keyboard Right Arrow through Keyboard Up Arrow.
    return true;
  }
  if (!validOutputByte(byte)) return false;
  if (c >= 'A' && c <= 'Z') {
    report[0] = capsLock ? 0 : 0x02;
    report[2] = 4 + c - 'A';
  } else if (c >= 'a' && c <= 'z') {
    report[0] = capsLock ? 0x02 : 0;
    report[2] = 4 + c - 'a';
  } else if (c >= '1' && c <= '9') report[2] = 30 + c - '1';
  else if (c == '0') report[2] = 39;
  else if (c == '\t') report[2] = 0x2b;
  else if (c == '\n') report[2] = 0x28;
  else if (c == ' ') report[2] = 0x2c;
  else {
    const char *plain="-=[]\\;'`,./";
    const char *shift="_+{}|:\"~<>?";
    const uint8_t keys[]={45,46,47,48,49,51,52,53,54,55,56};
    const char *symbols="!@#$%^&*()";
    const char *found=std::strchr(symbols,c);
    if(layout==KeyLayout::US && found){report[0]=2;report[2]=30+(found-symbols);}
    else if(layout==KeyLayout::US && (found=std::strchr(plain,c))){report[2]=keys[found-plain];}
    else if(layout==KeyLayout::US && (found=std::strchr(shift,c))){report[0]=2;report[2]=keys[found-shift];}
    else if(layout==KeyLayout::ES) {
      const char *unshift="'+,-.\\"; const uint8_t uk[]={45,48,54,56,55,53};
      const char *shifts="!\"$%&/()=?:;_*>^";const uint8_t sk[]={30,31,33,34,35,36,37,38,39,45,55,54,56,48,100,47};
      const char *alt="|@#~[]{}";const uint8_t ak[]={30,31,32,33,47,48,52,49};
      if((found=std::strchr(unshift,c))){report[2]=uk[found-unshift];if(c=='\\')report[0]=0x40;}
      else if(c=='<')report[2]=100;
      else if(c=='`')report[2]=47;
      else if((found=std::strchr(shifts,c))){report[0]=2;report[2]=sk[found-shifts];}
      else if((found=std::strchr(alt,c))){report[0]=0x40;report[2]=ak[found-alt];}
      else return false;
    } else return false;
  }
  return true;
}

// One bounded text at a time; press/release every 30 ms, never replay a text.
class Keyboard {
 public:
  bool busy() const { return phase_ != Phase::Idle; }
  Token owner() const { return owner_; }
  bool usbOwner() const { return usb_; }
  bool start(Token host, Token owner, bool usb, uint32_t now, const Sessions &s) {
    return startText(host,owner,usb,now,s,kTestText,KeyLayout::US);
  }
  bool startText(Token host,Token owner,bool usb,uint32_t now,const Sessions &s,const char *text,KeyLayout layout) {
    if (busy() || !text || !*text || (layout!=KeyLayout::US && layout!=KeyLayout::ES) || !s.keyboardReady(host) || (!usb && !s.authorized(owner))) return false;
    size_t used=0;
    for(size_t i=0;text[i];++i) {
      if(i>=512)return false;
      uint8_t report[8];if(!asciiReport(text[i],false,report,layout))return false;
      if(used+2>=sizeof(text_))return false;
      text_[used++]=text[i];
      if(layout==KeyLayout::ES && (text[i]=='^'||text[i]=='`'))text_[used++]=' '; // standard ES dead key completion
    }
    text_[used]=0;layout_=layout;
    host_ = host; owner_ = owner; usb_ = usb;
    index_ = 0; pressed_ = false; result_ = TypingResult::None;
    phase_ = Phase::InitialRelease;
    last_ = now - 30; retrySince_ = now;
    return true;
  }
  void cancel() { if (busy()) abort(TypingResult::Cancelled); }
  TypingResult takeResult() { auto r = result_; result_ = TypingResult::None; return r; }

  template <class Sender>
  void tick(uint32_t now, const Sessions &s, Sender send) {
    if (!busy()) return;
    if (!s.keyboardTarget(host_)) { finish(TypingResult::Cancelled); return; }
    if (!s.keyboardReady(host_) || (!usb_ && !s.authorized(owner_))) abort(TypingResult::Cancelled);
    if (!busy()) return;
    if (uint32_t(now - last_) < 30) return;
    uint8_t report[8] = {};
    if (phase_ == Phase::Press) {
      if (!asciiReport(text_[index_], s.find(host_)->capsLock, report, layout_)) { abort(TypingResult::Failed); return; }
    }
    const auto result = send(host_, report);
    if (result == SendResult::Retry) {
      if (uint32_t(now - retrySince_) >= 1500) {
        if (phase_ == Phase::Aborting) finish(abortResult_);
        else abort(TypingResult::Failed);
      }
      return;
    }
    if (result == SendResult::Failed) {
      if (phase_ == Phase::Aborting) finish(abortResult_);
      else abort(TypingResult::Failed);
      return;
    }
    last_ = retrySince_ = now;
    if (phase_ == Phase::Aborting) { finish(abortResult_); return; }
    if (phase_ == Phase::InitialRelease) phase_ = Phase::Press;
    else if (phase_ == Phase::Press) { pressed_ = true; phase_ = Phase::Release; }
    else {
      pressed_ = false;
      if (!text_[++index_]) finish(TypingResult::Done);
      else phase_ = Phase::Press;
    }
  }
 private:
  enum class Phase : uint8_t { Idle, InitialRelease, Press, Release, Aborting };
  Phase phase_ = Phase::Idle;
  Token host_, owner_;
  bool usb_ = false, pressed_ = false;
  size_t index_ = 0;
  char text_[1026]={};
  KeyLayout layout_=KeyLayout::US;
  uint32_t last_ = 0, retrySince_ = 0;
  TypingResult result_ = TypingResult::None, abortResult_ = TypingResult::Cancelled;
  void abort(TypingResult reason) {
    if (phase_ == Phase::Aborting) return;
    abortResult_ = reason;
    // An accepted keypress always gets a release attempt, never another keypress.
    if (pressed_) phase_ = Phase::Aborting;
    else finish(reason);
  }
  void finish(TypingResult result) { result_ = result; pressed_ = false; phase_ = Phase::Idle; }
};
}  // namespace probe
