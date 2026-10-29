// PLC Scanner v3.0.5-rc1 - 2026-10-29
// Complete source derived from the supplied firmware. See LEEME.md before
// flashing. Target: ESP32-S3, Arduino-ESP32 3.0.7, USB CDC enabled, OTA
// partition required. Existing V2 binary commands preserved. New optional
// features documented in LEEME.
#include <Adafruit_ADS1X15.h>
#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLESecurity.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <HTTPClient.h>
#include <HardwareSerial.h>
#include <Preferences.h>
#include <Update.h>
#include <WiFi.h>
#include <WiFiClient.h>
#include <Wire.h>
#include <errno.h>
#include <esp_system.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <lwip/sockets.h>
#include <math.h>
#include <stdarg.h>

#include <atomic>

#include "Types.h"

// ================= CONFIGURACION DEL PIN POR EL INSTALADOR ==================
// Cambia este valor ANTES de compilar. Exactamente 6 cifras, entre comillas.
// Se aplica siempre, incluso si la placa conserva un PIN anterior en NVS.
// Se usa para emparejar por BLE y para AUTH=<pin> en la app de mantenimiento.
static constexpr char PLC_MAINTENANCE_PIN[] = "123456";
static_assert(
    sizeof(PLC_MAINTENANCE_PIN) == 7 && PLC_MAINTENANCE_PIN[0] >= '0' &&
        PLC_MAINTENANCE_PIN[0] <= '9' && PLC_MAINTENANCE_PIN[1] >= '0' &&
        PLC_MAINTENANCE_PIN[1] <= '9' && PLC_MAINTENANCE_PIN[2] >= '0' &&
        PLC_MAINTENANCE_PIN[2] <= '9' && PLC_MAINTENANCE_PIN[3] >= '0' &&
        PLC_MAINTENANCE_PIN[3] <= '9' && PLC_MAINTENANCE_PIN[4] >= '0' &&
        PLC_MAINTENANCE_PIN[4] <= '9' && PLC_MAINTENANCE_PIN[5] >= '0' &&
        PLC_MAINTENANCE_PIN[5] <= '9',
    "PLC_MAINTENANCE_PIN debe contener exactamente 6 cifras entre comillas");
// ============================================================================

#include "mbedtls/sha256.h"

// ============================================================================
// HARDWARE / LIBS
// ============================================================================
Adafruit_ADS1115 ads;  // ADC externo para la batería
static bool gAdsOk = false;

// ============================================================================
// CONTADORES TÉCNICOS (PERSISTENTES EN NVS)
// ============================================================================
Preferences gPrefs;
static const char *NVS_NS_COUNTERS = "counters";

static uint32_t gBootCount = 0;
static uint32_t gPonTotal = 0;
static uint32_t gPalCargado = 0;
static uint32_t gPalDescargado = 0;
static uint32_t gLecturasFallidas = 0;

// ============================================================================
// CONFIGURACIÓN GENERAL
// ============================================================================
#define DEBUG_FOTO 0  // 1 = trazas masivas fotocélula (si las añades)

// TEST / DEBUG GENERAL
#define ENABLE_ADS_READS 1  // 0 = no leer ADS1115, 1 = leer ADS1115
#define ENABLE_POWER_CUT \
  1  // 0 = no cortar relé en pruebas, 1 = corte real activado

static unsigned long gBootMs = 0;
static const unsigned long STARTUP_WARMUP_MS =
    2000;  // (no usado ahora, reservado)

// ============================================================================
// LED ESTADO - un indicador, seis patrones, sin esperas.
// Prioridad: fallo/corte > bateria > OTA > conexion > preparado.
// WiFi habilitado exige TCP; sin WiFi se exige BLE cifrado y suscrito.
// ============================================================================

static LedMode gLedMode = LED_DISCONNECTED;
static uint32_t gLedT0 = 0;
static bool gLedOutOn = false;
// 0: normal, 1: baja (<=20%), 2: muy baja (<=10%).
static uint8_t gLedBatteryBand = 0;
static uint8_t gLedBatteryCandidate = 0;
static uint32_t gLedBatterySinceMs = 0;
static const uint32_t LED_BAT_CONFIRM_MS = 3000;
static const uint32_t LED_BAT_MAX_AGE_MS = 3000;

// ============================================================================
// BLE - GLOBALES
// ============================================================================
BLECharacteristic *pCharacteristic = nullptr;
std::atomic<bool> deviceConnected{false};
std::atomic<bool> isAdvertising{false};
std::atomic<unsigned long> lastAdvertiseCheck{0};
const unsigned long ADV_CHECK_MS = 10000;  // 10 s

// Comandos esperados (4 bytes)
const uint8_t CMD_L[4] = {0x1B, 0x4C, 0x0D, 0x0A};  // Lectura global
const uint8_t CMD_B[4] = {0x1B, 0x42, 0x0D, 0x0A};  // Nivel batería
const uint8_t CMD_A[4] = {0x1B, 0x41, 0x0D, 0x0A};  // Altura
const uint8_t CMD_P[4] = {0x1B, 0x50, 0x0D, 0x0A};  // Fotocélula
const uint8_t CMD_D[4] = {0x1B, 0x44, 0x0D, 0x0A};  // Palet
const uint8_t CMD_I[4] = {0x1B, 0x49, 0x0D, 0x0A};  // Ubicación
const uint8_t CMD_C[4] = {0x1B, 0x43, 0x0D, 0x0A};  // Conectado (handshake)
const uint8_t CMD_O[4] = {0x1B, 0x4F, 0x0D, 0x0A};  // Apagar relé motor

// ============================================================================
// BLE - EJECUCIÓN DIFERIDA (BUSY + 1 SLOT PENDING)
// ============================================================================
void sendFrame(const uint8_t *data, size_t len);

static void otaSendStatusLine(const String &line) {
  String s;
  s.reserve(120);
  s += (char)0x06;
  s += "OTA=" + line + "\r\n";
  sendFrame((const uint8_t *)s.c_str(), s.length());
}

static void otaSendErr(const String &err) {
  String s;
  s.reserve(160);
  s += (char)0x15;
  s += "OTA_ERR=" + err + "\r\n";
  sendFrame((const uint8_t *)s.c_str(), s.length());
}

static QueueHandle_t gCommandQueue = nullptr;
static std::atomic<uint32_t> gBleSession{1};

static inline void bleSendBusy() {
  // “busy” explícito. Android puede reintentar en 200-500ms.
  const uint8_t busyFrame[] = {0x15, 'B', 'U', 'S', 'Y', 0x0D, 0x0A};
  sendFrame(busyFrame, sizeof(busyFrame));
}

static inline void bleSendConnectedAck() {
  const uint8_t frame[] = {0x06, 'C', 'O', 'N', 'E',  'C',
                           'T',  'A', 'D', 'O', 0x0D, 0x0A};

  sendFrame(frame, sizeof(frame));
}

static inline void bleSendOff() {
  const uint8_t offframe[] = {0x06, 'O', 'F', 'F', 0x0D, 0x0A};
  sendFrame(offframe, sizeof(offframe));
}

static inline BleCmdType cmdFromBinary4(const uint8_t rx[4]) {
  if (matchCommand(rx, CMD_L)) return BLECMD_L;
  if (matchCommand(rx, CMD_B)) return BLECMD_B;
  if (matchCommand(rx, CMD_A)) return BLECMD_A;
  if (matchCommand(rx, CMD_P)) return BLECMD_P;
  if (matchCommand(rx, CMD_D)) return BLECMD_D;
  if (matchCommand(rx, CMD_I)) return BLECMD_I;
  if (matchCommand(rx, CMD_C)) return BLECMD_C;
  if (matchCommand(rx, CMD_O)) return BLECMD_O;
  return BLECMD_NONE;
}

// ============================================================================
// VARIABLES DE DATOS
// ============================================================================
String gId = "";  // Identificador configurable, 1..20 caracteres ASCII.
static String gHardwareId;  // Identificador tecnico inmutable, 12 HEX.
static String gBluetoothName;
static bool gCustomId = false;

// ============================================================================
// CONFIGURACIÓN RED (WiFi + TCP)
// ============================================================================

static const char *NVS_NS_NET = "net";

static NetMode gNetMode = NETMODE_DHCP;

static String gNetSsid = "";
static String gNetPwd = "";

static String gNetIp = "192.168.0.213";
static String gNetGw = "192.168.0.1";
static String gNetMask = "255.255.255.0";

static String gNetHost = "192.168.0.10";
static uint16_t gNetPort = 9000;

static bool gWifiEnabled = false;

static bool gWifiConnected = false;
static bool gTcpConnected = false;

static bool gTcpConnectedPrev = false;

WiFiClient gTcpClient;
static unsigned long gLastTcpAttempt = 0;
static unsigned long gLastTcpActivity = 0;
static const unsigned long TCP_HEARTBEAT_MS = 10000;  // 10s
static const unsigned long TCP_TIMEOUT_MS = 20000;  // 20s sin actividad = caída
static const unsigned long TCP_RETRY_MS = 3000;

// -------------------------------
// TCP connect state machine (no bloqueante)
// -------------------------------

static TcpConnState gTcpState = TCP_IDLE;

// Timeout corto para connect (evita congelar el loop)
// Ajusta si quieres: 120–250ms suele ir bien en LAN
static const uint16_t TCP_CONNECT_TIMEOUT_MS = 200;

// Flag: “cuando WiFi sube, manda NETSTAT SOLO cuando TCP suba”

// ============================================================================
// OTA SAT (módulo independiente)
// ============================================================================

// --- OTA AUTÓNOMO (SoftAP del PLC) ---
static String gOtaApSsid;  // "PLC_OTA_<ID>"
static String gOtaApPass;
static const char *OTA_AP_PASS = nullptr;  // mínimo 8 chars (SAT)
static IPAddress OTA_AP_IP(192, 168, 4, 1);
static IPAddress OTA_AP_GW(192, 168, 4, 1);
static IPAddress OTA_AP_MASK(255, 255, 255, 0);

static bool gOtaWifiWasEnabled = false;  // para restaurar config al salir

static std::atomic<OtaState> gOtaState{OTA_IDLE};
static String gOtaLastErr = "";
static SemaphoreHandle_t gOtaErrorMutex = nullptr;

static String gOtaSsid, gOtaPwd, gOtaUrl, gOtaSha256;
static uint32_t gOtaSize = 0;
static std::atomic<uint8_t> gOtaPct{0};
static std::atomic<bool> gOtaAbort{false};

static TaskHandle_t gOtaTaskHandle = nullptr;
static std::atomic<bool> gOtaParamsReady{false};

// ============================================================================
// I2C - SALIDAS (EXPANSOR 0x20)
// ============================================================================
#define I2C_OUT_ADDR 0x20
#define SDA_PIN 42
#define SCL_PIN 41

// ============================================================================
// I2C - SEGURIDAD / RECUPERACIÓN
// ============================================================================
static const uint32_t I2C_CLOCK_HZ =
    100000;  // 100 kHz: más robusto industrialmente
static const uint32_t I2C_TIMEOUT_MS = 50;  // evita bloqueos largos
static const uint8_t I2C_MAX_FAILS = 3;  // tras 3 fallos seguidos, recupera bus
static const uint32_t I2C_RECOVER_GAP_MS =
    1000;  // mínimo 1 s entre recuperaciones

static uint8_t gI2cFailCount = 0;
static bool gI2cHealthy = true;
static unsigned long gLastI2cRecoverMs = 0;

// Si algún día quieres desactivar rápido todo el diagnóstico I2C
#define DEBUG_I2C 0

// ============================================================================
// ADS1115 - LECTURA SEGURA CON TIMEOUT
// ============================================================================
#define ADS1115_ADDR 0x48
#define ADS1115_REG_CONVERSION 0x00
#define ADS1115_REG_CONFIG 0x01

#define ADS1115_OS_SINGLE 0x8000
#define ADS1115_MUX_SINGLE_0 0x4000
#define ADS1115_MUX_SINGLE_1 0x5000
#define ADS1115_MUX_SINGLE_2 0x6000
#define ADS1115_MUX_SINGLE_3 0x7000

#define ADS1115_PGA_4_096V 0x0200  // Equivalente a GAIN_ONE
#define ADS1115_MODE_SINGLE 0x0100
#define ADS1115_DR_250SPS 0x00A0
#define ADS1115_COMP_DISABLE 0x0003

static const uint32_t ADS_READ_TIMEOUT_MS = 100;
static bool gBatValid[2] = {false, false};
static uint32_t gBatValidMs[2] = {0, 0};
static bool gPeripheralOk[3] = {false, false, false};  // RTC, ADC, outputs
static uint32_t gLastProbeMs = 0;

byte estado = 0xFF;  // Activo-Bajo
const bool DEFAULT_STATE[8] = {
    false,  // LED ESTADO OFF (lo controla el parpadeo)
    true,   // RELÉ ON al arrancar
    false, false, false, false, false, false};

// ============================================================================
// BATERÍA (ADS1115)
// ============================================================================
String gNivelBat1 = "00";             // 2 chars
String gNivelBat2 = "00";             // 2 chars
String gNivelBat4 = "0000";           // campo BLE final
const float V_BAT_MIN = 14.0;         // 0%
const float V_BAT_MAX = 20.0;         // 100%
const float K_ADC_BAT1 = 0.0006435f;  // Ajuste batería 1
const float K_ADC_BAT2 = 0.0006435f;  // Ajuste batería 2

unsigned long lastBatRead = 0;
const unsigned long BAT_PERIOD = 300000;  // 5 min

const float V_BAT_CUTOFF = 14.0f;
const unsigned long BAT_CUTOFF_CONFIRM_MS =
    15000UL;  // 15 s por debajo para cortar
bool batCutEjecutado = false;
unsigned long batLowSinceMs = 0;
float gBatV1 = 0.0f;
float gBatV2 = 0.0f;

unsigned long lastBatSafetyCheck = 0;
const unsigned long BAT_SAFETY_PERIOD = 1000UL;  // 10 s

// --- Avisos escalonados por % (manda la peor batería) ---
static uint8_t gBatWarnMask = 0;  // bit0=20%, bit1=15%, bit2=10%, bit3=5%
static const uint8_t WARN20_BIT = 1 << 0;
static const uint8_t WARN15_BIT = 1 << 1;
static const uint8_t WARN10_BIT = 1 << 2;
static const uint8_t WARN5_BIT = 1 << 3;

static const int BAT_WARN_REARM_PCT =
    22;  // si sube a >=22%, rearmamos avisos (tras cambiar baterías)

// --- Corte crítico por % ---
static unsigned long batCriticalSinceMs = 0;
static const unsigned long BAT_5_CONFIRM_MS =
    3000UL;  // confirma 5% durante 3s antes de cortar (ajústalo)

// --- Corte por voltaje real (pack) ---
static const float V_CUTOFF_WARN = 14.0f;      // corte "seguro"
static const float V_CUTOFF_COLLAPSE = 13.5f;  // colapso (corte rápido)

// Confirmaciones (ajústalas si quieres)
static const unsigned long V_WARN_CONFIRM_MS = 10000UL;     // 10 s bajo 14.5V
static const unsigned long V_COLLAPSE_CONFIRM_MS = 3000UL;  // 3 s bajo 14.0V

static unsigned long vWarnSinceMs = 0;
static unsigned long vCollapseSinceMs = 0;

// --- Detección batería AUSENTE (para cambios en caliente) ---
static const float V_BAT_ABSENT =
    2.0f;  // <2V => batería desconectada/ausente (ajusta si hace falta)
static bool gBat1Present = true;
static bool gBat2Present = true;

// Para detectar evento "retirada" y avisar una vez
static bool gBat1PresentPrev = true;
static bool gBat2PresentPrev = true;

// ============================================================================
// RS485 - ALTURA
// ============================================================================
#define TXD1 17
#define RXD1 18
#define TXD1EN 21

String gAltura = "-";               // "MM.CC"
static bool gAlturaEnabled = true;  // ALT=1 habilita, ALT=0 deshabilita
uint8_t tramaRS[5] = {0x40, 0x43, 0x00, 0x00, 0x01};
HardwareSerial RS485(1);

// ============================================================================
// FOTOCÉLULA
// ============================================================================
#define PIN_SENSOR_PALET 4

const unsigned long retardoRebote = 250;  // ms
unsigned long ultimoRebote = 0;

char gFotoEstado = '0';
bool eventoCargaPendiente = false;
bool eventoDescargaPendiente = false;

bool PaletCargado = false;
int estadoSensorFiltrado = LOW;
int estadoSensorAnterior = LOW;

static int lecturaAnteriorRaw = HIGH;  // SOLO para debounce (lectura cruda)

