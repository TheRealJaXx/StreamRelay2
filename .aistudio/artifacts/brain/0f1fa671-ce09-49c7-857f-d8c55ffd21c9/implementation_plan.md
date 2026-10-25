# StreamRelay / Capture Studio: Streaming Stabilization & Low-Latency Audio

## 1. Protocol Architecture: OMT vs. Dual-TCP Socket Pipeline
- **Connection & Discovery:** Uses OMT's standardized discovery mechanisms:
  - DNS-SD / mDNS advertising and discovery under the `_omt._tcp` service type via Android's `NsdManager`.
  - UDP broadcast announcement beacons on port 6399 (`OmtDiscoveryManager.kt`).
- **Media Transmission:** Dedicated independent dual-TCP socket channels:
  - **Port $P$ (Video):** MJPEG frame stream with 16-byte protocol header (magic `0x53524C59`, length, sequence, flags). Conflated TX hand-off with buffer pooling.
  - **Port $P+1$ (Audio):** Length-prefixed 48 kHz 16-bit uncompressed Stereo PCM stream.
  - **Port $P+2$ (RTT):** 64-bit nanosecond echo probe for real-time round-trip latency statistics ($R_{avg}$ and $R_{95}$).

---

## 2. Audio Break-Up Root Causes & Targeted Solutions

### Cause 1: TX Partial Driver Reads & Frame Alignment Breaking
- **Issue:** ALSA / USB AudioRecord returned partial buffer reads (e.g., 1024 or 2048 bytes instead of 3840 bytes). Delivering partial or odd-byte slices broke 16-bit PCM word alignment and mono-to-stereo conversion, introducing clicks and stutter.
- **Fix in `AudioCaptureEngine.kt`:** Added an aligned frame accumulator buffer ensuring every emitted audio chunk is strictly 3840 bytes (960 stereo samples = 20.00 ms at 48 kHz). Elevated thread priority to `THREAD_PRIORITY_URGENT_AUDIO`.

### Cause 2: TX Audio Channel Buffer Starvation on Wi-Fi Jitter
- **Issue:** Transmitter audio channel capacity was only 6 chunks (120 ms). Any momentary Wi-Fi airtime contention (>100 ms) overflowed the channel, causing `trySend` to drop audio chunks before they reached the network socket.
- **Fix in `RelayServer.kt`:** Expanded `audioChannel` capacity to 30 chunks (600 ms headroom, <115 KB RAM) and configured socket traffic class `0x10` (`IPTOS_LOWDELAY`).

### Cause 3: TX Video Saturation Starving Shared Wi-Fi Airtime
- **Issue:** When heavy video frames stalled during transmission, they saturated Wi-Fi airtime and delayed time-sensitive audio TCP packets.
- **Fix in `RelayServer.kt`:** Lowered the socket write threshold to $1.2\times$ frame interval ($<20\text{ ms}$ at 60 FPS) to proactively drop the next video frame if network backpressure is detected, instantly vacating Wi-Fi airtime for audio delivery.

### Cause 4: RX 20ms Blocking Silence Stalls & Premature Packet Dropping
- **Issue:** Whenever the receiver jitter queue was momentarily empty (1–2 ms), the playback thread executed a blocking `track.write(silenceChunk, 0, 3840)` in `MODE_STREAM`, freezing the thread for 20 ms. During those 20 ms, incoming TCP packets accumulated, exceeded the strict 80 ms cap, and were discarded by `audioJitterQueue.pollFirst()`.
- **Fix in `RelayClient.kt`:** Replaced the 20 ms blocking silence with a 4 ms lock wait (`audioJitterLock.wait(4)`), woken immediately by `audioJitterLock.notify()` when new chunks arrive. Relaxed GAME profile bounds to 30 ms target prebuffer and 120 ms cap. If prolonged starvation occurs (>20 ms), writes only 5 ms (960 bytes) of comfort silence.

---

## 3. Custom Adaptive App Icon
- Updated app launcher icons across all screen densities (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) with the user's custom stylized cyan/white double-arrow 'S' on `#00183A` dark navy background.
