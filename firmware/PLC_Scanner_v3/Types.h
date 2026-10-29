#pragma once
#include <Arduino.h>

enum LedMode : uint8_t { LED_OK = 0, LED_ERROR, LED_LOCAL_BLE, LED_ISOLATED };

enum BleCmdType : uint8_t {
  BLECMD_NONE = 0,

  // Binarios
  BLECMD_L,
  BLECMD_B,
  BLECMD_A,
  BLECMD_P,
  BLECMD_D,
  BLECMD_I,
  BLECMD_C,
  BLECMD_O,

  // Texto
  BLECMD_RTC_SET,
  BLECMD_CFG_GET,
  BLECMD_CFG_SET,
  BLECMD_NET_GET,
  BLECMD_NET_SET,
  BLECMD_NETSTAT_GET,
  BLECMD_WIFI_GET,
  BLECMD_WIFI_SET,
  BLECMD_ALT_GET,
  BLECMD_ALT_SET,
  BLECMD_OTA_START,
  BLECMD_OTA_STATUS,
  BLECMD_OTA_ABORT,
  BLECMD_TEXT

};

struct BleCmd {
  BleCmdType type;
  uint8_t source;  // 0=BLE, 1=TCP
  uint32_t session;
  char payload[384];  // para RTC=... o CFG=...
};

enum NetMode { NETMODE_DHCP = 0, NETMODE_STATIC = 1 };

enum TcpConnState : uint8_t {
  TCP_IDLE = 0,    // no intentando
  TCP_WAIT_RETRY,  // esperando siguiente intento
  TCP_CONNECTING,  // haciendo connect() con timeout corto
  TCP_CONNECTED    // conectado y operativo
};

enum OtaState : uint8_t {
  OTA_IDLE = 0,
  OTA_CONNECT_WIFI,
  OTA_WAIT_PARAMS,
  OTA_HTTP_GET,
  OTA_FLASHING,
  OTA_VERIFY,
  OTA_DONE,
  OTA_FAIL
};

enum OutputIndex {
  O_LED_ESTADO = 0,
  O_RELE_MOTOR = 1,
  O_RESERVA2 = 2,
  O_RESERVA3 = 3,
  O_RESERVA4 = 4,
  O_RESERVA5 = 5,
  O_RESERVA6 = 6,
  O_RESERVA7 = 7
};

struct TxPacket {
  uint16_t len;
  uint32_t session;
  uint32_t eventId;
  uint8_t data[512];
};

struct ScanJob {
  BleCmdType type;
  bool event;
  char iso[20];
  char photo;
};

struct EventRecord {
  uint32_t id;
  uint16_t len;
  uint8_t frame[480];
};

struct PersistentOutbox {
  uint32_t magic;
  uint32_t nextId;
  uint8_t count;
  EventRecord items[8];
};