const unsigned long FOTO_INACTIVIDAD_MS =
    120UL * 60UL * 1000UL;  // 2h de inactividad hasta apagado
bool armadoInactividad = false;
unsigned long lastFotoActivaMs = 0;
bool powerCutEjecutado = false;

// Armado inicial “estable”
static bool gFotoLista = false;
static int gFotoUltLect = HIGH;
static unsigned long gFotoUltCambioMs = 0;
static const unsigned long FOTO_STABLE_MS = 1500;  // 1.5 s

// ============================================================================
// LECTOR CÓDIGOS (UART0 físico)
// ============================================================================
#define RX0 44
#define TX0 43

HardwareSerial PLCSerial(0);
const uint32_t LECTOR_TIMEOUT_MS = 2000;
const char LECTOR_TERMINADOR =
    '\n';  // trama termina en CR LF (cortamos por LF)

String gCodPalet = "-";
String gCodUbic = "-";

// ============================================================================
// SINCRONIZACIÓN LOG SERIE
// ============================================================================
portMUX_TYPE serialMux = portMUX_INITIALIZER_UNLOCKED;
portMUX_TYPE countersMux = portMUX_INITIALIZER_UNLOCKED;

// ============================================================================
// RTC PCF85063 (I2C 0x51)
// ============================================================================
#define PCF85063_ADDR 0x51

static bool gRtcOk = false;
static String gRtcIso = "0000-00-00T00:00:00";
static unsigned long gRtcLastReadMs = 0;

// ============================================================================
// FORWARD DECLARATIONS (para que el orden sea limpio)
// ============================================================================
void buildAndSendResponseV2(const String &id, const String &nivelBat,
                            const String &altura, char fotoEstado,
                            const String &codPalet, const String &codUbic);

void powerCut(const char *reason);

void sendResponse_L();
void sendResponse_B();
void sendResponse_A();
void sendResponse_P();
void sendResponse_D();
void sendResponse_I();

void ensureAdvertising();
static void rtcUpdateCacheIfNeeded();

static inline void serialConsolePoll();
static void bleProcessPendingOnce();

static void identityLoad();
static void handleIdentityCommand(const String &s, uint8_t source);

// Runtime v3: loop owns IO, parsing, output notifications and persistent
// events.
#define REQUIRE_MAINT_AUTH 1
#define REQUIRE_BLE_PAIRING 1
static std::atomic<bool> gBleSecure{false};
// Preserve original low-battery policy until the electrical power path is
// verified. 0: cut if ANY present battery is critical; 1: cut only if ALL
// present are critical.
#define BATTERY_CUT_ONLY_WHEN_ALL_LOW 0
#define MAX_FRAME_BYTES 512
#define OUTBOX_CAPACITY 8
#define FIRMWARE_VERSION "3.0.5-rc1"

static QueueHandle_t gTxQueue = nullptr;
static BLE2902 *gCccd = nullptr;
static std::atomic<uint16_t> gBleMtu{23};
static uint32_t gTxDropped = 0, gEventOverflow = 0, gScanOverflow = 0;
static std::atomic<bool> gMaintenanceAuthorized{false};
static uint32_t gAuthBlockedUntil = 0;
static uint8_t gAuthFails = 0;
static String gMaintenancePin;
static bool gPinChangedOnBoot = false;
static uint32_t gWifiStartMs = 0, gLastTcpRxMs = 0, gLastTcpPingMs = 0;
static bool gHeartbeatEnabled = false, gAckMode = false;
static std::atomic<bool> gNetworkRestorePending{false};
static std::atomic<bool> gCutRequested{false};
static bool gCutConfirmed = false;
static uint32_t gCutRequestedMs = 0, gLastCutAttemptMs = 0;
static char gCutReason[96] = "";
static PersistentOutbox gOutbox = {};
static bool gOutboxDirty = false, gEventInFlight = false;
static uint32_t gEventLastAttempt = 0;
static ScanJob gScanJobs[16];
static uint8_t gScanHead = 0, gScanCount = 0;
static bool gScanActive = false;
static ScanJob gCurrentScan = {};
static uint8_t gScanPhase = 0;
static uint32_t gScanStarted = 0;
static String gReaderBuffer;
static bool gReaderOverflow = false;
static uint8_t gHeightReply[3];
static uint8_t gHeightCount = 0;

// ============================================================================
// LOGGING
// ============================================================================
void logSerialBlock(const String &msg) { Serial.print(msg); }

void logLine(const String &msg) { logSerialBlock(msg + "\n"); }

void logf(const char *fmt, ...) {
  char buf[256];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);
  logSerialBlock(String(buf));
}

// ============================================================================
// I2C - HELPERS DE SEGURIDAD
// ============================================================================

static void i2cRegisterResult(bool ok, const char *context);

static void i2cBeginSafe() {
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(I2C_CLOCK_HZ);
  Wire.setTimeOut(I2C_TIMEOUT_MS);
}

static void i2cRecoverBus() {
  unsigned long now = millis();

  // Evita estar reiniciando el bus en bucle
  if (now - gLastI2cRecoverMs < I2C_RECOVER_GAP_MS) return;
  gLastI2cRecoverMs = now;

#if DEBUG_I2C
  logLine("I2C: intentando recuperar bus...");
#endif

  // Parar driver I2C
  Wire.end();
  delay(10);

  // Soltar líneas
  pinMode(SDA_PIN, INPUT_PULLUP);
  pinMode(SCL_PIN, INPUT_PULLUP);
  delay(5);

  // Si SDA está baja, generamos pulsos de reloj para liberar un esclavo colgado
  if (digitalRead(SDA_PIN) == LOW) {
#if DEBUG_I2C
    logLine("I2C: SDA baja, generando 9 pulsos SCL...");
#endif

    pinMode(SCL_PIN, OUTPUT_OPEN_DRAIN);
    pinMode(SDA_PIN, INPUT_PULLUP);

    for (int i = 0; i < 9; i++) {
      digitalWrite(SCL_PIN, HIGH);
      delayMicroseconds(10);
      digitalWrite(SCL_PIN, LOW);
      delayMicroseconds(10);
    }
  }

  // Generar condición STOP manual:
  // SDA baja con SCL alta y luego SDA alta
  pinMode(SDA_PIN, OUTPUT_OPEN_DRAIN);
  digitalWrite(SDA_PIN, LOW);
  delayMicroseconds(10);

  pinMode(SCL_PIN, INPUT_PULLUP);
  delayMicroseconds(10);

  digitalWrite(SDA_PIN, HIGH);
  delayMicroseconds(10);

  // Reiniciar driver I2C
  i2cBeginSafe();

  gI2cFailCount = 0;
  gI2cHealthy = false;

#if DEBUG_I2C
  logLine("I2C: bus reiniciado.");
#endif
}

static bool i2cWriteRegister16(uint8_t addr, uint8_t reg, uint16_t value,
                               const char *context) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write((uint8_t)(value >> 8));
  Wire.write((uint8_t)(value & 0xFF));

  byte err = Wire.endTransmission();
  bool ok = (err == 0);

  if (!ok) {
    logf("I2C WRITE16 ERROR en %s addr=0x%02X reg=0x%02X err=%d\n", context,
         addr, reg, err);
  }

  i2cRegisterResult(ok, context);
  return ok;
}

static bool i2cReadRegister16(uint8_t addr, uint8_t reg, uint16_t &value,
                              const char *context) {
  Wire.beginTransmission(addr);
  Wire.write(reg);

  byte err = Wire.endTransmission(false);  // repeated start
  bool ok = (err == 0);

  if (!ok) {
    logf("I2C READ16 ADDR ERROR en %s addr=0x%02X reg=0x%02X err=%d\n", context,
         addr, reg, err);
    i2cRegisterResult(false, context);
    return false;
  }

  uint8_t got = Wire.requestFrom(addr, (uint8_t)2);

  if (got != 2) {
    logf("I2C READ16 DATA ERROR en %s addr=0x%02X reg=0x%02X got=%u\n", context,
         addr, reg, got);
    i2cRegisterResult(false, context);
    return false;
  }

  uint8_t msb = Wire.read();
  uint8_t lsb = Wire.read();

  value = ((uint16_t)msb << 8) | lsb;

  i2cRegisterResult(true, context);
  return true;
}

static void i2cRegisterResult(bool ok, const char *context) {
  if (ok) {
    if (!gI2cHealthy) {
#if DEBUG_I2C
      logf("I2C: recuperado en %s\n", context);
#endif
    }

    gI2cHealthy = true;
    gI2cFailCount = 0;
    return;
  }

  gI2cHealthy = false;

  if (gI2cFailCount < 255) gI2cFailCount++;

#if DEBUG_I2C
  logf("I2C: fallo en %s. Fallos seguidos=%u\n", context, gI2cFailCount);
#endif

  if (gI2cFailCount >= I2C_MAX_FAILS) {
    i2cRecoverBus();
  }
}

static bool adsReadSingleEndedTimeout(
    uint8_t channel, int16_t &raw, uint32_t timeoutMs = ADS_READ_TIMEOUT_MS) {
  if (channel > 3) return false;
  uint16_t cfg = ADS1115_OS_SINGLE | (ADS1115_MUX_SINGLE_0 + channel * 0x1000) |
                 ADS1115_PGA_4_096V | ADS1115_MODE_SINGLE | ADS1115_DR_250SPS |
                 ADS1115_COMP_DISABLE;
  if (!i2cWriteRegister16(ADS1115_ADDR, ADS1115_REG_CONFIG, cfg, "ADS/config"))
    return false;
  uint32_t start = millis();
  while (millis() - start < timeoutMs) {
    delay(1);
    uint16_t status = 0;
    if (!i2cReadRegister16(ADS1115_ADDR, ADS1115_REG_CONFIG, status,
                           "ADS/ready"))
      return false;
    if (status & ADS1115_OS_SINGLE) {
      uint16_t v = 0;
      if (!i2cReadRegister16(ADS1115_ADDR, ADS1115_REG_CONVERSION, v,
                             "ADS/read"))
        return false;
      raw = (int16_t)v;
      return raw >= 0;
    }
  }
  return false;
}

bool writeOutput() {
  Wire.beginTransmission(I2C_OUT_ADDR);
  Wire.write(0x01);  // output register
  Wire.write(estado);

  byte err = Wire.endTransmission();
  bool ok = (err == 0);

  if (!ok) {
    logf("ERROR I2C: no se pudo aplicar estado salidas, err=%d\n", err);
  }

  gPeripheralOk[2] = ok;
  i2cRegisterResult(ok, "writeOutput/expansor 0x20");
  return ok;
}

bool setPin(byte pin, bool value) {
  byte oldEstado = estado;

  if (value)
    estado &= ~(1 << pin);  // ON físico, activo bajo
  else
    estado |= (1 << pin);  // OFF físico

  if (!writeOutput()) {
    // Revertimos estado software si no se pudo escribir físicamente
    estado = oldEstado;
    return false;
  }

  return true;
}

static inline void ledOut(bool on) {
  // Evita escribir al expansor si no hay cambio real
  if (gLedOutOn == on) return;

  // Solo actualizamos gLedOutOn si la escritura I2C ha funcionado
  if (setPin(O_LED_ESTADO, on)) {
    gLedOutOn = on;
  } else {
#if DEBUG_I2C
    logLine("LED: no se actualiza estado interno porque falló I2C.");
#endif
  }
}

// Atajos (por si algún día los usas)
void setO0(bool v) { (void)setPin(0, v); }
void setO1(bool v) { (void)setPin(1, v); }
void setO2(bool v) { (void)setPin(2, v); }
void setO3(bool v) { (void)setPin(3, v); }
void setO4(bool v) { (void)setPin(4, v); }
void setO5(bool v) { (void)setPin(5, v); }
void setO6(bool v) { (void)setPin(6, v); }
void setO7(bool v) { (void)setPin(7, v); }

bool aplicarConfiguracionInicial(const bool cfg[8]) {
  estado = 0xFF;
  for (byte i = 0; i < 8; i++) {
    if (cfg[i])
      estado &= ~(1 << i);
    else
      estado |= (1 << i);
  }
  bool ok = writeOutput();
  String bin = String(estado, BIN);
  logf("Estado inicial %s = 0b%s\n", ok ? "APLICADO" : "NO APLICADO",
       bin.c_str());
  return ok;
}

// ============================================================================
// RTC PCF85063
// ============================================================================
static uint8_t decToBcd(uint8_t val) { return ((val / 10) << 4) | (val % 10); }
static uint8_t bcdToDec(uint8_t val) {
  return ((val >> 4) * 10) + (val & 0x0F);
}

static bool rtcProbe() {
  Wire.beginTransmission(PCF85063_ADDR);
  byte err = Wire.endTransmission();

  bool ok = (err == 0);
  i2cRegisterResult(ok, "rtcProbe/PCF85063");

  return ok;
}

static String rtcReadIso19() {
  if (!gRtcOk) return "0000-00-00T00:00:00";

  Wire.beginTransmission(PCF85063_ADDR);
  Wire.write(0x04);

  byte err = Wire.endTransmission();
  if (err != 0) {
    i2cRegisterResult(false, "rtcReadIso19/endTransmission");
    return "0000-00-00T00:00:00";
  }

  i2cRegisterResult(true, "rtcReadIso19/endTransmission");

  uint8_t got = Wire.requestFrom(PCF85063_ADDR, 7);
  if (got != 7) {
    i2cRegisterResult(false, "rtcReadIso19/requestFrom");
    return "0000-00-00T00:00:00";
  }

  i2cRegisterResult(true, "rtcReadIso19/requestFrom");

  uint8_t rawSec = Wire.read();
  if (rawSec & 0x80) {
    while (Wire.available()) Wire.read();
    return "0000-00-00T00:00:00";
  }
  uint8_t sec = bcdToDec(rawSec & 0x7F);
  uint8_t min = bcdToDec(Wire.read() & 0x7F);
  uint8_t hour = bcdToDec(Wire.read() & 0x3F);
  uint8_t day = bcdToDec(Wire.read() & 0x3F);
  Wire.read();  // wday
  uint8_t month = bcdToDec(Wire.read() & 0x1F);
  uint16_t year = 2000 + bcdToDec(Wire.read());

  if (sec > 59 || min > 59 || hour > 23 || month < 1 || month > 12 || day < 1 ||
      day > 31)
    return "0000-00-00T00:00:00";
  char buf[20];
  snprintf(buf, sizeof(buf), "%04u-%02u-%02uT%02u:%02u:%02u", year, month, day,
           hour, min, sec);
  return String(buf);
}

static void rtcUpdateCacheIfNeeded() {
  unsigned long now = millis();
  if (now - gRtcLastReadMs >= 1000) {
    gRtcIso = rtcReadIso19();
    gPeripheralOk[0] = !gRtcIso.startsWith("0000-");
    gRtcLastReadMs = now;
  }
}

static bool rtcSetFromIso19(const String &iso) {
  if (!gRtcOk) return false;

  if (iso.length() != 19) return false;
  if (iso[4] != '-' || iso[7] != '-' || iso[10] != 'T' || iso[13] != ':' ||
      iso[16] != ':')
    return false;

  for (size_t i = 0; i < iso.length(); ++i) {
    if (i == 4 || i == 7 || i == 10 || i == 13 || i == 16) continue;
    if (!isDigit(iso[i])) return false;
  }
  int year = iso.substring(0, 4).toInt();
  int month = iso.substring(5, 7).toInt();
  int day = iso.substring(8, 10).toInt();
  int hour = iso.substring(11, 13).toInt();
  int min = iso.substring(14, 16).toInt();
  int sec = iso.substring(17, 19).toInt();

  if (year < 2000 || year > 2099) return false;
  if (month < 1 || month > 12) return false;
  static const uint8_t days[] = {31, 28, 31, 30, 31, 30,
                                 31, 31, 30, 31, 30, 31};
  int maxDay = days[month - 1] + (month == 2 && year % 4 == 0);
  if (day < 1 || day > maxDay) return false;
  if (hour < 0 || hour > 23) return false;
  if (min < 0 || min > 59) return false;
  if (sec < 0 || sec > 59) return false;

  Wire.beginTransmission(PCF85063_ADDR);
  Wire.write(0x04);

  Wire.write(decToBcd((uint8_t)sec));
  Wire.write(decToBcd((uint8_t)min));
  Wire.write(decToBcd((uint8_t)hour));
  Wire.write(decToBcd((uint8_t)day));
  Wire.write(decToBcd(0));  // weekday desconocido
  Wire.write(decToBcd((uint8_t)month));
  Wire.write(decToBcd((uint8_t)(year - 2000)));

  byte err = Wire.endTransmission();
  bool ok = (err == 0);

  i2cRegisterResult(ok, "rtcSetFromIso19");

  if (!ok) return false;

  gRtcIso = iso;
  gPeripheralOk[0] = true;
  gRtcLastReadMs = millis();
  return true;
}

