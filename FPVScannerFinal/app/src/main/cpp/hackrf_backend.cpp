#include <jni.h>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>
#include <cmath>
#include <complex>
#include <chrono>
#include <cstdio>
#include <cstdint>

#include "hackrf.h"

// ---------------------- GLOBALS ----------------------

static hackrf_device* g_dev = nullptr;
static std::atomic<bool> g_connected(false);
static std::atomic<bool> g_scanning(false);

static std::thread g_scan_thread;

static std::mutex g_det_mutex;
static std::string g_last_detection;

// простий спектр (на майбутнє, зараз не віддаємо в UI)
static std::mutex g_spec_mutex;
static std::vector<float> g_spectrum(64, 0.0f);

// EMA
static std::atomic<double> g_noise_ema(0.0);
static std::atomic<double> g_power_ema(0.0);

// коефіцієнт спрацювання (у скільки разів потужність має
// перевищувати шум, щоб вважати це дроном)
static std::atomic<double> g_detect_ratio(2.3);

// band select: 0=Auto,1=1.2,2=2.4,3=3.3,4=5.8
static std::atomic<int> g_band_mode(0);

// gain settings
static std::atomic<int>  g_lna_gain(24);
static std::atomic<int>  g_vga_gain(20);
static std::atomic<bool> g_amp_on(true);

// поточна частота, на якій ми слухаємо (оновлюється в scan_loop)
static std::atomic<uint64_t> g_current_freq_hz(300000000ULL);

// ------------------------------------------------------
// Scan frequencies
// ------------------------------------------------------

struct ScanFreq {
    uint64_t hz;
    const char* label;
};

static const ScanFreq g_scan_freqs[] = {
    {300000000ULL,   "300 MHz"},
    {450000000ULL,   "450 MHz"},
    {600000000ULL,   "600 MHz"},
    {750000000ULL,   "750 MHz"},
    {900000000ULL,   "900 MHz"},
    {1050000000ULL,  "1050 MHz"},

    {1200000000ULL,  "1200 MHz"},
    {1350000000ULL,  "1350 MHz"},

    {2400000000ULL,  "2400 MHz"},
    {3300000000ULL,  "3300 MHz"},
    {5800000000ULL,  "5800 MHz"},
};

static const size_t FREQ_COUNT =
        sizeof(g_scan_freqs) / sizeof(g_scan_freqs[0]);

static const int SCAN_STEP_MS = 700;

// ------------------------------------------------------

static void set_last_detection(const std::string& s) {
    std::lock_guard<std::mutex> lock(g_det_mutex);
    g_last_detection = s;
}

static inline double ema_update(double prev, double v, double a) {
    return prev + a * (v - prev);
}

static bool freq_allowed(uint64_t hz, int band) {
    double mhz = hz / 1e6;

    if (band == 0) return true;
    if (band == 1) return mhz >= 1100 && mhz <= 1400;  // 1.2–1.3
    if (band == 2) return mhz >= 2300 && mhz <= 2500;  // 2.4
    if (band == 3) return mhz >= 3200 && mhz <= 3400;  // 3.3
    if (band == 4) return mhz >= 5700 && mhz <= 5900;  // 5.8

    return true;
}

// ------------------------------------------------------
// HackRF RX callback
// ------------------------------------------------------

static int rx_callback(hackrf_transfer* transfer) {
    if (!g_scanning.load()) return 0;

    const uint8_t* buf = transfer->buffer;
    size_t count = transfer->valid_length / 2;
    if (count == 0) return 0;

    double sum_power = 0.0;
    for (size_t i = 0; i < count; i++) {
        int I = (int)buf[2 * i]     - 128;
        int Q = (int)buf[2 * i + 1] - 128;
        sum_power += (double)I * I + (double)Q * Q;
    }

    double avg_power = sum_power / (double)count;

    // EMA
    static bool init_noise = false;
    double noise_ema = g_noise_ema.load();
    double power_ema = g_power_ema.load();

    if (!init_noise) {
        noise_ema = avg_power;
        power_ema = avg_power;
        init_noise = true;
    } else {
        power_ema = ema_update(power_ema, avg_power, 0.05);
        double ratio = g_detect_ratio.load();
        if (avg_power < noise_ema * ratio) {
            noise_ema = ema_update(noise_ema, avg_power, 0.05);
        }
    }

    g_noise_ema.store(noise_ema);
    g_power_ema.store(power_ema);

    // Перевірка на «дрон»
    double ratio = (noise_ema > 1e-9) ? (power_ema / noise_ema) : 0.0;
    if (ratio > g_detect_ratio.load()) {
        double db = 10.0 * std::log10(ratio);

        uint64_t freq_hz = g_current_freq_hz.load();
        double freq_mhz = (double)freq_hz / 1e6;

        char msg[256];
        std::snprintf(msg, sizeof(msg),
                      "FREQ=%.1f MHz; POWER=%d; NOISE=%d; DELTA_DB=%.0f",
                      freq_mhz, (int)power_ema, (int)noise_ema, db);

        set_last_detection(msg);
    }

    return 0;
}

