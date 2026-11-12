#pragma once

#include <stdint.h>
#include <string.h>

struct DiagnosticHistory {
  static constexpr uint32_t format = 1;
  static constexpr uint8_t capacity = 8;
  struct Event {
    uint32_t boot;
    uint32_t seconds;
    char reason[24];
  };

  uint32_t version = format;
  uint32_t sequence = 0;
  uint8_t count = 0;
  uint8_t next = 0;
  Event events[capacity]{};

  bool valid() const {
    if (version != format || count > capacity || next >= capacity) return false;
    for (uint8_t i = 0; i < count; ++i) {
      const auto &event = newest(i);
      if (!memchr(event.reason, 0, sizeof(event.reason))) return false;
      for (const char *p = event.reason; *p; ++p) {
        if (!(*p >= 'A' && *p <= 'Z') && !(*p >= '0' && *p <= '9') && *p != '_') return false;
      }
    }
    return true;
  }

  void add(uint32_t boot, uint32_t seconds, const char *reason) {
    Event &event = events[next];
    event = {};
    event.boot = boot;
    event.seconds = seconds;
    strncpy(event.reason, reason, sizeof(event.reason) - 1);
    next = (next + 1) % capacity;
    if (count < capacity) ++count;
    ++sequence;
  }

  const Event &newest(uint8_t index) const {
    return events[(next + capacity - 1 - index) % capacity];
  }
};