// ============================================================================
// NET (WiFi) - NVS LOAD / SAVE
// ============================================================================

static void netLoad() {
  Preferences pref;
  pref.begin(NVS_NS_NET, true);

  gNetMode = (NetMode)pref.getUInt("mode", NETMODE_DHCP);
  gNetSsid = pref.getString("ssid", gNetSsid);
  gNetPwd = pref.getString("pwd", gNetPwd);

  gNetIp = pref.getString("ip", gNetIp);
  gNetGw = pref.getString("gw", gNetGw);
  gNetMask = pref.getString("mask", gNetMask);

  gNetHost = pref.getString("host", gNetHost);
  gNetPort = pref.getUShort("port", gNetPort);

  gWifiEnabled = pref.getBool("wifi_en", false);
  gAlturaEnabled = pref.getBool("alt_en", false);

  pref.end();

  logLine("NET cargado desde NVS:");
  logf("  Mode: %s\n", gNetMode == NETMODE_DHCP ? "DHCP" : "STATIC");
  logf("  SSID: %s\n", gNetSsid.c_str());
  logf("  IP: %s\n", gNetIp.c_str());
  logf("  HOST: %s:%u\n", gNetHost.c_str(), gNetPort);
}

static void netSave() {
  Preferences pref;
  pref.begin(NVS_NS_NET, false);

  pref.putUInt("mode", gNetMode);
  pref.putString("ssid", gNetSsid);
  pref.putString("pwd", gNetPwd);

  pref.putString("ip", gNetIp);
  pref.putString("gw", gNetGw);
  pref.putString("mask", gNetMask);

  pref.putString("host", gNetHost);
  pref.putUShort("port", gNetPort);

  pref.putBool("wifi_en", gWifiEnabled);
  pref.putBool("alt_en", gAlturaEnabled);

  pref.end();

  logLine("NET guardado en NVS.");
}

static void wifiStartFromConfig() {
  gTcpClient.stop();
  gTcpConnected = false;
  gTcpState = TCP_IDLE;
  gWifiConnected = false;
  if (!gWifiEnabled || gNetSsid.isEmpty()) return;
  WiFi.mode(WIFI_STA);
  WiFi.disconnect(false);
  IPAddress ip, gw, mask;
  if (gNetMode == NETMODE_STATIC && ip.fromString(gNetIp) &&
      gw.fromString(gNetGw) && mask.fromString(gNetMask)) {
    if (!WiFi.config(ip, gw, mask)) {
      logLine("WiFi.config STATIC fallo");
      return;
    }
  } else {
    if (!WiFi.config(INADDR_NONE, INADDR_NONE, INADDR_NONE))
      logLine("WiFi.config DHCP fallo");
  }
  WiFi.setAutoReconnect(true);
  WiFi.begin(gNetSsid.c_str(), gNetPwd.c_str());
  gWifiStartMs = millis();
  logLine("WiFi: conexion iniciada sin espera");
}

static void tcpConnectTick() {
  // Si WiFi no está realmente conectado, no hay nada que hacer
  if (!gWifiConnected) {
    if (gTcpClient.connected()) gTcpClient.stop();
    gTcpConnected = false;
    gTcpState = TCP_IDLE;
    return;
  }

  // Si ya está conectado según el cliente, mantenemos estado
  if (gTcpClient.connected()) {
    gTcpConnected = true;
    gTcpState = TCP_CONNECTED;
    return;
  }

  // Si creíamos que estaba conectado pero ya no, limpiamos
  if (gTcpConnected) {
    gTcpConnected = false;
    gTcpState = TCP_WAIT_RETRY;
  }

  unsigned long now = millis();

  switch (gTcpState) {
    case TCP_IDLE:
      // primera vez: intentamos ya
      gTcpState = TCP_CONNECTING;
      __attribute__((fallthrough));

    case TCP_CONNECTING: {
      // throttle por seguridad (evitar martillear)
      if (now - gLastTcpAttempt < TCP_RETRY_MS) {
        gTcpState = TCP_WAIT_RETRY;
        break;
      }

      gLastTcpAttempt = now;

      logf("Intentando conexión TCP a %s:%u...\n", gNetHost.c_str(), gNetPort);

      // 🔑 CLAVE: connect con timeout corto (evita los 3s de bloqueo)
      // En ESP32 Arduino suele existir esta sobrecarga:
      bool ok = gTcpClient.connect(gNetHost.c_str(), gNetPort,
                                   TCP_CONNECT_TIMEOUT_MS);

      if (ok) {
        gTcpConnected = true;
        gTcpState = TCP_CONNECTED;
        gLastTcpActivity = millis();
        gLastTcpRxMs = millis();
        gLastTcpPingMs = millis();
        gTcpClient.setNoDelay(true);

        logLine("TCP conectado.");

        // --- ENVIAR MENSAJE DE CONEXIÓN ---
        rtcUpdateCacheIfNeeded();  // aseguramos RTC fresco

        String msg;
        msg.reserve(128);
        msg += "CONNECTED;";
        msg += "RTC=" + gRtcIso + ";";
        msg += "ID=" + gId + ";";
        msg += "IP=" + WiFi.localIP().toString();
        msg += "\r\n";

        tcpWriteAvailable((const uint8_t *)msg.c_str(), msg.length());

        logLine("Mensaje CONNECT enviado.");
      } else {
        gTcpConnected = false;
        gTcpState = TCP_WAIT_RETRY;
        logLine("TCP conexión fallida.");
      }

    } break;

    case TCP_WAIT_RETRY:
      // Esperar hasta que toque reintentar
      if (now - gLastTcpAttempt >= TCP_RETRY_MS) {
        gTcpState = TCP_CONNECTING;
      }
      break;

    case TCP_CONNECTED:
      // Si estamos aquí pero connected() no lo confirma arriba, caeremos a WAIT
      gTcpState = TCP_WAIT_RETRY;
      break;
  }
}

static void tcpPollRx() {
  if (!gTcpClient.connected()) return;
  // Stream parser: partial frames, coalesced frames and resynchronisation.
  static uint8_t binary[4];
  static uint8_t n = 0;
  static char line[384];
  static size_t used = 0;
  static bool overflow = false;
  static uint32_t lastByte = 0;
  if ((n || used || overflow) && millis() - lastByte > 2000) {
    n = 0;
    used = 0;
    overflow = false;
  }
  for (unsigned budget = 0; budget < 256 && gTcpClient.available(); ++budget) {
    int v = gTcpClient.read();
    if (v < 0) break;
    uint8_t c = (uint8_t)v;
    lastByte = millis();
    gLastTcpActivity = lastByte;
    if (c == 0x1B) {
      binary[0] = c;
      n = 1;
      used = 0;
      overflow = false;
      continue;
    }
    if (n) {
      binary[n++] = c;
      if (n == 4) {
        n = 0;
        BleCmd cmd = {};
        cmd.type = cmdFromBinary4(binary);
        cmd.source = 1;
        if (cmd.type != BLECMD_NONE && !xQueueSend(gCommandQueue, &cmd, 0)) {
          const uint8_t busy[] = {0x15, 'B', 'U', 'S', 'Y', 13, 10};
          tcpWriteAvailable(busy, sizeof(busy));
        }
      }
      continue;
    }
    if (c == '\r') continue;
    if (c == '\n') {
      if (used && !overflow) {
        line[used] = 0;
        if (!strcmp(line, "PONG") || !strncmp(line, "PONG;", 5))
          gLastTcpRxMs = millis();
        else if (!strncmp(line, "ACK=", 4) || !strcmp(line, "INFO?") ||
                 !strcmp(line, "DIAG?") || !strcmp(line, "ID?")) {
          BleCmd cmd = {};
          cmd.type = BLECMD_TEXT;
          cmd.source = 1;
          strlcpy(cmd.payload, line, sizeof(cmd.payload));
          if (!xQueueSend(gCommandQueue, &cmd, 0))
            logLine("TCP command queue full");
        }
      }
      used = 0;
      overflow = false;
      continue;
    }
    if (c >= 32 && c < 127 && !overflow) {
      if (used < sizeof(line) - 1)
        line[used++] = (char)c;
      else
        overflow = true;
    }
  }
}

static void tcpWatchdog() {
  if (!gTcpConnected) return;
  if (!gTcpClient.connected()) {
    gTcpConnected = false;
    return;
  }
  // Optional application heartbeat, disabled until the server implements PONG.
  if (!gHeartbeatEnabled) return;
  if (millis() - gLastTcpPingMs >= TCP_HEARTBEAT_MS) {
    gLastTcpPingMs = millis();
    tcpWriteAvailable((const uint8_t *)"PING\r\n", 6);
  }
  if (millis() - gLastTcpRxMs > TCP_TIMEOUT_MS) {
    gTcpClient.stop();
    gTcpConnected = false;
    logLine("TCP: timeout PONG");
  }
}

// ============================================================================
// CONTADORES (NVS) - Load/Inc/Print/Reset
// ============================================================================
static inline void countersLoadAndBootInc() {
  gPrefs.begin(NVS_NS_COUNTERS, false);
  uint32_t boot = gPrefs.getUInt("boot", 0);
  uint32_t pon = gPrefs.getUInt("pon", 0);
  uint32_t palC = gPrefs.getUInt("pal_c", 0);
  uint32_t palD = gPrefs.getUInt("pal_d", 0);
  uint32_t fail = gPrefs.getUInt("fail", 0);

  portENTER_CRITICAL(&countersMux);
  gBootCount = boot + 1;
  gPonTotal = pon;
  gPalCargado = palC;
  gPalDescargado = palD;
  gLecturasFallidas = fail;
  boot = gBootCount;
  portEXIT_CRITICAL(&countersMux);

  gPrefs.putUInt("boot", boot);
  gPrefs.end();
}

static inline void countersIncPon() {
  uint32_t v;
  portENTER_CRITICAL(&countersMux);
  gPonTotal++;
  v = gPonTotal;
  portEXIT_CRITICAL(&countersMux);

  gPrefs.begin(NVS_NS_COUNTERS, false);
  gPrefs.putUInt("pon", v);
  gPrefs.end();
}

static inline void countersIncPalCargado() {
  uint32_t v;
  portENTER_CRITICAL(&countersMux);
  gPalCargado++;
  v = gPalCargado;
  portEXIT_CRITICAL(&countersMux);

  gPrefs.begin(NVS_NS_COUNTERS, false);
  gPrefs.putUInt("pal_c", v);
  gPrefs.end();
}

static inline void countersIncPalDescargado() {
  uint32_t v;
  portENTER_CRITICAL(&countersMux);
  gPalDescargado++;
  v = gPalDescargado;
  portEXIT_CRITICAL(&countersMux);

  gPrefs.begin(NVS_NS_COUNTERS, false);
  gPrefs.putUInt("pal_d", v);
  gPrefs.end();
}

static inline void countersIncLecturaFallida() {
  uint32_t v;
  portENTER_CRITICAL(&countersMux);
  gLecturasFallidas++;
  v = gLecturasFallidas;
  portEXIT_CRITICAL(&countersMux);

  gPrefs.begin(NVS_NS_COUNTERS, false);
  gPrefs.putUInt("fail", v);
  gPrefs.end();
}

static inline void countersPrint() {
  uint32_t boot, pon, palC, palD, fail;
  portENTER_CRITICAL(&countersMux);
  boot = gBootCount;
  pon = gPonTotal;
  palC = gPalCargado;
  palD = gPalDescargado;
  fail = gLecturasFallidas;
  portEXIT_CRITICAL(&countersMux);

  logLine("========== CONTADORES ==========");
  logf("Encendidos:                %lu\n", (unsigned long)boot);
  logf("Fases ejecutadas (PON):    %lu\n", (unsigned long)pon);
  logf("Palets cargados:           %lu\n", (unsigned long)palC);
  logf("Palets descargados:        %lu\n", (unsigned long)palD);
  logf("Lecturas fallidas:         %lu\n", (unsigned long)fail);
  logLine("================================");
}

static inline void countersReset() {
  uint32_t boot, pon, palC, palD, fail;

  portENTER_CRITICAL(&countersMux);
  gBootCount = 0;
  gPonTotal = 0;
  gPalCargado = 0;
  gPalDescargado = 0;
  gLecturasFallidas = 0;

  boot = gBootCount;
  pon = gPonTotal;
  palC = gPalCargado;
  palD = gPalDescargado;
  fail = gLecturasFallidas;
  portEXIT_CRITICAL(&countersMux);

  gPrefs.begin(NVS_NS_COUNTERS, false);
  gPrefs.putUInt("boot", boot);
  gPrefs.putUInt("pon", pon);
  gPrefs.putUInt("pal_c", palC);
  gPrefs.putUInt("pal_d", palD);
  gPrefs.putUInt("fail", fail);
  gPrefs.end();

  logLine("Contadores reseteados a 0.");
  countersPrint();
}

// Consola serial
static String gSerialLine;

static void i2cScan() {
  logLine("Escaneando bus I2C...");

  uint8_t found = 0;

  for (uint8_t addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    byte err = Wire.endTransmission();

    if (err == 0) {
      logf("  I2C encontrado: 0x%02X\n", addr);
      found++;
    }

    delay(2);
  }

  if (found == 0) {
    logLine("No se encontraron dispositivos I2C.");
  } else {
    logf("Total dispositivos I2C: %u\n", found);
  }
}

static inline void serialConsolePoll() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\r') continue;

    if (c == '\n') {
      String cmd = gSerialLine;
      gSerialLine = "";
      cmd.trim();
      cmd.toLowerCase();

      if (cmd.length() == 0) return;

      if (cmd == "info") {
        Serial.printf("FW %s | PIN %s | OTA AP key %s\n", FIRMWARE_VERSION,
                      gMaintenancePin.c_str(), OTA_AP_PASS);
      } else if (cmd == "counters")
        countersPrint();
      else if (cmd == "counters reset")
        countersReset();
      else if (cmd == "i2c")
        i2cScan();
      else if (cmd == "i2c recover")
        i2cRecoverBus();
      else if (cmd == "counters help") {
        logLine("Comandos:");
        logLine("  counters");
        logLine("  counters reset");
        logLine("  counters help");
        logLine("  rtc");
        logLine("  i2c");
        logLine("  i2c recover");
      } else if (cmd == "rtc") {
        logf("RTC actual: %s\n", gRtcIso.c_str());
      }
      return;
    }

    if (gSerialLine.length() < 80)
      gSerialLine += c;
    else
      gSerialLine = "";
  }
}

// ============================================================================
// UTILIDADES FRAME / CMD
// ============================================================================
bool matchCommand(const uint8_t *rx, const uint8_t *cmd) {
  return (rx[0] == cmd[0] && rx[1] == cmd[1] && rx[2] == cmd[2] &&
          rx[3] == cmd[3]);
}

// Convierte un comando ASCII tipo "0x1B,0x4C,0x0D,0x0A" a 4 bytes
bool parseAsciiCommand(String text, uint8_t *rx) {
  text.replace(" ", "");
  unsigned start = 0;
  for (unsigned i = 0; i < 4; ++i) {
    int end = text.indexOf(',', start);
    if ((i < 3 && end < 0) || (i == 3 && end >= 0)) return false;
    String token = end < 0 ? text.substring(start) : text.substring(start, end);
    if (token.startsWith("0x") || token.startsWith("0X"))
      token = token.substring(2);
    if (token.length() != 2) return false;
    for (unsigned k = 0; k < 2; ++k)
      if (!isxdigit((unsigned char)token[k])) return false;
    rx[i] = (uint8_t)strtoul(token.c_str(), nullptr, 16);
    start = end + 1;
  }
  return true;
}

static bool otaParseStart(const String &s) {
  String body = s.substring(4), url, hash;
  uint32_t size = 0;
  uint8_t seen = 0;
  int start = 0;
  while (start < (int)body.length()) {
    int end = body.indexOf(';', start);
    String kv = end < 0 ? body.substring(start) : body.substring(start, end);
    int eq = kv.indexOf('=');
    if (eq < 1) return false;
    String k = kv.substring(0, eq), v = kv.substring(eq + 1);
    k.trim();
    v.trim();
    uint8_t bit = 0;
    if (k == "URL") {
      bit = 1;
      url = v;
    } else if (k == "SIZE") {
      bit = 2;
      if (!parseU32(v, size) || size == 0 || size > ESP.getFreeSketchSpace())
        return false;
    } else if (k == "SHA256") {
      bit = 4;
      hash = v;
      hash.toLowerCase();
    } else
      return false;
    if (seen & bit) return false;
    seen |= bit;
    if (end < 0) break;
    start = end + 1;
    if (start == (int)body.length()) return false;
  }
  if (seen != 7 || hash.length() != 64) return false;
  for (unsigned i = 0; i < 64; ++i)
    if (!isxdigit(hash[i])) return false;
  // SAT serves the binary over the PLC's private AP; reject external hosts.
  if (!url.startsWith("http://192.168.4.")) return false;
  int colon = url.indexOf(':', 7), slash = url.indexOf('/', 7);
  int hostEnd = colon >= 0 ? colon : slash;
  if (hostEnd < 0) return false;
  IPAddress ip;
  if (!ip.fromString(url.substring(7, hostEnd)) || ip[3] < 2 || ip[3] > 254)
    return false;
  gOtaUrl = url;
  gOtaSha256 = hash;
  gOtaSize = size;
  return true;
}

