#pragma once

#include "../../HidOutputFormat.h"
#include <array>

namespace cc {
constexpr size_t maxCodes = 6;
constexpr uint16_t maxWaitMs = 60000;
constexpr uint8_t maxKeyBytes = 20;  // Keep a full settings write within the existing BLE command buffer.
constexpr uint16_t defaultWaitMs = 2000;
constexpr const char *configHeader = "HIDCCCFG=";

struct Keys {
  uint8_t bytes[32] = {};
  uint8_t count = 0;

  void appendTo(std::string &out) const {
    out.append(reinterpret_cast<const char *>(bytes), count);
  }
};

struct Gap {
  Keys keys{{'\n'}, 1};
  uint16_t waitMs = defaultWaitMs;
};

struct Config {
  Keys start;
  Keys end{{'\n'}, 1};
  std::array<Gap, maxCodes - 1> gaps;
};

inline bool parseWait(const std::string &text, uint16_t &wait) {
  if (text.empty() || text.size() > 5) return false;
  uint32_t parsed = 0;
  for (const char digit : text) {
    if (digit < '0' || digit > '9') return false;
    parsed = parsed * 10 + (digit - '0');
  }
  if (parsed > maxWaitMs) return false;
  wait = uint16_t(parsed);
  return true;
}

inline bool parseKeys(const std::string &text, Keys &keys) {
  return probe::parseOutputHex(text, keys.bytes, keys.count) && keys.count <= maxKeyBytes;
}

inline std::string wire(const Config &config) {
  std::string result = configHeader;
  probe::appendOutputHex(result, config.start.bytes, config.start.count);
  result += '|';
  probe::appendOutputHex(result, config.end.bytes, config.end.count);
  for (const auto &gap : config.gaps) {
    result += '|';
    probe::appendOutputHex(result, gap.keys.bytes, gap.keys.count);
    result += ',';
    result += std::to_string(gap.waitMs);
  }
  return result;
}

inline bool parseConfig(std::string value, Config &config) {
  if (value.rfind(configHeader, 0) != 0 || value.size() >= 382) return false;
  size_t start = std::strlen(configHeader);
  Config candidate;
  for (size_t index = 0; index < maxCodes + 1; ++index) {
    const size_t end = value.find('|', start);
    if ((end == std::string::npos) != (index == maxCodes)) return false;
    const auto part = value.substr(start, end - start);
    if (index < 2) {
      if (!parseKeys(part, index ? candidate.end : candidate.start)) return false;
    } else {
      const auto comma = part.find(',');
      auto &gap = candidate.gaps[index - 2];
      if (comma == std::string::npos || !parseKeys(part.substr(0, comma), gap.keys) ||
          !parseWait(part.substr(comma + 1), gap.waitMs)) return false;
    }
    start = end + 1;
  }
  config = candidate;
  return true;
}

struct Batch {
  std::array<std::string, maxCodes> segments;
  std::array<uint16_t, maxCodes - 1> waits = {};
  size_t count = 0;
};

inline bool buildBatch(const std::string &input, const Config &config, Batch &batch) {
  if (input.empty() || input.size() > 320) return false;
  Batch candidate;
  size_t start = 0;
  for (;;) {
    const auto separator = input.find(';', start);
    const auto code = input.substr(start, separator - start);
    if (candidate.count >= maxCodes || code.find_first_not_of(' ') == std::string::npos) return false;
    for (unsigned char c : code) if (c < 32 || c > 126) return false;

    const size_t index = candidate.count++;
    auto &segment = candidate.segments[index];
    if (index == 0) config.start.appendTo(segment);
    segment += code;
    if (separator == std::string::npos) {
      config.end.appendTo(segment);
      break;
    }
    if (candidate.count == maxCodes) return false;
    config.gaps[index].keys.appendTo(segment);
    candidate.waits[index] = config.gaps[index].waitMs;
    start = separator + 1;
  }
  batch = std::move(candidate);
  return true;
}
}  // namespace cc