// ------------------------------------------------------
// SCAN LOOP
// ------------------------------------------------------

static void scan_loop() {
    size_t idx = 0;
    auto last_switch = std::chrono::steady_clock::now();

    while (g_scanning.load()) {
        auto now = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - last_switch).count();

        if (ms > SCAN_STEP_MS) {
            int band = g_band_mode.load();

            size_t guard = 0;
            while (!freq_allowed(g_scan_freqs[idx].hz, band) &&
                   guard < FREQ_COUNT) {
                idx = (idx + 1) % FREQ_COUNT;
                ++guard;
            }

            uint64_t freq_hz = g_scan_freqs[idx].hz;
            hackrf_set_freq(g_dev, freq_hz);
            g_current_freq_hz.store(freq_hz);

            last_switch = now;
            idx = (idx + 1) % FREQ_COUNT;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}

// ------------------------------------------------------
// JNI
// ------------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_fpvscanner_MainActivity_nativeTestBackend(
        JNIEnv* env,
        jobject /*thiz*/) {
    return env->NewStringUTF("HackRF backend OK (direct USB)");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_fpvscanner_MainActivity_nativeSetBandMode(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint mode) {
    if (mode < 0) mode = 0;
    if (mode > 4) mode = 4;
    g_band_mode.store((int)mode);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_fpvscanner_MainActivity_nativeSetGain(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint lna,
        jint vga,
        jboolean amp) {
    g_lna_gain.store((int)lna);
    g_vga_gain.store((int)vga);
    g_amp_on.store(amp == JNI_TRUE);

    if (g_dev) {
        hackrf_set_lna_gain(g_dev, g_lna_gain.load());
        hackrf_set_vga_gain(g_dev, g_vga_gain.load());
        hackrf_set_amp_enable(g_dev, g_amp_on.load() ? 1 : 0);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_fpvscanner_MainActivity_nativeStartScan(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (g_scanning.load()) return JNI_TRUE;

    if (hackrf_init() != HACKRF_SUCCESS) {
        set_last_detection("hackrf_init() failed");
        g_connected.store(false);
        return JNI_FALSE;
    }

    if (hackrf_open(&g_dev) != HACKRF_SUCCESS || !g_dev) {
        set_last_detection("hackrf_open() failed");
        g_connected.store(false);
        hackrf_exit();
        return JNI_FALSE;
    }

    g_connected.store(true);

    // базові параметри
    hackrf_set_sample_rate(g_dev, 10000000);              // 10 MS/s
    hackrf_set_baseband_filter_bandwidth(g_dev, 10000000);

    hackrf_set_amp_enable(g_dev, g_amp_on.load() ? 1 : 0);
    hackrf_set_lna_gain(g_dev, g_lna_gain.load());
    hackrf_set_vga_gain(g_dev, g_vga_gain.load());

    // стартова частота
    g_current_freq_hz.store(g_scan_freqs[0].hz);
    hackrf_set_freq(g_dev, g_scan_freqs[0].hz);

    if (hackrf_start_rx(g_dev, rx_callback, nullptr) != HACKRF_SUCCESS) {
        set_last_detection("hackrf_start_rx() failed");
        hackrf_close(g_dev);
        g_dev = nullptr;
        hackrf_exit();
        g_connected.store(false);
        return JNI_FALSE;
    }

    g_scanning.store(true);
    g_scan_thread = std::thread(scan_loop);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_fpvscanner_MainActivity_nativeStopScan(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    g_scanning.store(false);

    if (g_scan_thread.joinable()) {
        g_scan_thread.join();
    }

    if (g_dev) {
        hackrf_stop_rx(g_dev);
        hackrf_close(g_dev);
        g_dev = nullptr;
    }

    hackrf_exit();
    g_connected.store(false);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_fpvscanner_MainActivity_nativeIsDeviceConnected(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    return g_connected.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_fpvscanner_MainActivity_nativeGetLastDetection(
        JNIEnv* env,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_det_mutex);
    if (g_last_detection.empty()) return nullptr;
    return env->NewStringUTF(g_last_detection.c_str());
}