// ============================================================================
// BLE: enviar trama binaria
// ============================================================================
void sendFrame(const uint8_t *data, size_t len) {
  // Producers never access BLECharacteristic or TCP. The loop owns
  // transmission.
  if (!gTxQueue || !data || !len || len > MAX_FRAME_BYTES) return;
  TxPacket p = {};
  p.len = len;
  p.session = gBleSession;
  memcpy(p.data, data, len);
  if (xQueueSend(gTxQueue, &p, 0) != pdTRUE) {
    ++gTxDropped;  // Observable; event outbox is separate and is not discarded
                   // here.
  }
}

// ============================================================================
// CFG (PREFIJOS LECTOR) - (Opción B) debajo de sendFrame()
// ============================================================================
static const char *NVS_NS_CFG = "cfg";
static String gPfxPalet = "]d1";
static String gPfxUbic = "]Q1";

static bool isValidPrefix(const String &pfx) {
  if (pfx.length() == 0 || pfx.length() > 12) return false;
  for (size_t i = 0; i < pfx.length(); i++) {
    char c = pfx[i];
    if ((uint8_t)c < 32 || c == ';' || c == 127) return false;
  }
  return true;
}

static void cfgLoad() {
  Preferences pref;
  pref.begin(NVS_NS_CFG, true);
  String p1 = pref.getString("pfxp", gPfxPalet);
  String p2 = pref.getString("pfxu", gPfxUbic);
  pref.end();

  if (isValidPrefix(p1)) gPfxPalet = p1;
  if (isValidPrefix(p2)) gPfxUbic = p2;

  logf("CFG prefijos: PFXP=%s  PFXU=%s\n", gPfxPalet.c_str(), gPfxUbic.c_str());
}

static bool cfgSave(const String &pfxP, const String &pfxU) {
  if (!isValidPrefix(pfxP) || !isValidPrefix(pfxU) || pfxP.startsWith(pfxU) ||
      pfxU.startsWith(pfxP))
    return false;

  Preferences pref;
  pref.begin(NVS_NS_CFG, false);
  pref.putString("pfxp", pfxP);
  pref.putString("pfxu", pfxU);
  pref.end();

  gPfxPalet = pfxP;
  gPfxUbic = pfxU;
  return true;
}

// Parse: "CFG=PFXP=]d1;PFXU=]Q1"
static bool cfgParseSet(const String &s, String &outP, String &outU) {
  String body = s.substring(4);
  body.trim();

  outP = gPfxPalet;
  outU = gPfxUbic;

  int start = 0;
  while (start < (int)body.length()) {
    int sep = body.indexOf(';', start);
    String kv = (sep < 0) ? body.substring(start) : body.substring(start, sep);
    kv.trim();

    int eq = kv.indexOf('=');
    if (eq < 0) return false;

    String k = kv.substring(0, eq);
    String v = kv.substring(eq + 1);
    k.trim();
    v.trim();
    k.toUpperCase();

    if (k == "PFXP")
      outP = v;
    else if (k == "PFXU")
      outU = v;
    else
      return false;

    if (sep < 0) break;
    start = sep + 1;
  }

  return isValidPrefix(outP) && isValidPrefix(outU);
}

static void cfgSendOk() {
  uint8_t okFrame[] = {0x06, 'O', 'K', 0x0D, 0x0A};
  sendFrame(okFrame, sizeof(okFrame));
}
static void cfgSendErr() {
  uint8_t errFrame[] = {0x15, 'E', 'R', 'R', 0x0D, 0x0A};
  sendFrame(errFrame, sizeof(errFrame));
}

static void cfgSendCurrent() {
  String s;
  s.reserve(64);
  s += (char)0x06;
  s += "PFXP=" + gPfxPalet + ";PFXU=" + gPfxUbic + "\r\n";
  sendFrame((const uint8_t *)s.c_str(), s.length());
}

// ============================================================================
// RESPUESTAS NET
// ============================================================================

static void netSendCurrent() {
  String s;
  s.reserve(160);

  s += (char)0x06;
  s += "NET=";
  s += "MODE=" + String(gNetMode == NETMODE_DHCP ? "DHCP" : "STATIC");
  s += ";SSID=" + gNetSsid;
  s += ";PWD=***";  // Nunca divulgar la clave por consulta o TCP
  s += ";IP=" + gNetIp;
  s += ";GW=" + gNetGw;
  s += ";MASK=" + gNetMask;
  s += ";HOST=" + gNetHost;
  s += ";PORT=" + String(gNetPort);
  s += "\r\n";

  sendFrame((const uint8_t *)s.c_str(), s.length());
}

static void netSendStatus() {
  String s;
  s.reserve(160);

  s += (char)0x06;
  s += "NETSTAT=";
  s += "WIFI=" + String(gWifiEnabled ? "1" : "0");

  bool realLink = (WiFi.status() == WL_CONNECTED);
  bool realTcp = (gTcpClient.connected());

  s += ";LINK=" + String(realLink ? "UP" : "DOWN");
  s += ";TCP=" + String(realTcp ? "UP" : "DOWN");

  if (gWifiConnected) {
    s += ";IP=" + WiFi.localIP().toString();
    s += ";RSSI=" + String(WiFi.RSSI());
  }

  s += "\r\n";

  sendFrame((const uint8_t *)s.c_str(), s.length());
}

static bool netParseSet(const String &s) {
  String ssid = gNetSsid, pwd = gNetPwd, ip = gNetIp, gw = gNetGw,
         mask = gNetMask, host = gNetHost;
  NetMode mode = gNetMode;
  uint16_t port = gNetPort;
  String body = s.substring(4);
  if (body.isEmpty()) return false;
  unsigned start = 0;
  uint16_t seen = 0;
  while (start < body.length()) {
    int end = body.indexOf(';', start);
    String kv = end < 0 ? body.substring(start) : body.substring(start, end);
    int eq = kv.indexOf('=');
    if (eq < 1) return false;
    String k = kv.substring(0, eq), v = kv.substring(eq + 1);
    k.trim();
    k.toUpperCase();
    uint16_t bit = 0;
    if (k == "MODE") {
      bit = 1;
      if (v == "DHCP")
        mode = NETMODE_DHCP;
      else if (v == "STATIC")
        mode = NETMODE_STATIC;
      else
        return false;
    } else if (k == "SSID") {
      bit = 2;
      if (v.length() > 32) return false;
      ssid = v;
    } else if (k == "PWD") {
      bit = 4;
      if (v.length() > 64) return false;
      if (v != "***") pwd = v;
    } else if (k == "IP") {
      bit = 8;
      ip = v;
    } else if (k == "GW") {
      bit = 16;
      gw = v;
    } else if (k == "MASK") {
      bit = 32;
      mask = v;
    } else if (k == "HOST") {
      bit = 64;
      if (v.isEmpty() || v.length() > 100) return false;
      host = v;
    } else if (k == "PORT") {
      bit = 128;
      uint32_t p;
      if (!parseU32(v, p) || p < 1 || p > 65535) return false;
      port = p;
    } else
      return false;
    if (seen & bit) return false;
    seen |= bit;
    if (end < 0) break;
    start = end + 1;
    if (start == body.length()) return false;
  }
  IPAddress a, b, c;
  if (mode == NETMODE_STATIC) {
    if (!a.fromString(ip) || !b.fromString(gw) || !c.fromString(mask))
      return false;
    uint32_t m = ((uint32_t)c[0] << 24) | ((uint32_t)c[1] << 16) |
                 ((uint32_t)c[2] << 8) | c[3];
    uint32_t inv = ~m;
    if (m == 0 || inv == 0 || (inv & (inv + 1)) != 0) return false;
    uint32_t addr = ((uint32_t)a[0] << 24) | ((uint32_t)a[1] << 16) |
                    ((uint32_t)a[2] << 8) | a[3];
    uint32_t gateway = ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) |
                       ((uint32_t)b[2] << 8) | b[3];
    if ((addr & inv) == 0 || (addr & inv) == inv || (addr & m) != (gateway & m))
      return false;
  }
  // Commit only after validation of the entire request.
  gNetSsid = ssid;
  gNetPwd = pwd;
  gNetIp = ip;
  gNetGw = gw;
  gNetMask = mask;
  gNetHost = host;
  gNetMode = mode;
  gNetPort = port;
  return true;
}

// ============================================================================
// TRAMA BLE V2
//  <ACK>YYYY-MM-DDThh:mm:ss<STX>ID<1B>BAT<1B>ALT<1B>FOTO<1B>PALET<1B>UBIC<1B><ETX>
// ============================================================================
void buildAndSendResponseV2(const String &id, const String &nivelBat,
                            const String &altura, char fotoEstado,
                            const String &codPalet, const String &codUbic) {
  String frame;
  frame.reserve(64 + id.length() + codPalet.length() + codUbic.length());

  frame += (char)0x06;  // ACK
  frame += gRtcIso;     // 19 chars ISO
  frame += (char)0x02;  // STX

  frame += id;
  frame += (char)0x1B;
  frame += nivelBat;
  frame += (char)0x1B;
  frame += altura;
  frame += (char)0x1B;
  frame += fotoEstado;
  frame += (char)0x1B;
  frame += codPalet;
  frame += (char)0x1B;
  frame += codUbic;
  frame += (char)0x1B;

  frame += (char)0x03;  // ETX

  sendFrame((const uint8_t *)frame.c_str(), frame.length());
}

// ============================================================================
// BATERÍA
// ============================================================================
static inline String twoDigitsOr00(const String &s) {
  if (s.length() == 2 && isDigit(s[0]) && isDigit(s[1])) return s;
  return "00";
}

static void actualizarCampoBateriaBLE() {
  gNivelBat4 = twoDigitsOr00(gNivelBat1) + twoDigitsOr00(gNivelBat2);  // "0000"
}

static inline int pctFrom2Digits(const String &s) {
  if (s.length() != 2) return 0;
  if (!isDigit(s[0]) || !isDigit(s[1])) return 0;
  return (s[0] - '0') * 10 + (s[1] - '0');
}

// Envía trama B al cruzar 20/15/10/5 (una vez por escalón)
static inline void batteryWarnIfNeeded(int pctMin) {
  // Rearme si vuelve a estar “sana” normalmente tras cambio de baterías
  if (pctMin >= BAT_WARN_REARM_PCT) {
    gBatWarnMask = 0;
  }

  if (!deviceConnected) return;

  if (pctMin <= 20 && !(gBatWarnMask & WARN20_BIT)) {
    gBatWarnMask |= WARN20_BIT;
    sendResponse_B_Cached();
  }

  if (pctMin <= 15 && !(gBatWarnMask & WARN15_BIT)) {
    gBatWarnMask |= WARN15_BIT;
    sendResponse_B_Cached();
  }

  if (pctMin <= 10 && !(gBatWarnMask & WARN10_BIT)) {
    gBatWarnMask |= WARN10_BIT;
    sendResponse_B_Cached();
  }

  if (pctMin <= 5 && !(gBatWarnMask & WARN5_BIT)) {
    gBatWarnMask |= WARN5_BIT;
    sendResponse_B_Cached();
  }
}

void actualizarNivelBateria(uint8_t channel, float factor, String &outNivel,
                            const char *tag, bool doLog = true) {
  if (channel > 1) return;
  int16_t raw = 0;
  bool ok =
      ENABLE_ADS_READS && gAdsOk && adsReadSingleEndedTimeout(channel, raw);
  float v = raw * factor;
  if (!ok || v < 0 || v > 25.0f) {
    gBatValid[channel] = false;
    gPeripheralOk[1] = false;
    if (doLog) logf("%s: MEDIDA INVALIDA (se conserva cache)\n", tag);
    return;
  }
  gBatValid[channel] = true;
  gBatValidMs[channel] = millis();
  gPeripheralOk[1] = true;
  if (channel == 0)
    gBatV1 = v;
  else
    gBatV2 = v;
  float pct = constrain((v - V_BAT_MIN) * 100.0f / (V_BAT_MAX - V_BAT_MIN),
                        0.0f, 99.0f);
  char buf[3];
  snprintf(buf, sizeof(buf), "%02d", (int)roundf(pct));
  outNivel = buf;
  if (doLog) logf("%s: raw=%d V=%.3f pct=%s%%\n", tag, raw, v, buf);
}

void powerCut(const char *reason) {
  if (gCutRequested) return;
  logf("POWER CUT solicitado: %s\n", reason);
  strlcpy(gCutReason, reason, sizeof(gCutReason));
  gCutRequested = true;
  gCutRequestedMs = millis();
  gLastCutAttemptMs = 0;
  // Legacy OFF preserved; detailed reason is available in DIAG and serial.
  bleSendOff();
}

// ============================================================================
// RS485 ALTURA
// ============================================================================
static void clearRxBuffer_RS485() {
  while (RS485.available() > 0) RS485.read();
}

String formatAltura(float metros) {
  if (!isfinite(metros) || metros < 0 || metros > 99.99f) return "-";
  unsigned cm = (unsigned)roundf(metros * 100.0f);
  char buf[16];
  snprintf(buf, sizeof(buf), "%02u.%02u", cm / 100, cm % 100);
  return String(buf);
}

void initRS485() {
  RS485.begin(115200, SERIAL_8N1, RXD1, TXD1);
  RS485.setPins(-1, -1, -1, TXD1EN);
  RS485.setMode(UART_MODE_RS485_HALF_DUPLEX);
  RS485.setTimeout(30);
  logLine("RS485 inicializado.");
}

// ============================================================================
// LECTOR CÓDIGOS - IO puro + wrapper contabilizado (Mini-mejora)
// ============================================================================

// Parsea "palet;ubic" con prefijos configurables
static bool parseReaderFrame(String trama) {
  trama.trim();

  if (trama.length() == 0) {
    logLine("No se recibio trama del lector");
    return false;
  }

  logf("Trama recibida: \"%s\"\n", trama.c_str());

  int sep = trama.indexOf(';');
  if (sep >= 0 && trama.indexOf(';', sep + 1) >= 0) return false;
  if (sep < 0) {
    logf("Trama sin separador: %s\n", trama.c_str());

    String parte = trama;
    parte.trim();

    String palet = "";
    String ubic = "";

    if (parte.startsWith(gPfxPalet))
      palet = parte.substring(gPfxPalet.length());
    else if (parte.startsWith(gPfxUbic))
      ubic = parte.substring(gPfxUbic.length());
    else {
      logf("Parte unica con prefijo desconocido: %s\n", parte.c_str());
      return false;
    }

    if (palet.length() == 0) palet = "-";
    if (ubic.length() == 0) ubic = "-";

    gCodPalet = palet;
    gCodUbic = ubic;

    logf("Cod. Palet: %s\n", gCodPalet.c_str());
    logf("Cod. Ubic:  %s\n", gCodUbic.c_str());
    return true;
  }

  String parte1 = trama.substring(0, sep);
  String parte2 = trama.substring(sep + 1);
  parte1.trim();
  parte2.trim();

  String palet = "";
  String ubic = "";

  if (parte1.startsWith(gPfxPalet))
    palet = parte1.substring(gPfxPalet.length());
  else if (parte1.startsWith(gPfxUbic))
    ubic = parte1.substring(gPfxUbic.length());
  else if (parte1 != "-" && !parte1.isEmpty())
    return false;

  if (parte2.startsWith(gPfxPalet))
    palet = parte2.substring(gPfxPalet.length());
  else if (parte2.startsWith(gPfxUbic))
    ubic = parte2.substring(gPfxUbic.length());
  else if (parte2 != "-" && !parte2.isEmpty())
    return false;

  if (palet.length() == 0 && ubic.length() == 0) {
    logLine("No se encontró ni palet ni ubicación en la trama");
    return false;
  }

  if (palet.length() == 0) palet = "-";
  if (ubic.length() == 0) ubic = "-";

  gCodPalet = palet;
  gCodUbic = ubic;

  logf("Palet leido (normalizado): %s\n", gCodPalet.c_str());
  logf("Ubic  leida (normalizada): %s\n", gCodUbic.c_str());
  return true;
}

