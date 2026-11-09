#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "ProbeReceiver.h"

namespace probe {
static constexpr uint16_t kNoConnection = 0xffff;
enum class Role : uint8_t { Unassigned, Maintenance, Keyboard, Client };
struct Token {
  uint16_t id = kNoConnection;
  uint32_t generation = 0;
  bool operator==(const Token &other) const {
    return id == other.id && generation == other.generation;
  }
};
struct Peer {
  bool used = false;
  Token token;
  uint8_t address[6] = {};
  Role role = Role::Unassigned;
  bool secure = false;
  uint8_t addressType = 0xff;
  ReceiverIdentity identity;
  bool uartNotify = false;
  bool reportSeen = false, bootSeen = false;
  bool reportNotify = false;
  bool bootNotify = false;
  bool reportProtocol = true;
  bool suspended = false;
  bool congested = false;
  bool capsLock = false;
  bool authorized = false;
  uint8_t authFailures = 0;
  bool authLocked = false;
  uint16_t mtu = 23;
  uint32_t maintenanceActivityMs = 0;
  uint32_t unassignedSinceMs = 0;
  bool expiring = false;
};

// No BLE/global CCCD state: every capability belongs to a connection generation.
// Callers serialize accesses; the sketch uses a portMUX around this small table.
class Sessions {
 public:
  static constexpr size_t capacity = 2;
  Peer peers[capacity];

  Token connect(uint16_t id, const uint8_t *address, uint32_t now = 0) {
    if (id == kNoConnection || byId(id)) return {};
    for (const auto &p : peers) if (p.used && !std::memcmp(p.address, address, 6)) return {};
    for (auto &p : peers) {
      if (p.used) continue;
      p = Peer{};
      p.used = true;
      p.unassignedSinceMs = now;
      p.token = {id, ++generation_};
      if (!p.token.generation) p.token.generation = ++generation_;
      std::memcpy(p.address, address, 6);
      return p.token;
    }
    return {};
  }
  bool disconnect(uint16_t id) {
    auto *p = byId(id);
    if (!p) return false;
    *p = Peer{};
    return true;
  }
  size_t count() const {
    size_t n = 0;
    for (const auto &p : peers) n += p.used;
    return n;
  }
  Peer *byId(uint16_t id) {
    for (auto &p : peers) if (p.used && p.token.id == id) return &p;
    return nullptr;
  }
  Peer *find(Token token) {
    auto *p = byId(token.id);
    return p && p->token == token ? p : nullptr;
  }
  const Peer *find(Token token) const {
    for (const auto &p : peers) if (p.used && p.token == token) return &p;
    return nullptr;
  }
  Token role(Role role) const {
    for (const auto &p : peers) if (p.used && p.role == role) return p.token;
    return {};
  }
  bool secureAddress(const uint8_t *address, bool secure) {
    for (auto &p : peers) {
      if (!p.used || std::memcmp(p.address, address, 6)) continue;
      p.secure = secure;
      if (!secure) { p.authorized = false; p.identity = {}; }
      return true;
    }
    return false;
  }
  bool claimMaintenance(Token token) {
    auto *p = find(token);
    if (!p || p->expiring || !p->secure || !p->uartNotify || (p->role == Role::Keyboard || p->role == Role::Client)) return false;
    auto existing = role(Role::Maintenance);
    if (existing.id != kNoConnection && !(existing == token)) return false;
    p->role = Role::Maintenance;
    return true;
  }
  // Clients must opt in explicitly. A CCCD subscription grants no role.
  bool claimClient(Token token, bool fromKeyboard = false) {
    auto *p = find(token);
    const auto origin = fromKeyboard ? Role::Keyboard : Role::Unassigned;
    if (!p || p->expiring || !p->secure || !p->uartNotify || p->role != origin) return false;
    if (role(Role::Client).id != kNoConnection) return false;
    p->role = Role::Client;
    return true;
  }
  bool clientReady(Token token) const {
    const auto *p = find(token);
    return p && p->role == Role::Client && p->secure && p->uartNotify;
  }
  // Explicit admin action: a CCCD write by itself never becomes a keyboard role.
  bool bindKeyboard(Token token) {
    auto *p = find(token);
    if (!p || p->expiring || !p->secure || (p->role == Role::Maintenance || p->role == Role::Client) || !subscription(*p)) return false;
    auto existing = role(Role::Keyboard);
    if (existing.id != kNoConnection && !(existing == token)) return false;
    p->role = Role::Keyboard;
    return true;
  }
  // Restore only the selected authenticated identity. A fresh CCCD write, even
  // an explicit disable, always wins over remembered state for this connection.
  bool restoreSubscriptions(Token token, const ReceiverIdentity &saved) {
    auto *p = find(token);
    if (!p || !p->secure || (p->role == Role::Maintenance || p->role == Role::Client) || !saved.subscriptionsKnown ||
        !sameIdentity(p->identity, saved)) return false;
    if (!p->reportSeen) p->reportNotify = (saved.subscriptions & 1) != 0;
    if (!p->bootSeen) p->bootNotify = (saved.subscriptions & 2) != 0;
    return true;
  }
  bool resumeSavedKeyboard(Token token, const ReceiverIdentity &saved) {
    auto *p = find(token);
    if (!p || p->role != Role::Client || p->uartNotify || !p->secure ||
        !sameIdentity(p->identity, saved)) return false;
    p->role = Role::Unassigned;
    if (restoreSubscriptions(token, saved) && bindKeyboard(token)) return true;
    p->role = Role::Client;
    return false;
  }
  void unbindKeyboard() {
    for (auto &p : peers) if (p.role == Role::Keyboard) p.role = Role::Unassigned;
  }
  bool authorize(Token token, bool correct) {
    auto *p = find(token);
    if (!p || p->role != Role::Maintenance || !p->secure || p->authLocked) return false;
    if (correct) { p->authorized = true; p->authFailures = 0; return true; }
    p->authorized = false;
    if (++p->authFailures >= 3) p->authLocked = true;
    return false;
  }
  void revoke(Token token) { if (auto *p = find(token)) p->authorized = false; }
  bool authorized(Token token) const {
    auto *p = find(token);
    return p && p->role == Role::Maintenance && p->secure && p->authorized;
  }
  bool uartReady(Token token) const {
    auto *p = find(token);
    return p && p->role == Role::Maintenance && p->secure && p->uartNotify;
  }
  bool keyboardTarget(Token token) const {
    auto *p = find(token);
    return p && p->role == Role::Keyboard && p->secure && subscription(*p);
  }
  bool keyboardReady(Token token) const {
    auto *p = find(token);
    return keyboardTarget(token) && !p->suspended;
  }
 private:
  uint32_t generation_ = 0;
  static bool subscription(const Peer &p) {
    return p.reportProtocol ? p.reportNotify : p.bootNotify;
  }
};
}  // namespace probe
