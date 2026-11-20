#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

struct TcpTraceEntry {
  uint32_t sequence;
  char time[20];
  char direction;
  char detail[24];
};

template <size_t Capacity>
struct TcpTraceBuffer {
  TcpTraceEntry entries[Capacity]{};
  size_t count = 0;
  size_t next = 0;

  void add(const TcpTraceEntry &entry) {
    entries[next] = entry;
    next = (next + 1) % Capacity;
    if (count < Capacity) ++count;
  }

  const TcpTraceEntry &newest(size_t index) const {
    return entries[(next + Capacity - 1 - index) % Capacity];
  }
};