// Helper para evitar repetir código en CMD L/D/I

// ============================================================================
// RESPUESTAS ESPECÍFICAS
// ============================================================================
void sendResponse_L() {
  actualizarCampoBateriaBLE();

  String palet = gCodPalet;
  if (palet.length() == 0) palet = "-";
  String ubic = gCodUbic;
  if (ubic.length() == 0) ubic = "-";

  buildAndSendResponseV2(gId, gNivelBat4, gAltura, gFotoEstado, palet, ubic);
}

void sendResponse_B() {
  actualizarCampoBateriaBLE();

  buildAndSendResponseV2(gId, gNivelBat4, "-", '-', "-", "-");
}

void sendResponse_B_Cached() {
  // Usa gNivelBat4 ya calculado previamente.
  // No vuelve a leer ADS1115.
  buildAndSendResponseV2(gId, gNivelBat4, "-", '-', "-", "-");
}

void sendResponse_A() {
  buildAndSendResponseV2(gId, "-", gAltura, '-', "-", "-");
}
void sendResponse_P() {
  buildAndSendResponseV2(gId, "-", "-", gFotoEstado, "-", "-");
}

void sendResponse_D() {
  String palet = gCodPalet;
  if (palet.length() == 0) palet = "-";
  buildAndSendResponseV2(gId, "-", "-", '-', palet, "-");
}

void sendResponse_I() {
  String ubic = gCodUbic;
  if (ubic.length() == 0) ubic = "-";
  buildAndSendResponseV2(gId, "-", "-", '-', "-", ubic);
}

// ============================================================================
// BLE CALLBACKS
// ============================================================================

class MyCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    String rxString = pChar->getValue();
    if (rxString.length() == 0) return;

    String rxTrim = rxString;
    rxTrim.trim();

    BleCmd cmd;
    cmd.type = BLECMD_NONE;
    cmd.payload[0] = '\0';
    if (rxString.length() >= sizeof(cmd.payload)) {
      bleSendBusy();
      return;
    }

    // --------------------------
    // 1) TEXTO (RTC/CFG) -> se difiere, NO se ejecuta aquí
    // --------------------------
    if (rxTrim.startsWith("RTC=")) {
      cmd.type = BLECMD_RTC_SET;
      strncpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload) - 1);
      cmd.payload[sizeof(cmd.payload) - 1] = '\0';
    } else if (rxTrim == "CFG?") {
      cmd.type = BLECMD_CFG_GET;
    } else if (rxTrim.startsWith("CFG=")) {
      cmd.type = BLECMD_CFG_SET;
      strncpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload) - 1);
      cmd.payload[sizeof(cmd.payload) - 1] = '\0';
    } else if (rxTrim == "NET?") {
      cmd.type = BLECMD_NET_GET;
    } else if (rxTrim.startsWith("NET=")) {
      cmd.type = BLECMD_NET_SET;
      strncpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload) - 1);
      cmd.payload[sizeof(cmd.payload) - 1] = '\0';
    } else if (rxTrim == "NETSTAT?") {
      cmd.type = BLECMD_NETSTAT_GET;
    } else if (rxTrim == "WIFI?") {
      cmd.type = BLECMD_WIFI_GET;
    } else if (rxTrim.startsWith("WIFI=")) {
      cmd.type = BLECMD_WIFI_SET;
      strncpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload) - 1);
      cmd.payload[sizeof(cmd.payload) - 1] = '\0';
    } else if (rxTrim == "ALT?") {
      cmd.type = BLECMD_ALT_GET;
    } else if (rxTrim.startsWith("ALT=")) {
      cmd.type = BLECMD_ALT_SET;
      strncpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload) - 1);
      cmd.payload[sizeof(cmd.payload) - 1] = '\0';
    } else if (rxTrim.startsWith("OTA=")) {
      cmd.type = BLECMD_OTA_START;
      strncpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload) - 1);
      cmd.payload[sizeof(cmd.payload) - 1] = '\0';
    } else if (rxTrim == "OTA?") {
      cmd.type = BLECMD_OTA_STATUS;
    } else if (rxTrim == "OTA!") {
      cmd.type = BLECMD_OTA_ABORT;
    } else if (rxTrim.startsWith("AUTH=") || rxTrim.startsWith("ACK=") ||
               rxTrim.startsWith("ACKMODE=") ||
               rxTrim.startsWith("HEARTBEAT=") || rxTrim == "INFO?" ||
               rxTrim == "DIAG?" || rxTrim == "ID?" ||
               rxTrim.startsWith("ID=")) {
      cmd.type = BLECMD_TEXT;
      strlcpy(cmd.payload, rxTrim.c_str(), sizeof(cmd.payload));
    } else {
      // --------------------------
      // 2) BINARIO 4 bytes o ASCII "0x.."
      // --------------------------
      uint8_t rx[4];
      bool ok = false;
      int len = rxString.length();

      if (len == 4) {
        for (int i = 0; i < 4; i++) rx[i] = (uint8_t)rxString[i];
        ok = true;
      } else {
        ok = parseAsciiCommand(rxTrim, rx);
      }

      // Si no parsea, NAK
      if (!ok) {
        uint8_t nakFrame[3] = {0x15, 0x0D, 0x0A};
        sendFrame(nakFrame, sizeof(nakFrame));
        return;
      }

      cmd.type = cmdFromBinary4(rx);
      if (cmd.type == BLECMD_NONE) {
        uint8_t nakFrame[3] = {0x15, 0x0D, 0x0A};
        sendFrame(nakFrame, sizeof(nakFrame));
        return;
      }
    }

    cmd.source = 0;
    cmd.session = gBleSession;
    if (!gCommandQueue || xQueueSend(gCommandQueue, &cmd, 0) != pdTRUE)
      bleSendBusy();
  }
};

class ScannerSecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override {
    return (uint32_t)gMaintenancePin.toInt();
  }
  void onPassKeyNotify(uint32_t pin) override {
    Serial.printf("BLE pairing PIN: %06lu\n", (unsigned long)pin);
  }
  bool onSecurityRequest() override { return true; }
  bool onConfirmPIN(uint32_t pin) override {
    return pin == (uint32_t)gMaintenancePin.toInt();
  }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    gBleSecure = result.success;
    if (!result.success) gMaintenanceAuthorized = false;
  }
};
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *pServer) override {
    ++gBleSession;
    gBleMtu = 23;
    gBleSecure = false;
    deviceConnected = true;
    if (gCccd) gCccd->setNotifications(false);
    isAdvertising = false;

    logSerialBlock("Cliente BLE conectado\n");
  }

  void onDisconnect(BLEServer *pServer) override {
    deviceConnected = false;
    ++gBleSession;
    gBleSecure = false;
    gMaintenanceAuthorized = false;

    // Session number invalidates commands queued by the disconnected client.

    logSerialBlock("Cliente BLE desconectado\n");

    isAdvertising = false;
    lastAdvertiseCheck = millis() - ADV_CHECK_MS;
  }
  void onConnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
#if REQUIRE_BLE_PAIRING
    esp_ble_set_encryption(param->connect.remote_bda, ESP_BLE_SEC_ENCRYPT_MITM);
#endif
  }
  void onMtuChanged(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    gBleMtu = param->mtu.mtu;
  }
};

// ============================================================================
// WATCHDOG ADVERTISING
// ============================================================================
void ensureAdvertising() {
  if (!deviceConnected) {
    unsigned long now = millis();
    if (!isAdvertising && (now - lastAdvertiseCheck >= ADV_CHECK_MS)) {
      logSerialBlock("Watchdog BLE: reintentando advertising...\n");
      BLEAdvertising *adv = BLEDevice::getAdvertising();
      if (adv) {
        adv->start();
        isAdvertising = true;
        lastAdvertiseCheck = now;
      } else {
        logSerialBlock("ERROR: getAdvertising() devolvió nullptr\n");
      }
    }
  }
}

// ============================================================================
// BLE WORKER (se ejecuta en loop, fuera del callback)
// ============================================================================
static void bleProcessPendingOnce() {
  BleCmd cmd = {};
  if (xQueueReceive(gCommandQueue, &cmd, 0) != pdTRUE) return;
  if (cmd.source == 0 && (!deviceConnected || cmd.session != gBleSession))
    return;
  if (cmd.type == BLECMD_TEXT) {
    handleTextCommand(cmd);
    return;
  }
  bool maintenance =
      cmd.type == BLECMD_RTC_SET || cmd.type == BLECMD_CFG_SET ||
      cmd.type == BLECMD_NET_SET || cmd.type == BLECMD_WIFI_SET ||
      cmd.type == BLECMD_ALT_SET || cmd.type == BLECMD_O ||
      cmd.type == BLECMD_OTA_START || cmd.type == BLECMD_OTA_ABORT;
  if (maintenance && (cmd.source != 0 || !maintenanceAuthorized())) {
    replyLine(false, "ERR=AUTH_REQUIRED");
    return;
  }
  bool otaActive = gOtaTaskHandle != nullptr || gOtaState != OTA_IDLE;
  if (otaActive && cmd.type != BLECMD_OTA_STATUS &&
      cmd.type != BLECMD_OTA_START && cmd.type != BLECMD_OTA_ABORT &&
      cmd.type != BLECMD_C && cmd.type != BLECMD_B) {
    replyLine(false, "ERR=MAINTENANCE_MODE");
    return;
  }
  if (maintenance && cmd.type != BLECMD_O && cmd.type != BLECMD_OTA_ABORT &&
      cmd.type != BLECMD_OTA_START && (gScanActive || gScanCount)) {
    replyLine(false, "ERR=BUSY");
    return;
  }
  if (cmd.type == BLECMD_OTA_START && String(cmd.payload) == "OTA=START" &&
      (!gFotoLista || PaletCargado || gScanActive || gScanCount ||
       gCutRequested)) {
    replyLine(false, "ERR=NOT_IDLE");
    return;
  }
  if (cmd.type == BLECMD_L || cmd.type == BLECMD_A || cmd.type == BLECMD_D ||
      cmd.type == BLECMD_I) {
    enqueueScan(cmd.type, false);
    return;
  }
  // Ejecutar el comando (AQUÍ sí se puede tardar 2s)
  switch (cmd.type) {
    // --------------------
    // TEXTO
    // --------------------
    case BLECMD_RTC_SET: {
      String s(cmd.payload);
      String iso = s.substring(4);
      bool okSet = rtcSetFromIso19(iso);

      if (okSet) {
        uint8_t okFrame[] = {0x06, 'O', 'K', 0x0D, 0x0A};
        sendFrame(okFrame, sizeof(okFrame));
        logLine("RTC actualizado por BLE (worker).");
      } else {
        uint8_t errFrame[] = {0x15, 'E', 'R', 'R', 0x0D, 0x0A};
        sendFrame(errFrame, sizeof(errFrame));
        logLine("RTC comando inválido (worker).");
      }
    } break;

    case BLECMD_CFG_GET:
      cfgSendCurrent();
      logLine("CFG? -> enviados prefijos actuales (worker).");
      break;

    case BLECMD_CFG_SET: {
      String rxTrim(cmd.payload);
      String pfxP, pfxU;
      bool okParse = cfgParseSet(rxTrim, pfxP, pfxU);

      if (okParse && cfgSave(pfxP, pfxU)) {
        cfgSendOk();
        logf("CFG actualizado (worker): PFXP=%s  PFXU=%s\n", gPfxPalet.c_str(),
             gPfxUbic.c_str());
      } else {
        cfgSendErr();
        logLine("CFG inválido (worker).");
      }
    } break;

    case BLECMD_NET_GET:
      netSendCurrent();
      break;

    case BLECMD_NETSTAT_GET:
      netSendStatus();
      break;

    case BLECMD_WIFI_GET: {
      String s;
      s += (char)0x06;
      s += "WIFI=" + String(gWifiEnabled ? "1" : "0") + "\r\n";
      sendFrame((const uint8_t *)s.c_str(), s.length());
    } break;

    case BLECMD_WIFI_SET: {
      String rxTrim(cmd.payload);
      String val = rxTrim.substring(5);
      val.trim();

      // ------------------------------
      // Validación del parámetro
      // ------------------------------
      if (val == "1") {
        gWifiEnabled = true;
      } else if (val == "0") {
        gWifiEnabled = false;
      } else {
        uint8_t errFrame[] = {0x15, 'E', 'R', 'R', 0x0D, 0x0A};
        sendFrame(errFrame, sizeof(errFrame));
        break;
      }

      netSave();

      // ============================================================
      // ACTIVAR WIFI
      // ============================================================
      if (gWifiEnabled) {
        wifiStartFromConfig();
      }

      // ============================================================
      // DESACTIVAR WIFI (DESCONEXIÓN LIMPIA)
      // ============================================================
      else {
        // --- Si hay TCP activo, enviamos DISCONNECT antes de cerrar ---
        if (gTcpClient.connected()) {
          rtcUpdateCacheIfNeeded();

          String msg;
          msg.reserve(128);
          msg += "DISCONNECTED;";
          msg += "RTC=" + gRtcIso + ";";
          msg += "ID=" + gId + ";";
          msg += "REASON=WIFI_DISABLED\r\n";

          tcpWriteAvailable((const uint8_t *)msg.c_str(), msg.length());

          gTcpClient.stop();
        }

        // Limpiamos flags en orden correcto
        gTcpConnected = false;
        gTcpConnectedPrev = false;
        gTcpState = TCP_IDLE;
        gWifiConnected = false;

        WiFi.disconnect(true);
      }

      // ------------------------------
      // Respuesta OK al BLE
      // ------------------------------
      uint8_t okFrame[] = {0x06, 'O', 'K', 0x0D, 0x0A};
      sendFrame(okFrame, sizeof(okFrame));

      netSendStatus();

    } break;

    case BLECMD_ALT_GET: {
      String s;
      s.reserve(16);
      s += (char)0x06;
      s += "ALT=" + String(gAlturaEnabled ? "1" : "0") + "\r\n";
      sendFrame((const uint8_t *)s.c_str(), s.length());
    } break;

    case BLECMD_ALT_SET: {
      String rxTrim(cmd.payload);
      String val = rxTrim.substring(4);
      val.trim();

      if (val == "1")
        gAlturaEnabled = true;
      else if (val == "0")
        gAlturaEnabled = false;
      else {
        uint8_t errFrame[] = {0x15, 'E', 'R', 'R', 0x0D, 0x0A};
        sendFrame(errFrame, sizeof(errFrame));
        break;
      }

      // Si la deshabilitas, normaliza el campo
      if (!gAlturaEnabled) gAltura = "-";

      // 🔥 GUARDAR EN NVS
      Preferences pref;
      pref.begin(NVS_NS_NET, false);
      pref.putBool("alt_en", gAlturaEnabled);
      pref.end();

      uint8_t okFrame[] = {0x06, 'O', 'K', 0x0D, 0x0A};
      sendFrame(okFrame, sizeof(okFrame));
    } break;

    case BLECMD_NET_SET: {
      if (!netParseSet(String(cmd.payload))) {
        cfgSendErr();
        break;
      }
      netSave();
      wifiStartFromConfig();
      cfgSendOk();
      netSendCurrent();
      netSendStatus();
    } break;

      // --------------------
      // BINARIOS
      // --------------------

    case BLECMD_B:
      sendResponse_B();
      break;

    case BLECMD_P:
      sendResponse_P();
      break;

    case BLECMD_C:
      if (cmd.source == 1) {
        String msg = "CONNECTED;RTC=" + gRtcIso + ";ID=" + gId + "\r\n";
        sendFrame((const uint8_t *)msg.c_str(), msg.length());
      } else
        bleSendConnectedAck();
      break;

    case BLECMD_O:
      powerCut("COMMAND");
      break;

    case BLECMD_OTA_STATUS: {
      String st;
      st += "STATE=" + String((int)gOtaState);
      st += ";PCT=" + String((uint8_t)gOtaPct.load());
      String err = otaGetLastErr();
      if (err.length()) st += ";ERR=" + err;
      otaSendStatusLine(st);
    } break;

    case BLECMD_OTA_ABORT:
      gOtaAbort = true;
      otaSendStatusLine("ABORT_REQ");
      break;

    case BLECMD_OTA_START: {
      String rxTrim(cmd.payload);
      rxTrim.trim();

      // 1) OTA=START  -> arranca AP y espera cliente + params
      if (rxTrim == "OTA=START") {
        if (gOtaTaskHandle != nullptr || gOtaState != OTA_IDLE) {
          otaSendErr("BUSY");
          break;
        }

        // IMPORTANTE: NO tocar O_RELE_MOTOR aquí (si no, te apagas)
        gOtaParamsReady = false;
        gOtaUrl = "";
        gOtaSha256 = "";
        gOtaSize = 0;

        BaseType_t ok = xTaskCreatePinnedToCore(otaTask, "otaTask", 8192,
                                                nullptr, 1, &gOtaTaskHandle, 1);

        if (ok != pdPASS) {
          gOtaTaskHandle = nullptr;
          otaSendErr("TASK_FAIL");
          break;
        }

        uint8_t okFrame[] = {0x06, 'O', 'K', 0x0D, 0x0A};
        sendFrame(okFrame, sizeof(okFrame));
        break;
      }

      // 2) OTA=URL=...;SIZE=...;SHA256=... -> fase 2 (o modo antiguo si
      // quieres)
      if (!rxTrim.startsWith("OTA=")) {
        otaSendErr("BAD_PARAMS");
        break;
      }

      // Si hay task corriendo, esto es “fase 2”
      if (gOtaTaskHandle != nullptr && gOtaState == OTA_WAIT_PARAMS) {
        if (!otaParseStart(rxTrim)) {
          otaSendErr("BAD_PARAMS");
          break;
        }

        gOtaParamsReady = true;

        uint8_t okFrame[] = {0x06, 'O', 'K', 0x0D, 0x0A};
        sendFrame(okFrame, sizeof(okFrame));
        otaSendStatusLine("PARAMS_OK");
        break;
      }

      // Si NO hay task: puedes decidir
      // - o rechazar (para forzar 2 fases)
      // - o permitir “modo 1 fase” (como tenías antes)
      otaSendErr("USE_OTA_START");
      break;

    } break;

    default: {
      uint8_t nakFrame[3] = {0x15, 0x0D, 0x0A};
      sendFrame(nakFrame, sizeof(nakFrame));
    } break;
  }
}

