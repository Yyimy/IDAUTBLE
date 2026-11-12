#pragma once

#include <stddef.h>
#include <stdint.h>

class TcpMonitor {
 public:
  static constexpr uint8_t capacity = 5;
  static constexpr uint8_t maxReplyBytes = 24;

  struct Reply {
    uint32_t at;
    char text[maxReplyBytes];
    uint8_t length;
    bool truncated;
  };

  void connected(uint32_t now) {
    up = true;
    hasConnected = true;
    connectedAt = now;
  }

  bool disconnected(uint32_t now, const char *reason) {
    if (!up) return false;
    up = false;
    disconnectedAt = now;
    disconnectReason = reason;
    return true;
  }

  void receive(uint32_t now, const char *text, size_t length) {
    if (!length) return;
    Reply &reply = replies[next];
    reply.at = now;
    reply.truncated = length > maxReplyBytes;
    reply.length = reply.truncated ? maxReplyBytes : length;
    for (uint8_t i = 0; i < reply.length; ++i) reply.text[i] = text[i];
    next = (next + 1) % capacity;
    if (count < capacity) ++count;
  }

  const Reply &newest(uint8_t index) const {
    return replies[(next + capacity - 1 - index) % capacity];
  }

  uint8_t count = 0;
  bool hasConnected = false;
  uint32_t connectedAt = 0;
  uint32_t disconnectedAt = 0;
  const char *disconnectReason = nullptr;

 private:
  Reply replies[capacity]{};
  uint8_t next = 0;
  bool up = false;
};
