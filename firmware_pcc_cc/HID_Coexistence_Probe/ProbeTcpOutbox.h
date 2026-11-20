#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace probe {
enum class TcpFlush { Idle, Progress, Retry, Failed };
// Debug telemetry only: never replay queued output on a new TCP connection.
class TcpOutbox {
 public:
  static constexpr size_t capacity = 8, maxLine = 192, maxChunk = 128;
  static constexpr uint32_t stallMs = 5000;
  bool push(const uint8_t *data, size_t length, uint32_t now) {
    if (!length || length > maxLine || count_ == capacity) return false;
    auto &f = frames_[(head_ + count_) % capacity];
    f.length = length; f.offset = 0; std::memcpy(f.data, data, length);
    if (!count_) progressMs_ = now;
    ++count_; return true;
  }
  void clear() { head_ = count_ = 0; }
  size_t size() const { return count_; }
  // send returns bytes accepted, 0 for EAGAIN/EWOULDBLOCK/EINTR, -1 for fatal.
  template <typename Send>
  TcpFlush flush(uint32_t now, Send send) {
    if (!count_) return TcpFlush::Idle;
    if (uint32_t(now - progressMs_) >= stallMs) { clear(); return TcpFlush::Failed; }
    auto &f = frames_[head_];
    size_t remaining = f.length - f.offset;
    const size_t chunk = remaining < maxChunk ? remaining : maxChunk;
    const int written = send(f.data + f.offset, chunk);
    if (written < 0 || size_t(written) > chunk) { clear(); return TcpFlush::Failed; }
    if (!written) return TcpFlush::Retry;
    f.offset += written; progressMs_ = now;
    if (f.offset == f.length) { head_ = (head_ + 1) % capacity; --count_; }
    return TcpFlush::Progress;
  }
 private:
  struct Frame { size_t length = 0, offset = 0; uint8_t data[maxLine] = {}; };
  Frame frames_[capacity];
  size_t head_ = 0, count_ = 0;
  uint32_t progressMs_ = 0;
};
} // namespace probe