// Confirma las bandas durante 3 s. Para recuperar se exigen >=13% y
// >=23%, respectivamente. No afecta a las protecciones ni al corte real.
static void ledBatteryTick() {
  const uint32_t now = millis();
  const bool valid = gBatValid[0] && gBatValid[1] &&
                     now - gBatValidMs[0] <= LED_BAT_MAX_AGE_MS &&
                     now - gBatValidMs[1] <= LED_BAT_MAX_AGE_MS;
  const bool present1 = gBatV1 > V_BAT_ABSENT;
  const bool present2 = gBatV2 > V_BAT_ABSENT;
  if (!valid || (!present1 && !present2)) {
    gLedBatteryCandidate = gLedBatteryBand;
    gLedBatterySinceMs = now;
    return;
  }
  int pct = present1 ? pctFrom2Digits(gNivelBat1) : 99;
  if (present2) {
    const int p2 = pctFrom2Digits(gNivelBat2);
    if (p2 < pct) pct = p2;
  }
  uint8_t target;
  if (pct <= 10 || (gLedBatteryBand == 2 && pct < 13))
    target = 2;
  else if (pct <= 20 || (gLedBatteryBand != 0 && pct < 23))
    target = 1;
  else
    target = 0;
  if (target == gLedBatteryBand) {
    gLedBatteryCandidate = target;
    gLedBatterySinceMs = now;
  } else if (target != gLedBatteryCandidate) {
    gLedBatteryCandidate = target;
    gLedBatterySinceMs = now;
  } else if (now - gLedBatterySinceMs >= LED_BAT_CONFIRM_MS) {
    gLedBatteryBand = target;
  }
}

static bool bleCanNotify();

static inline void updateLedMode() {
  ledBatteryTick();
  const uint32_t now = millis();
  if (gCutRequested || !gPeripheralOk[0] || !gPeripheralOk[2] ||
      !gBatValid[0] || !gBatValid[1] ||
      now - gBatValidMs[0] > LED_BAT_MAX_AGE_MS ||
      now - gBatValidMs[1] > LED_BAT_MAX_AGE_MS ||
      (gBatV1 <= V_BAT_ABSENT && gBatV2 <= V_BAT_ABSENT)) {
    gLedMode = LED_ERROR;
  } else if (gLedBatteryBand == 2) {
    gLedMode = LED_BAT_CRITICAL;
  } else if (gLedBatteryBand == 1) {
    gLedMode = LED_BAT_LOW;
  } else if (gOtaTaskHandle || gOtaState != OTA_IDLE) {
    gLedMode = LED_OTA;
  } else {
    const bool ready = gWifiEnabled
                           ? (gTcpConnected && gTcpClient.connected())
                           : bleCanNotify();
    gLedMode = ready ? LED_READY : LED_DISCONNECTED;
  }
}

// dt es el tiempo transcurrido desde el cambio de modo (aritmetica uint32_t).
static bool ledPatternOn(LedMode mode, uint32_t dt) {
  switch (mode) {
    case LED_READY:
      return true;
    case LED_DISCONNECTED:
      return dt % 1000 < 500;
    case LED_BAT_LOW: {
      const uint32_t t = dt % 4000;
      return t < 200 || (t >= 400 && t < 600);
    }
    case LED_BAT_CRITICAL: {
      const uint32_t t = dt % 2000;
      return t < 200 || (t >= 400 && t < 600) ||
             (t >= 800 && t < 1000);
    }
    case LED_OTA:
      return dt % 1000 < 800;  // ON 800 ms, OFF 200 ms.
    case LED_ERROR:
    default:
      return dt % 400 < 200;
  }
}

static inline void ledEstadoBlinkTick() {
  const uint32_t now = millis();
  static LedMode lastMode = (LedMode)255;
  if (gLedMode != lastMode) {
    lastMode = gLedMode;
    gLedT0 = now;
  }
  ledOut(ledPatternOn(gLedMode, now - gLedT0));
}

// ============================================================================
// OTA
// ============================================================================

static String sha256HexOfStream(HTTPClient &http, WiFiClient &stream,
                                uint32_t expected, bool doUpdateWrite) {
  mbedtls_sha256_context ctx;
  mbedtls_sha256_init(&ctx);
  mbedtls_sha256_starts(&ctx, 0);
  uint8_t buf[1024], out[32];
  uint32_t total = 0, lastData = millis();
  String error;
  while (total < expected) {
    if (gOtaAbort || gCutRequested) {
      error = "ABORT";
      break;
    }
    if (millis() - lastData > 10000) {
      error = "ERR_DATA_TIMEOUT";
      break;
    }
    int avail = stream.available();
    if (avail <= 0) {
      if (!http.connected()) {
        error = "ERR_TRUNCATED";
        break;
      }
      delay(5);
      continue;
    }
    size_t want = (size_t)avail;
    if (want > sizeof(buf)) want = sizeof(buf);
    if (want > expected - total) want = expected - total;
    int r = stream.read(buf, want);
    if (r <= 0) {
      delay(1);
      continue;
    }
    lastData = millis();
    total += r;
    mbedtls_sha256_update(&ctx, buf, r);
    if (doUpdateWrite && Update.write(buf, r) != (size_t)r) {
      error = "WRITE_FAIL";
      break;
    }
    uint8_t pct = (uint8_t)((uint64_t)total * 100 / expected);
    if (pct != gOtaPct) {
      gOtaPct = pct;
      otaSendStatusLine("PCT=" + String(pct));
    }
  }
  if (error.length()) {
    mbedtls_sha256_free(&ctx);
    return error;
  }
  mbedtls_sha256_finish(&ctx, out);
  mbedtls_sha256_free(&ctx);
  static const char hex[] = "0123456789abcdef";
  char result[65];
  for (int i = 0; i < 32; ++i) {
    result[2 * i] = hex[out[i] >> 4];
    result[2 * i + 1] = hex[out[i] & 15];
  }
  result[64] = 0;
  return String(result);
}

static void otaTask(void *param) {
  gOtaAbort = false;
  gOtaPct = 0;
  otaSetLastErr("");

  WiFiClient client;
  HTTPClient http;
  int code = 0;
  int contentLen = 0;
  unsigned long t0 = 0;

  http.setConnectTimeout(5000);
  http.setTimeout(5000);
  otaSendStatusLine("START");

  // 1) cortar TCP si estaba (SAT manda)
  if (gTcpClient.connected()) {
    gTcpClient.stop();
  }
  gTcpConnected = false;

  // 2) arrancar SoftAP OTA (PLC autónomo)
  gOtaState = OTA_CONNECT_WIFI;

  gOtaWifiWasEnabled = gWifiEnabled;  // backup para restaurar si falla
  gWifiEnabled = false;               // evita que el loop toque red normal

  gWifiConnected = false;
  gTcpConnected = false;
  if (gTcpClient.connected()) gTcpClient.stop();

  WiFi.disconnect(false);
  WiFi.mode(WIFI_AP);
  delay(150);

  WiFi.softAPConfig(OTA_AP_IP, OTA_AP_GW, OTA_AP_MASK);
  bool apOk = WiFi.softAP(gOtaApSsid.c_str(), OTA_AP_PASS);
  if (!apOk) {
    otaSetLastErr("AP_START_FAIL");
    goto fail;
  }

  otaSendStatusLine("AP_ON SSID=" + gOtaApSsid +
                    " IP=" + WiFi.softAPIP().toString());
  otaSendStatusLine("WAIT_CLIENT");

  // Esperar a que la tablet se conecte
  t0 = millis();
  while (WiFi.softAPgetStationNum() == 0 && (millis() - t0) < 120000UL) {
    if (gOtaAbort) {
      otaSetLastErr("ABORT_WAIT_CLIENT");
      goto fail;
    }
    delay(200);
  }
  if (WiFi.softAPgetStationNum() == 0) {
    otaSetLastErr("NO_CLIENT");
    goto fail;
  }

  otaSendStatusLine("CLIENT_OK");

  // 2.5) Esperar parámetros (fase 2)
  gOtaParamsReady = false;
  gOtaState = OTA_WAIT_PARAMS;
  otaSendStatusLine("WAIT_PARAMS");

  // Params-ready reset occurs before publishing OTA_WAIT_PARAMS.

  t0 = millis();
  while (!gOtaParamsReady && (millis() - t0) < 120000UL) {
    if (gOtaAbort) {
      otaSetLastErr("ABORT_WAIT_PARAMS");
      goto fail;
    }
    delay(50);
  }
  if (!gOtaParamsReady) {
    otaSetLastErr("NO_PARAMS");
    goto fail;
  }

  // 3) HTTP GET
  gOtaState = OTA_HTTP_GET;
  otaSendStatusLine("HTTP_GET");

  if (!http.begin(client, gOtaUrl)) {
    otaSetLastErr("HTTP_BEGIN_FAIL");
    goto fail;
  }

  code = http.GET();
  contentLen = http.getSize();  // puede ser -1

  if (code != HTTP_CODE_OK) {
    otaSetLastErr("HTTP_CODE_" + String(code));
    http.end();
    goto fail;
  }

  if (gOtaSize > 0 && contentLen > 0 && (uint32_t)contentLen != gOtaSize) {
    otaSetLastErr("SIZE_MISMATCH");
    http.end();
    goto fail;
  }

  if (contentLen <= 0) {
    // hay servidores que no mandan Content-Length; lo permitimos
    otaSendStatusLine("NO_CONTENT_LENGTH");
  } else {
    otaSendStatusLine("LEN=" + String(contentLen));
  }

  // 4) iniciar Update
  gOtaState = OTA_FLASHING;
  if (!Update.begin((size_t)gOtaSize)) {
    otaSetLastErr("UPDATE_BEGIN_FAIL");
    http.end();
    goto fail;
  }

  // 5) leer stream -> hashear + escribir
  {
    WiFiClient *stream = http.getStreamPtr();
    String gotHash = sha256HexOfStream(http, *stream, gOtaSize, true);

    if (gotHash.startsWith("ERR_")) {
      otaSetLastErr(gotHash);
      http.end();
      Update.abort();
      goto fail;
    }
    if (gotHash == "ABORT") {
      otaSetLastErr("ABORT_DL");
      http.end();
      Update.abort();
      goto fail;
    }
    if (gotHash == "WRITE_FAIL") {
      otaSetLastErr("WRITE_FAIL");
      http.end();
      Update.abort();
      goto fail;
    }

    otaSendStatusLine("HASH=" + gotHash);

    if (gOtaSha256.length() == 64 && gotHash != gOtaSha256) {
      otaSetLastErr("SHA256_MISMATCH");
      http.end();
      Update.abort();
      goto fail;
    }
  }

  http.end();

  // 6) cerrar Update
  if (!Update.end(false)) {  // Exact number of bytes required before selecting
                             // the new image
    otaSetLastErr("UPDATE_END_FAIL");
    goto fail;
  }

  if (!Update.isFinished()) {
    otaSetLastErr("UPDATE_NOT_FINISHED");
    goto fail;
  }

  gOtaState = OTA_DONE;
  otaSendStatusLine("DONE REBOOT");
  delay(1000);  // Allow queued DONE to be transmitted by loop.
  ESP.restart();

fail:
  gOtaState = OTA_FAIL;
  {
    String err = otaGetLastErr();
    otaSendErr(err.length() ? err : String("FAIL"));
  }

  // Apagar AP y restaurar estado
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
  delay(100);

  // Restaurar WiFi normal si estaba habilitado antes
  gWifiEnabled = gOtaWifiWasEnabled;
  vTaskDelay(200 / portTICK_PERIOD_MS);
  gNetworkRestorePending = true;
  gOtaState = OTA_IDLE;
  gOtaTaskHandle = nullptr;
  vTaskDelete(nullptr);
}

// ============================================================================
// SETUP
// ============================================================================
void setup() {
  Serial.begin(115200);
  gBootMs = millis();
  gOtaErrorMutex = xSemaphoreCreateMutex();
  gCommandQueue = xQueueCreate(8, sizeof(BleCmd));
  gTxQueue = xQueueCreate(24, sizeof(TxPacket));
  if (!gCommandQueue || !gTxQueue || !gOtaErrorMutex) {
    Serial.println("FATAL: RAM/colas");
    while (true) delay(1000);
  }
  runtimeLoad();

  // CFG + contadores
  cfgLoad();
  countersLoadAndBootInc();
  countersPrint();

  // NET (WiFi)
  netLoad();
  wifiStartFromConfig();

  logf("Reset reason: %d\n", (int)esp_reset_reason());
  logSerialBlock("\n");
  logLine("BOOT: inicio");

  // UART0 lector
  PLCSerial.begin(9600, SERIAL_8N1, RX0, TX0);
  logLine("UART0 inicializado (lector codigos)");

  // Fotocélula
  pinMode(PIN_SENSOR_PALET, INPUT_PULLUP);

  lecturaAnteriorRaw = digitalRead(PIN_SENSOR_PALET);

  gFotoLista = false;
  gFotoUltLect = digitalRead(PIN_SENSOR_PALET);
  gFotoUltCambioMs = millis();

  estadoSensorAnterior = digitalRead(PIN_SENSOR_PALET);
  estadoSensorFiltrado = estadoSensorAnterior;

  PaletCargado = (estadoSensorFiltrado == HIGH);
  gFotoEstado = PaletCargado ? '1' : '0';

  armadoInactividad = false;
  lastFotoActivaMs = 0;
  powerCutEjecutado = false;

  // I2C
  // I2C
  i2cBeginSafe();
  logLine("I2C inicializado con timeout.");

  // RTC
  gRtcOk = rtcProbe();
  if (gRtcOk) {
    logLine("RTC PCF85063 detectado (0x51).");
    gRtcIso = rtcReadIso19();
  } else {
    logLine("RTC NO detectado (0x51). Se enviará timestamp 0000...");
  }
  logf("RTC inicial: %s\n", gRtcIso.c_str());

  // Expansor salidas - config
  Wire.beginTransmission(I2C_OUT_ADDR);
  Wire.write(0x03);  // config register
  Wire.write(0x00);  // all OUTPUT

  byte err = Wire.endTransmission();
  bool expCfgOk = (err == 0);

  if (!expCfgOk) {
    logf("ERROR I2C: expansor NO responde (cfg), err=%d\n", err);
  }

  i2cRegisterResult(expCfgOk, "setup/expansor cfg 0x20");

  // Estado inicial salidas
  bool okInit = aplicarConfiguracionInicial(DEFAULT_STATE);
  if (!okInit) {
    logLine("Estado inicial NO aplicado: expansor I2C no responde");
    logLine("No se puede forzar relé ON mientras el expansor no responda.");
  } else {
    logLine("Estado inicial aplicado OK (incluye relé ON).");
  }

  // ADS1115
  gAdsOk = ads.begin();

  if (!gAdsOk) {
    logLine(
        "Error: no se detectó el ADS1115. Continúo sin lectura de batería.");
    i2cRegisterResult(false, "setup/ADS1115");
  } else {
    ads.setGain(GAIN_ONE);
    logLine("ADS1115 inicializado.");
    i2cRegisterResult(true, "setup/ADS1115");
  }

  // RS485
  initRS485();

  // BLE: ID / nombre
  uint64_t chipid = ESP.getEfuseMac();
  char suffix[13];
  snprintf(suffix, sizeof(suffix), "%04X%08lX",
           (unsigned int)(uint16_t)(chipid >> 32),
           (unsigned long)(uint32_t)chipid);

  gHardwareId = String(suffix);
  identityLoad();
  String btName = gBluetoothName;

  // SSID del modo OTA autónomo
  gOtaApSsid = "PLC_OTA_" + gHardwareId;
  logf("OTA AP SSID: %s (PASS: %s)\n", gOtaApSsid.c_str(), OTA_AP_PASS);

  logf("ID equipo: %s\n", gId.c_str());
  logf("Nombre BT asignado: %s\n", btName.c_str());

  gAltura = "-";

  BLEDevice::init(btName);
  applyConfiguredPin();
  BLEDevice::setMTU(185);
#if REQUIRE_BLE_PAIRING
  BLEDevice::setEncryptionLevel(ESP_BLE_SEC_ENCRYPT_MITM);
  BLEDevice::setSecurityCallbacks(new ScannerSecurityCallbacks());
  BLESecurity *security = new BLESecurity();
  security->setStaticPIN((uint32_t)gMaintenancePin.toInt());
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
#endif

  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService =
      pServer->createService("0000FFE0-0000-1000-8000-00805F9B34FB");

  pCharacteristic = pService->createCharacteristic(
      "0000FFE1-0000-1000-8000-00805F9B34FB",
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR |
          BLECharacteristic::PROPERTY_NOTIFY);

  gCccd = new BLE2902();
#if REQUIRE_BLE_PAIRING
  pCharacteristic->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  gCccd->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM |
                              ESP_GATT_PERM_WRITE_ENC_MITM);
