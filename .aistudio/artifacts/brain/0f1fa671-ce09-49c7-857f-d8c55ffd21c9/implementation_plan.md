# Stream Relay: Audio Stabilization & Performance Roadmap

A clear, actionable plan to eliminate audio cutouts, automate device discovery, and achieve ultra-low latency without relying on impractical Bluetooth data transmission.

---

## 1. Architectural Clarification: Bluetooth vs. Wi-Fi

| Mechanism | Bandwidth | Typical Local Latency | Verdict for Video/Audio Relay |
| :--- | :--- | :--- | :--- |
| **Bluetooth RFCOMM/SPP** | ~0.8 – 1.5 Mbps | 30 – 100 ms | **Impractical**: Video requires 15–40 Mbps; Bluetooth packet scheduling introduces high latency. |
| **Bluetooth Audio (A2DP)** | ~0.3 Mbps | 120 – 250 ms | **Too slow**: High encode/decode buffer delay causes severe audio lag. |
| **Bluetooth Radio Coexistence** | Shared 2.4 GHz antenna | Variable jitter | **Degrading**: Transmitting Bluetooth concurrently causes Wi-Fi packet drops on mobile chips. |
| **BLE for Discovery Only** | Minimal | N/A (beacon only) | **Viable**: Transmits tiny beacon packets solely to advertise IP and port for zero-config pairing. |
| **Wi-Fi LAN / Hotspot** | 50 – 500+ Mbps | 1 – 5 ms | **Optimal**: High throughput, microsecond hardware buffers, supports 60 FPS truecolor feed. |

**Conclusion:** Keep all audio and video streaming on Wi-Fi sockets. Focus optimization on adaptive jitter buffering, zero-config discovery, and peer-to-peer/hotspot direct streaming.

---

## 2. Priority 1: Eliminating Audio Cutouts (Adaptive Jitter Buffer)

### Problem Diagnosis
The current fixed ~60 ms audio queue cuts out whenever a brief Wi-Fi retry or packet burst arrives 10–20 ms late. The `AudioTrack` drains to empty, forcing an audible silence gap before refilling.

### Solution Design
1. **Dynamic Low-Latency Watermark:**
   - Maintain a dynamic buffer target of **50–80 ms** during stable transmission.
   - If transmission jitter spikes, automatically scale buffer depth up to **100 ms** to absorb fluctuations, then gently drift back down once stable.
2. **Concealment over Hard Stalls:**
   - In case of an underrun, write a micro-silence fade rather than hard stopping `AudioTrack`, avoiding audible clicks and speaker pops.
3. **Audio-Video Desync Prevention:**
   - If the audio queue exceeds 120 ms, drop the oldest chunk in micro-steps (20 ms at a time) so audio instantly re-syncs to live video without a sudden stutter.
4. **Selectable Latency Modes in UI:**
   - **Game Mode (Low Latency):** 40–60 ms buffer target (minimum latency for fast-paced gaming).
   - **Balanced Mode (Default):** 70–90 ms buffer target (smooth audio even on typical home Wi-Fi).
   - **Smooth Mode:** 120–150 ms buffer target (impermeable to congested networks).

---

## 3. Priority 2: Zero-Config Auto-Discovery (No Manual IP Typing)

### Design
- **Transmitter (TX):** Broadcasts a lightweight UDP discovery beacon on port `4122` every 1 second when relay is active:
  `{"service": "StreamRelay", "videoPort": 4120, "audioPort": 4121, "device": "Pixel 7"}`
- **Receiver (RX):** Listens on port `4122`. When a beacon is detected, automatically populates the transmitter's IP address and displays a one-tap **"Connect to TX"** banner.
- **Manual Override:** Retains the manual IP entry field for networks with UDP broadcast isolation.

---

## 4. Priority 3: Immersive RX Display & Quality Presets

1. **Aspect Ratio Modes on RX:**
   - Quick toggle bar: `Fit` (preserves 16:9), `Crop/Fill` (fills tablet screen edge-to-edge), `16:9`, and `4:3`.
2. **TX Quality Profiles:**
   - **Performance:** 720p @ 60 FPS, JPEG quality 82 (~40 KB/frame, lowest bandwidth).
   - **High Fidelity:** 1080p @ 60 FPS, JPEG quality 92 (~90 KB/frame, pristine gradients).

---

## 5. Technical Implementation Steps

1. **`RelayClient.kt`**:
   - Upgrade the audio worker thread with an adaptive sliding-window jitter buffer (`AdaptiveJitterBuffer`).
   - Add latency mode configuration (`Game`, `Balanced`, `Smooth`).
2. **`DiscoveryService.kt`**:
   - Implement `DiscoveryBeaconBroadcaster` on TX.
   - Implement `DiscoveryBeaconListener` on RX.
3. **`RxScreen.kt` & `TxScreen.kt`**:
   - Add auto-discovered device card to the RX screen for one-tap connection.
   - Add aspect ratio toggle and latency profile selector to RX quick bar.
4. **Verification**:
   - Verify build with `compile_applet`.
   - Test audio playback stability under simulated burst delays.
