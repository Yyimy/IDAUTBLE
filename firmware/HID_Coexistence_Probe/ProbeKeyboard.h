#pragma once
#include "ProbeSessions.h"

namespace probe {
static constexpr char kTestText[] = "PALLET123\tLOC01\n";
enum class SendResult : uint8_t { Accepted, Retry, Failed };
enum class TypingResult : uint8_t { None, Done, Cancelled, Failed };

inline bool asciiReport(char c, bool capsLock, uint8_t (&report)[8]) {
  std::memset(report, 0, sizeof(report));
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
  else return false;
  return true;
}

// Exactly one fixed test, press/release at 30 ms intervals; never replay a text.
class Keyboard {
 public:
  bool busy() const { return phase_ != Phase::Idle; }
  Token owner() const { return owner_; }
  bool usbOwner() const { return usb_; }
  bool start(Token host, Token owner, bool usb, uint32_t now, const Sessions &s) {
    if (busy() || !s.keyboardReady(host) || (!usb && !s.authorized(owner))) return false;
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
      if (!asciiReport(kTestText[index_], s.find(host_)->capsLock, report)) { abort(TypingResult::Failed); return; }
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
      if (!kTestText[++index_]) finish(TypingResult::Done);
      else phase_ = Phase::Press;
    }
  }
 private:
  enum class Phase : uint8_t { Idle, InitialRelease, Press, Release, Aborting };
  Phase phase_ = Phase::Idle;
  Token host_, owner_;
  bool usb_ = false, pressed_ = false;
  size_t index_ = 0;
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