#endif
  pCharacteristic->addDescriptor(gCccd);
  pCharacteristic->setCallbacks(new MyCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID("0000FFE0-0000-1000-8000-00805F9B34FB");

  pAdvertising->setScanResponse(true);
  BLEAdvertisementData scanResp;
  scanResp.setName(btName.c_str());
  pAdvertising->setScanResponseData(scanResp);

  pAdvertising->setMinPreferred(0x00);
  pAdvertising->start();

  isAdvertising = true;
  lastAdvertiseCheck = millis();

  // --- LED ESTADO: arrancar en OFF, el loop decidirá el patrón ---
  gLedOutOn = false;
  ledOut(false);
  gLedMode = LED_DISCONNECTED;  // el loop evalua perifericos y conexiones
  gLedT0 = millis();

  logLine("Esperando conexión BLE...");
}

static void loopAliveTick() {
  static unsigned long lastAliveMs = 0;

  unsigned long now = millis();

  if (now - lastAliveMs >= 1000) {
    lastAliveMs = now;

#if DEBUG_I2C
    logf("LOOP ALIVE | I2C=%s | fails=%u | BLE=%d | WiFi=%d | TCP=%d\n",
         gI2cHealthy ? "OK" : "FAIL", gI2cFailCount, deviceConnected ? 1 : 0,
         gWifiConnected ? 1 : 0, gTcpConnected ? 1 : 0);
#endif
  }
}

// ============================================================================
// LOOP
// ============================================================================
void loop() {
  loopAliveTick();
  serialConsolePoll();
  ensureAdvertising();
  rtcUpdateCacheIfNeeded();
  photoTick();  // Capture transitions before commands or read processing.
  batteryTick();
  cutTick();
  bool otaActive = (gOtaTaskHandle != nullptr || gOtaState != OTA_IDLE);
  if (!otaActive) {
    if (gNetworkRestorePending) {
      gNetworkRestorePending = false;
      if (gWifiEnabled) wifiStartFromConfig();
    }
    peripheralProbeTick();
    if (gWifiEnabled) {
      bool before = gWifiConnected;
      gWifiConnected = (WiFi.status() == WL_CONNECTED);
      if (gWifiConnected) {
        tcpConnectTick();
        tcpPollRx();
        tcpWatchdog();
      } else {
        if (gTcpClient.connected()) gTcpClient.stop();
        gTcpConnected = false;
        gTcpState = TCP_IDLE;
        if (millis() - gWifiStartMs > 20000) {
          gWifiStartMs = millis();
          WiFi.reconnect();
        }
      }
      if (before != gWifiConnected || gTcpConnected != gTcpConnectedPrev) {
        gTcpConnectedPrev = gTcpConnected;
        netSendStatus();
      }
    }
    scanTick();
  }
  bleProcessPendingOnce();
  updateLedMode();
  ledEstadoBlinkTick();
  outboxSaveIfNeeded();
  eventTick();
  txTick();
  delay(1);
}

// ------------------- Validation, diagnostics and maintenance -----------------
static bool parseU32(const String &s, uint32_t &value) {
  if (s.isEmpty() || s.length() > 10) return false;
  uint64_t n = 0;
  for (size_t i = 0; i < s.length(); ++i) {
    if (!isDigit(s[i])) return false;
    n = n * 10 + (s[i] - '0');
    if (n > UINT32_MAX) return false;
  }
  value = (uint32_t)n;
  return true;
}
static void replyLine(bool ok, const String &text) {
  String s = String((char)(ok ? 0x06 : 0x15)) + text + "\r\n";
  sendFrame((const uint8_t *)s.c_str(), s.length());
}
static bool maintenanceAuthorized() {
#if REQUIRE_MAINT_AUTH
  return gMaintenanceAuthorized.load();
#else
  return true;
#endif
}
static void runtimeLoad() {
  Preferences p;
  p.begin("runtime3", false);
  // El codigo es la fuente de verdad. NVS solo recuerda el ultimo PIN aplicado
  // para detectar cambios y retirar las vinculaciones BLE anteriores.
  gMaintenancePin = PLC_MAINTENANCE_PIN;
  gPinChangedOnBoot = p.getString("pin", "") != gMaintenancePin;
  gOtaApPass = p.getString("apkey", "");
  if (gOtaApPass.length() != 16) {
    char pass[17];
    snprintf(pass, sizeof(pass), "%08lX%08lX", (unsigned long)esp_random(),
             (unsigned long)esp_random());
    gOtaApPass = pass;
    p.putString("apkey", gOtaApPass);
  }
  OTA_AP_PASS = gOtaApPass.c_str();
  gAckMode = p.getBool("ack", false);
  gHeartbeatEnabled = p.getBool("heartbeat", false);
  size_t stored = p.getBytesLength("outbox");
  bool valid = false;
  if (stored == sizeof(gOutbox)) {
    p.getBytes("outbox", &gOutbox, sizeof(gOutbox));
    valid = gOutbox.magic == 0x33504C43 && gOutbox.count <= OUTBOX_CAPACITY &&
            gOutbox.nextId > 0;
    for (unsigned i = 0; valid && i < gOutbox.count; ++i)
      valid = gOutbox.items[i].len > 0 &&
              gOutbox.items[i].len <= sizeof(gOutbox.items[i].frame) &&
              gOutbox.items[i].id > 0;
  }
  if (!valid) {
    memset(&gOutbox, 0, sizeof(gOutbox));
    gOutbox.magic = 0x33504C43;
    gOutbox.nextId = 1;
  }
  p.end();
  Serial.printf("\nPLC Scanner %s\nPIN mantenimiento: %s\nOTA AP clave: %s\n",
                FIRMWARE_VERSION, gMaintenancePin.c_str(), gOtaApPass.c_str());
}
static void handleTextCommand(const BleCmd &cmd) {
  String s(cmd.payload);
  if (s == "ID?" || s.startsWith("ID=")) {
    handleIdentityCommand(s, cmd.source);
    return;
  }
  if (s.startsWith("AUTH=")) {
    handleAuthCommand(s, cmd.source);
    return;
  }
  if (s.startsWith("ACK=")) {
    uint32_t id;
    if (!gAckMode || !parseU32(s.substring(4), id)) return;
    if (gOutbox.count && gOutbox.items[0].id == id) outboxPop();
    return;
  }
  if (s == "INFO?") {
    replyLine(true, "FW=" FIRMWARE_VERSION ";PROTO=V2;ACKMODE=" +
                        String(gAckMode ? 1 : 0) +
                        ";HEARTBEAT=" + String(gHeartbeatEnabled ? 1 : 0));
    return;
  }
  if (s == "DIAG?") {
    String d = "DIAG=RTC=" + String(gPeripheralOk[0] ? "OK" : "FAIL") +
               ";ADC=" + String(gPeripheralOk[1] ? "OK" : "FAIL");
    d += ";OUT=" + String(gPeripheralOk[2] ? "OK" : "FAIL") +
         ";B1_VALID=" + String(gBatValid[0] ? 1 : 0) +
         ";B2_VALID=" + String(gBatValid[1] ? 1 : 0);
    d += ";V1=" + String(gBatV1, 2) + ";V2=" + String(gBatV2, 2) +
         ";QUEUE=" + String(gOutbox.count);
    d += ";EVENT_OVERFLOW=" + String(gEventOverflow) +
         ";SCAN_OVERFLOW=" + String(gScanOverflow) +
         ";TX_DROP=" + String(gTxDropped);
    d += ";CUT=" +
         String(gCutConfirmed ? "CONFIRMED"
                              : (gCutRequested ? "PENDING" : "NO")) +
         ";REASON=" + String(gCutReason);
    replyLine(true, d);
    return;
  }
  if (cmd.source != 0 || !maintenanceAuthorized()) {
    replyLine(false, "ERR=AUTH_REQUIRED");
    return;
  }
  if (gOtaTaskHandle || gScanActive || gScanCount || gEventInFlight ||
      gOutbox.count) {
    replyLine(false, "ERR=BUSY");
    return;
  }
  bool ack = s.startsWith("ACKMODE="), heartbeat = s.startsWith("HEARTBEAT=");
  if (!ack && !heartbeat) {
    cfgSendErr();
    return;
  }
  String value = s.substring(ack ? 8 : 10);
  if (value != "0" && value != "1") {
    cfgSendErr();
    return;
  }
  Preferences p;
  p.begin("runtime3", false);
  if (ack) {
    gAckMode = value == "1";
    p.putBool("ack", gAckMode);
  } else {
    gHeartbeatEnabled = value == "1";
    p.putBool("heartbeat", gHeartbeatEnabled);
    gLastTcpRxMs = millis();
    gLastTcpPingMs = millis();
  }
  p.end();
  cfgSendOk();
}
// ------------------------ Persistent event outbox ---------------------------
static void outboxSaveIfNeeded() {
  if (!gOutboxDirty) return;
  static uint32_t lastFailure = 0;
  if (lastFailure && millis() - lastFailure < 1000) return;
  Preferences p;
  p.begin("runtime3", false);
  bool ok = p.putBytes("outbox", &gOutbox, sizeof(gOutbox)) == sizeof(gOutbox);
  p.end();
  if (ok)
    gOutboxDirty = false;
  else {
    lastFailure = millis();
    logLine("ERROR: no se ha podido persistir outbox; se conserva en RAM");
  }
}
static void outboxPop() {
  if (!gOutbox.count) return;
  for (unsigned i = 1; i < gOutbox.count; ++i)
    gOutbox.items[i - 1] = gOutbox.items[i];
  --gOutbox.count;
  gOutboxDirty = true;
  gEventLastAttempt = 0;
}
static void outboxPush(const String &frame) {
  if (frame.isEmpty() || frame.length() > sizeof(gOutbox.items[0].frame)) {
    ++gEventOverflow;
    replyLine(false, "ERR=EVENT_TOO_LONG");
    return;
  }
  if (gOutbox.count >= OUTBOX_CAPACITY) {
    ++gEventOverflow;
    replyLine(false, "ERR=EVENT_QUEUE_FULL");
    logLine("ERROR TRAZABILIDAD: cola eventos llena");
    return;
  }
  if (gOutbox.nextId == UINT32_MAX) {
    ++gEventOverflow;
    replyLine(false, "ERR=EVENT_ID_EXHAUSTED");
    return;
  }
  EventRecord &e = gOutbox.items[gOutbox.count++];
  e.id = gOutbox.nextId++;
  e.len = frame.length();
  memcpy(e.frame, frame.c_str(), e.len);
  gOutboxDirty = true;
}
static bool bleCanNotify() {
#if REQUIRE_BLE_PAIRING
  if (!gBleSecure) return false;
#endif
  return deviceConnected && gCccd && gCccd->getNotifications();
}
static void eventTick() {
  // Do not transmit unpersisted events. ACK mode safely tolerates duplicates.
  if (!gOutbox.count || gOutboxDirty || gEventInFlight || gCutConfirmed) return;
  if (!bleCanNotify() &&
      (gOtaTaskHandle || !gTcpConnected || !gTcpClient.connected()))
    return;
  if (gEventLastAttempt && millis() - gEventLastAttempt < 3000) return;
  TxPacket p = {};
  p.session = gBleSession;
  p.eventId = gOutbox.items[0].id;
  if (gAckMode) {
    int n = snprintf((char *)p.data, sizeof(p.data), "EVT=%lu\r\n",
                     (unsigned long)p.eventId);
    p.len = (uint16_t)n;
  }
  EventRecord &e = gOutbox.items[0];
  if (p.len + e.len > sizeof(p.data)) {
    ++gEventOverflow;
    return;
  }
  memcpy(p.data + p.len, e.frame, e.len);
  p.len += e.len;
  if (xQueueSend(gTxQueue, &p, 0) == pdTRUE) {
    gEventInFlight = true;
    gEventLastAttempt = millis();
  }
}
static int tcpWriteAvailable(const uint8_t *data, size_t len) {
  if (!gTcpClient.connected()) return -1;
  int n = ::send(gTcpClient.fd(), data, len, MSG_DONTWAIT);
  if (n > 0) return n;
  if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == ENOBUFS))
    return -2;
  return -1;
}
static void txTick() {
  static TxPacket p;
  static bool active = false, tcpDone = false, bleDone = false,
              accepted = false;
  static size_t offset = 0, tcpOffset = 0;
  static uint32_t started = 0, lastChunk = 0;
  if (!active) {
    if (xQueueReceive(gTxQueue, &p, 0) != pdTRUE) return;
    active = true;
    tcpDone = false;
    bleDone = false;
    accepted = false;
    offset = 0;
    tcpOffset = 0;
    started = millis();
  }
  if (!tcpDone) {
    if (gOtaTaskHandle || gOtaState != OTA_IDLE || !gTcpConnected ||
        !gTcpClient.connected())
      tcpDone = true;
    else {
      int n = tcpWriteAvailable(p.data + tcpOffset, p.len - tcpOffset);
      if (n > 0) {
        tcpOffset += (size_t)n;
        if (tcpOffset == p.len) {
          tcpDone = true;
          accepted = true;
        }
      } else if (n == -1) {
        gTcpClient.stop();
        gTcpConnected = false;
        tcpDone = true;
      }
      // Backpressure never blocks IO. Drop this connection after 2 s without
      // completing.
      if (!tcpDone && millis() - started > 2000) {
        gTcpClient.stop();
        gTcpConnected = false;
        tcpDone = true;
      }
    }
  }
  if (!bleDone) {
    if (!bleCanNotify() || p.session != gBleSession)
      bleDone = true;
    else if (millis() - lastChunk >= 15) {
      lastChunk = millis();
      size_t chunk = gBleMtu > 3 ? gBleMtu - 3 : 20;
      if (chunk > p.len - offset) chunk = p.len - offset;
      pCharacteristic->setValue(p.data + offset, chunk);
      pCharacteristic->notify();
      offset += chunk;
      if (offset == p.len) {
        bleDone = true;
        accepted = true;
      }
    }
  }
  if (millis() - started > 5000) bleDone = true;
  if (tcpDone && bleDone) {
    if (p.eventId) {
      if (accepted && !gAckMode && gOutbox.count &&
          gOutbox.items[0].id == p.eventId)
        outboxPop();
      gEventInFlight = false;
    }
    active = false;
  }
}
// --------------------------- Nonblocking scanning ---------------------------
static void enqueueScan(BleCmdType type, bool event) {
  if (gCutRequested) return;
  if (gScanCount >= 16) {
    ++gScanOverflow;
    replyLine(false, "ERR=SCAN_QUEUE_FULL");
    return;
  }
  ScanJob &j = gScanJobs[(gScanHead + gScanCount) % 16];
  j.type = type;
  j.event = event;
  strlcpy(j.iso, gRtcIso.c_str(), sizeof(j.iso));
  j.photo = gFotoEstado;
  ++gScanCount;
}
static String frameForScan(const ScanJob &j) {
  String f;
  f.reserve(480);
  f += (char)0x06;
  f += j.iso;
  f += (char)0x02;
  f += gId;
  f += (char)0x1B;
  f += (j.type == BLECMD_L ? gNivelBat4 : String("-"));
  f += (char)0x1B;
  f += (j.type == BLECMD_L || j.type == BLECMD_A ? gAltura : String("-"));
  f += (char)0x1B;
  f += (j.type == BLECMD_L ? j.photo : '-');
  f += (char)0x1B;
  f += (j.type == BLECMD_L || j.type == BLECMD_D ? gCodPalet : String("-"));
  f += (char)0x1B;
  f += (j.type == BLECMD_L || j.type == BLECMD_I ? gCodUbic : String("-"));
  f += (char)0x1B;
  f += (char)0x03;
  return f;
}
static void finishScan() {
  actualizarCampoBateriaBLE();
  String f = frameForScan(gCurrentScan);
  if (gCurrentScan.event)
    outboxPush(f);
  else
    sendFrame((const uint8_t *)f.c_str(), f.length());
  gScanActive = false;
  gScanPhase = 0;
}
static void beginHeight() {
  gAltura = "-";
  if (!gAlturaEnabled ||
      (gCurrentScan.type != BLECMD_L && gCurrentScan.type != BLECMD_A)) {
    finishScan();
    return;
  }
  clearRxBuffer_RS485();
  gHeightCount = 0;
  RS485.write(tramaRS,
              sizeof(tramaRS));  // UART driver controls half-duplex RTS.
  gScanStarted = millis();
  gScanPhase = 2;
}
static void scanTick() {
  if (gCutRequested) return;
  if (!gScanActive) {
    if (!gScanCount) return;
    gCurrentScan = gScanJobs[gScanHead];
    gScanHead = (gScanHead + 1) % 16;
    --gScanCount;
    gScanActive = true;
    gCodPalet = "-";
    gCodUbic = "-";
    gAltura = "-";
    if (gCurrentScan.type == BLECMD_A) {
      beginHeight();
      return;
    }
    while (PLCSerial.available()) PLCSerial.read();
    countersIncPon();
    PLCSerial.print("PON");
    gReaderBuffer = "";
    gReaderOverflow = false;
    gScanStarted = millis();
    gScanPhase = 1;
  }
  if (gScanPhase == 1) {
    bool complete = false;
    for (unsigned budget = 0; budget < 512 && PLCSerial.available(); ++budget) {
      char c = (char)PLCSerial.read();
      if (c == '\n') {
        complete = true;
        break;
      }
      if (c == '\r') continue;
      if ((uint8_t)c < 32 || c == 127) {
        gReaderOverflow = true;
        continue;
      }
      if (gReaderBuffer.length() < 320)
        gReaderBuffer += c;
      else
        gReaderOverflow = true;
    }
    if (complete || millis() - gScanStarted >= LECTOR_TIMEOUT_MS) {
      if (!complete || gReaderOverflow || !parseReaderFrame(gReaderBuffer)) {
        gCodPalet = "-";
        gCodUbic = "-";
        countersIncLecturaFallida();
      }
      beginHeight();
    }
  } else if (gScanPhase == 2) {
    while (RS485.available() && gHeightCount < 3)
      gHeightReply[gHeightCount++] = (uint8_t)RS485.read();
    if (gHeightCount == 3) {
      float m = ((gHeightReply[0] & 0x3F) * 4096UL +
                 (gHeightReply[1] & 0x3F) * 64UL + (gHeightReply[2] & 0x3F)) /
                1000.0f;
      if (m >= 0.2f && m <= 20.0f) gAltura = formatAltura(m);
      finishScan();
    } else if (millis() - gScanStarted >= 250) {
      gAltura = "-";
      finishScan();
    }
  }
}
// ------------------------- Photocell and inactivity -------------------------
static void photoTick() {
  uint32_t now = millis();
  int raw = digitalRead(PIN_SENSOR_PALET);
  if (!gFotoLista) {
    if (raw != gFotoUltLect) {
      gFotoUltLect = raw;
      gFotoUltCambioMs = now;
    }
    if (now - gFotoUltCambioMs < FOTO_STABLE_MS) return;
    gFotoLista = true;
    estadoSensorFiltrado = raw;
    lecturaAnteriorRaw = raw;
    ultimoRebote = now;
    PaletCargado = raw == HIGH;
    gFotoEstado = PaletCargado ? '1' : '0';
    armadoInactividad = true;
    lastFotoActivaMs = now;
    logLine("Fotocelula estable: eventos habilitados");
  }
  if (raw != lecturaAnteriorRaw) {
    lecturaAnteriorRaw = raw;
    ultimoRebote = now;
  }
  if (now - ultimoRebote >= retardoRebote && raw != estadoSensorFiltrado) {
    estadoSensorFiltrado = raw;
    PaletCargado = raw == HIGH;
    gFotoEstado = PaletCargado ? '1' : '0';
    if (PaletCargado)
      countersIncPalCargado();
    else {
      countersIncPalDescargado();
      lastFotoActivaMs = now;
    }
    if (gOtaTaskHandle) {
      // Unexpected movement in maintenance: abort OTA and record a partial
      // event.
      gOtaAbort = true;
      gCodPalet = "-";
      gCodUbic = "-";
      gAltura = "-";
      ScanJob j = {};
      j.type = BLECMD_L;
      j.event = true;
      j.photo = gFotoEstado;
      strlcpy(j.iso, gRtcIso.c_str(), sizeof(j.iso));
      outboxPush(frameForScan(j));
      replyLine(false, "ERR=MOVEMENT_DURING_OTA");
    } else
      enqueueScan(BLECMD_L, true);
  }
  if (!gOtaTaskHandle && armadoInactividad && !PaletCargado &&
      now - lastFotoActivaMs >= FOTO_INACTIVIDAD_MS)
    powerCut("INACTIVITY_120_MIN");
}
// ---------------------------- Battery supervision ---------------------------
static void batteryTick() {
  uint32_t now = millis();
  if (now - lastBatSafetyCheck < BAT_SAFETY_PERIOD) return;
  lastBatSafetyCheck = now;
  actualizarNivelBateria(0, K_ADC_BAT1, gNivelBat1, "BAT1", false);
  actualizarNivelBateria(1, K_ADC_BAT2, gNivelBat2, "BAT2", false);
  actualizarCampoBateriaBLE();
  if (!gBatValid[0] || !gBatValid[1]) {
    vWarnSinceMs = 0;
    vCollapseSinceMs = 0;
    batCriticalSinceMs = 0;
    // Never interpret ADC communication errors as physical absence.
    return;
  }
  gBat1Present = gBatV1 > V_BAT_ABSENT;
  gBat2Present = gBatV2 > V_BAT_ABSENT;
  if (gBat1Present != gBat1PresentPrev || gBat2Present != gBat2PresentPrev)
    sendResponse_B_Cached();
  gBat1PresentPrev = gBat1Present;
  gBat2PresentPrev = gBat2Present;
  if (!gBat1Present && !gBat2Present) {
    vWarnSinceMs = 0;
    vCollapseSinceMs = 0;
    batCriticalSinceMs = 0;
    return;
  }
  float v = gBat1Present ? gBatV1 : gBatV2;
  if (gBat1Present && gBat2Present) {
#if BATTERY_CUT_ONLY_WHEN_ALL_LOW
    v = fmaxf(gBatV1, gBatV2);
#else
    v = fminf(gBatV1, gBatV2);
#endif
  }
  int p1 = gBat1Present ? pctFrom2Digits(gNivelBat1) : 99,
      p2 = gBat2Present ? pctFrom2Digits(gNivelBat2) : 99;
  batteryWarnIfNeeded(p1 < p2 ? p1 : p2);
  if (v <= V_CUTOFF_COLLAPSE) {
    if (!vCollapseSinceMs) vCollapseSinceMs = now;
    if (now - vCollapseSinceMs >= V_COLLAPSE_CONFIRM_MS)
      powerCut("BAT_VOLTAGE_COLLAPSE");
  } else
    vCollapseSinceMs = 0;
  if (v <= V_CUTOFF_WARN) {
    if (!vWarnSinceMs) vWarnSinceMs = now;
    if (now - vWarnSinceMs >= V_WARN_CONFIRM_MS) powerCut("BAT_VOLTAGE_LOW");
  } else
    vWarnSinceMs = 0;
  // 5% is a warning; voltage is the cutoff reference. Avoid two conflicting
  // cutoffs.
}
static void cutTick() {
  if (!gCutRequested || gCutConfirmed) return;
  if (millis() - gCutRequestedMs < 750 ||
      (gLastCutAttemptMs && millis() - gLastCutAttemptMs < 500))
    return;
  gLastCutAttemptMs = millis();
  gOtaAbort = true;
#if ENABLE_POWER_CUT
  if (setPin(O_RELE_MOTOR, false)) {
    // Verify output register latch; not proof of mechanical relay actuation.
    Wire.beginTransmission(I2C_OUT_ADDR);
    Wire.write(0x01);
    bool ok = Wire.endTransmission(false) == 0;
    if (ok && Wire.requestFrom((uint8_t)I2C_OUT_ADDR, (uint8_t)1) == 1) {
      uint8_t latch = Wire.read();
      if (latch & (1 << O_RELE_MOTOR)) {
        gCutConfirmed = true;
        logLine("POWER CUT: registro confirmado");
      }
    }
  }
  if (!gCutConfirmed) logLine("POWER CUT: pendiente; se reintentara");
#else
  gCutConfirmed = true;
  logLine("POWER CUT simulado (ENABLE_POWER_CUT=0)");
#endif
}
// --------------------------- Peripheral recovery ----------------------------
static void peripheralProbeTick() {
  if (millis() - gLastProbeMs < 5000) return;
  gLastProbeMs = millis();
  gRtcOk = rtcProbe();
  gPeripheralOk[0] = gRtcOk && !gRtcIso.startsWith("0000-");
  if (!gAdsOk || !gBatValid[0] || !gBatValid[1]) {
    gAdsOk = ads.begin();
    if (gAdsOk) ads.setGain(GAIN_ONE);
  }
  Wire.beginTransmission(I2C_OUT_ADDR);
  Wire.write(0x03);
  Wire.write(0x00);
  bool ok = Wire.endTransmission() == 0;
  // Reassert desired latch, including an already requested OFF.
  if (gCutRequested && millis() - gCutRequestedMs >= 750)
    estado |= (1 << O_RELE_MOTOR);
  if (ok) ok = writeOutput();
  gPeripheralOk[2] = ok;
}

static void otaSetLastErr(const String &error) {
  xSemaphoreTake(gOtaErrorMutex, portMAX_DELAY);
  gOtaLastErr = error;
  xSemaphoreGive(gOtaErrorMutex);
}
static String otaGetLastErr() {
  xSemaphoreTake(gOtaErrorMutex, portMAX_DELAY);
  String result = gOtaLastErr;
  xSemaphoreGive(gOtaErrorMutex);
  return result;
}

// Se ejecuta despues de BLEDevice::init y antes de iniciar el advertising.
static void applyConfiguredPin() {
  if (!gPinChangedOnBoot) return;
  bool ok = true;
  int count = esp_ble_get_bond_device_num();
  if (count < 0) {
    ok = false;
  } else if (count > 0) {
    esp_ble_bond_dev_t *devices =
        (esp_ble_bond_dev_t *)calloc((size_t)count, sizeof(esp_ble_bond_dev_t));
    if (!devices)
      ok = false;
    else {
      if (esp_ble_get_bond_device_list(&count, devices) != ESP_OK)
        ok = false;
      else {
        for (int i = 0; i < count; ++i) {
          if (esp_ble_remove_bond_device(devices[i].bd_addr) != ESP_OK)
            ok = false;
        }
      }
      free(devices);
    }
  }
  if (ok) {
    Preferences p;
    if (p.begin("runtime3", false)) {
      ok = p.putString("pin", gMaintenancePin) > 0;
      p.end();
    } else
      ok = false;
  }
  if (ok) {
    gPinChangedOnBoot = false;
    logLine(
        "PIN del codigo aplicado. Si ya estaba vinculado, olvidar el PLC en "
        "Android y volver a emparejar.");
  } else {
    logLine(
        "AVISO: PIN del codigo activo; no se pudo completar la retirada de "
        "vinculos o guardar la referencia. Se reintentara al reiniciar.");
  }
}

// Authorization belongs to this BLE connection, not to a wall-clock deadline.
static void handleAuthCommand(const String &s, uint8_t source) {
  if (source != 0) return;
  if (s == "AUTH=OFF") {
    gMaintenanceAuthorized = false;
    replyLine(true, "AUTH=OFF");
    return;
  }
  if (gAuthBlockedUntil && (int32_t)(gAuthBlockedUntil - millis()) > 0) {
    gMaintenanceAuthorized = false;
    replyLine(false, "ERR=AUTH_LOCKED");
    return;
  }
  if (s.substring(5) == gMaintenancePin) {
    gMaintenanceAuthorized = true;
    gAuthFails = 0;
    gAuthBlockedUntil = 0;
    replyLine(true, "AUTH=OK;TTL=SESSION");
  } else {
    gMaintenanceAuthorized = false;
    if (++gAuthFails >= 5) {
      gAuthFails = 0;
      gAuthBlockedUntil = millis() + 60000UL;
    }
    replyLine(false, "ERR=AUTH");
  }
}

// ---------------------- Identidad configurable ------------------------------
// ASCII limita bytes en scan response y excluye delimitadores del protocolo.
static bool validEquipmentId(const String &id) {
  if (id.length() < 1 || id.length() > 20 || id[0] == ' ' ||
      id[id.length() - 1] == ' ') return false;
  for (size_t i = 0; i < id.length(); ++i) {
    const char c = id[i];
    if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
          c == '-' || c == '_' || c == ' ')) return false;
  }
  return true;
}

static void identityLoad() {
  Preferences p;
  String saved;
  if (p.begin("identity", true)) {
    saved = p.getString("id", "");
    p.end();
  }
  gCustomId = validEquipmentId(saved);
  gId = gCustomId ? saved : gHardwareId;
  // Conservar el nombre de fabrica hasta configurar un ID propio.
  gBluetoothName = gCustomId ? gId : "ISP-" + gHardwareId;
}

static bool applyBluetoothIdentity(const String &name) {
  if (esp_ble_gap_set_device_name(name.c_str()) != ESP_OK) return false;
  BLEAdvertisementData response;
  response.setName(name.c_str());
  String payload = response.getPayload();
  // setup ya activa scan response personalizado. Comprobar el resultado de la
  // API real permite rechazar el cambio si el controlador no lo acepta.
  return esp_ble_gap_config_scan_rsp_data_raw(
             (uint8_t *)payload.c_str(), payload.length()) == ESP_OK;
}

static void identitySendCurrent() {
  replyLine(true, "ID=" + gId + ";HWID=" + gHardwareId +
                      ";NAME=" + gBluetoothName);
}

static void handleIdentityCommand(const String &s, uint8_t source) {
  if (s == "ID?") {
    identitySendCurrent();
    return;
  }
  if (source != 0 || !maintenanceAuthorized()) {
    replyLine(false, "ERR=AUTH_REQUIRED");
    return;
  }
  String id = s.substring(3);
  id.trim();
  id.toUpperCase();
  if (!validEquipmentId(id)) {
    replyLine(false, "ERR=ID_INVALID");
    return;
  }
  // No mezclar identidades dentro de trabajos o eventos pendientes de entrega.
  if (gCutRequested || gOtaTaskHandle || gOtaState != OTA_IDLE ||
      gScanActive || gScanCount || gEventInFlight || gOutbox.count ||
      !gFotoLista) {
    replyLine(false, "ERR=ID_BUSY");
    return;
  }
  Preferences p;
  if (!p.begin("identity", false)) {
    replyLine(false, "ERR=ID_STORAGE");
    return;
  }
  if (!applyBluetoothIdentity(id)) {
    applyBluetoothIdentity(gBluetoothName);
    p.end();
    replyLine(false, "ERR=ID_BLUETOOTH");
    return;
  }
  if (p.putString("id", id) != id.length()) {
    applyBluetoothIdentity(gBluetoothName);
    p.end();
    replyLine(false, "ERR=ID_STORAGE");
    return;
  }
  p.end();
  gId = id;
  gCustomId = true;
  gBluetoothName = id;
  identitySendCurrent();
  // Se mantiene el enlace actual. Al volver a buscar se anuncia el nuevo ID.
  // PIN, bonds, HWID y SSID OTA no cambian.
}
