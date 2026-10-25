# StreamRelay2: Background Screen-Lock Streaming, Custom Sequenced UDP Transport & Hardware H.264/HEVC Encoding

A comprehensive, low-latency roadmap to keep the transmitter streaming continuously with the screen locked, transition the media pipeline from dual TCP to custom sequenced UDP to eradicate transport stalls, and integrate hardware H.264/HEVC encoding to cut bandwidth by 80% and eliminate thermal throttling.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following user preferences were confirmed in Phase 1 and govern the architectural strategy:
> 1. **Screen Locking & Thermal Management:** Use a full Android Foreground Service with CPU `PARTIAL_WAKE_LOCK` and high-performance `WIFI_MODE_FULL_HIGH_PERF` WifiLock, keeping USB video and audio capturing continuously while the device screen is completely locked.
> 2. **Transport Evolution:** Transition both audio and video streams to a custom UDP protocol with packet sequencing, lightweight header framing, and reassembly to eliminate TCP head-of-line blocking on noisy Wi-Fi.
> 3. **Next Feature Priority:** Introduce Android `MediaCodec` hardware H.264/HEVC video encoding to replace MJPEG, dropping network bandwidth from ~30 Mbps to 3–6 Mbps, dramatically reducing transmitter heat and power consumption.

---

## 1. Overview & Core Concept

* **The Problem:** 
  1. As soon as the transmitter screen locks or turns off, the Android OS enters device doze/sleep, cutting CPU cycles and throttling the Wi-Fi radio. This immediately halts audio and video packet transmission.
  2. TCP guarantees in-order delivery via retries. While our tuned buffers minimized dropouts, severe 2.4/5 GHz packet collisions still cause head-of-line blocking where one delayed packet holds up the entire audio/video stream.
  3. MJPEG encodes independent JPEG frames, consuming 20–40 Mbps of Wi-Fi bandwidth. This heavy network load generates significant thermal load on both transmitting and receiving mobile devices.
* **The Solution:**
  1. **Robust Screen-Off Background Streaming:** Upgrade `RelayServerService` to acquire a reference-counted CPU `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_HIGH_PERF` `WifiLock`. Transfer UVC capture and audio capture ownership to the service lifecycle so playback never halts when the user taps the power button.
  2. **Custom Sequenced UDP Transport:** Implement a lightweight, low-overhead UDP protocol. Each packet carries a 12-byte header (Stream ID, 32-bit sequence number, timestamp, fragment flags). Audio packets are dispatched as unfragmented 20 ms datagrams; video frames are fragmented into MTU-safe chunks (~1400 bytes) with instantaneous assembly and frame-skipping on the receiver.
  3. **Hardware H.264/HEVC Encoding:** Feed the USB capture card's frames into Android's low-latency hardware encoder (`MediaCodec` with `COLOR_FormatSurface` or byte buffer input, `KEY_LATENCY = 0`, and periodic I-frames).

---

## 2. User Experience & Visual Design

* **Transmitter (TX) Experience:**
  - **Screen Lock Indicator & Thermal Shield:** An active "Background Service Armed" badge in the TX header displays when the foreground service, wake lock, and Wi-Fi locks are verified.
  - **Power-Save Screen Off Hint:** A dedicated banner confirms: *"Safe to lock screen: Relay will run at full performance in background with display powered down to prevent device heating."*
  - **Transport & Codec Selector:** Clear UI controls to switch between **Sequenced UDP (Ultra Low Latency)** and legacy **TCP (Reliable)**, as well as choosing **H.264 Hardware (Low Heat / Low Bandwidth)** vs **MJPEG (Legacy Raw)**.
* **Receiver (RX) Experience:**
  - **Real-Time Packet Health Bar:** Visual latency indicator showing UDP packet arrival jitter, dropped packet percentage, and instantaneous bitrate.
  - **Seamless Hardware Decoder:** Auto-detects whether the incoming stream is H.264 NAL units or MJPEG, routing directly to `MediaCodec` surface rendering without user intervention.

---

## 3. Key Product Decisions & Trade-Offs

* **Decision 1: Service Architecture for Screen-Off Survival**
  - *Chosen Approach:* Upgrade `RelayServerService` to manage the actual lifecycles of `RelayServer`, `UvcCaptureEngine`, and `AudioCaptureEngine`. Acquire `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, ...)`.
  - *Why:* Prevents Android OS from pausing capture coroutines or suspending network sockets when the screen shuts off.
  - *Alternative Considered:* Activity-only wake lock with black screen overlay. Rejected because the screen remains technically on, draining battery and generating heat.
* **Decision 2: Transport Protocol — Custom Sequenced UDP vs. TCP**
  - *Chosen Approach:* Custom UDP datagrams with 12-byte headers (`Magic`, `StreamType`, `SequenceNumber`, `FrameIndex`, `FragmentOffset`, `TotalFragments`).
  - *Why:* Audio packets never wait for dropped video packets. If a video fragment is lost on Wi-Fi, the receiver immediately drops the incomplete frame and decodes the next fresh frame instead of stalling audio playback.
* **Decision 3: Video Compression — Hardware MediaCodec H.264 vs. MJPEG**
  - *Chosen Approach:* Android hardware H.264 encoder with baseline profile, CBR/VBR rate control (3–5 Mbps), and low-latency configuration.
  - *Why:* Slashes bandwidth from ~30 Mbps to 4 Mbps (85% reduction), drastically lowering Wi-Fi airtime and cooling the device.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                        TRANSMITTER (TX DEVICE)                         │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │               RelayServerService (Foreground Service)          │   │
│   │   [PARTIAL_WAKE_LOCK] + [WIFI_MODE_FULL_HIGH_PERF WifiLock]   │   │
│   └───────────────┬────────────────────────────────┬───────────────┘   │
│                   │                                │                   │
│   ┌───────────────▼──────────────┐ ┌───────────────▼───────────────┐   │
│   │    UvcCaptureEngine (USB)    │ │    AudioCaptureEngine (USB)   │   │
│   │   - 720p / 1080p YUV/MJPEG   │ │   - 48 kHz 16-bit Stereo PCM  │   │
│   └───────────────┬──────────────┘ └───────────────┬───────────────┘   │
│                   │                                │                   │
│   ┌───────────────▼──────────────┐                 │                   │
│   │ Hardware H.264 (MediaCodec)  │                 │                   │
│   │  - 3–5 Mbps, Low Latency NAL │                 │                   │
│   └───────────────┬──────────────┘                 │                   │
│                   │                                │                   │
│   ┌───────────────▼────────────────────────────────▼───────────────┐   │
│   │                   Sequenced UDP Packetizer                     │   │
│   │       - 1400-byte MTU video fragments (Sequence + Index)       │   │
│   │       - 20 ms unfragmented audio datagrams (Urgent Audio)      │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
└───────────────────────────────────┼────────────────────────────────────┘
                                    │ Wi-Fi UDP Datagrams
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                         RECEIVER (RX DEVICE)                           │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                   Sequenced UDP Receiver                       │   │
│   │        - Port P: Video Datagrams (Reassembly & Drop)           │   │
│   │        - Port P+1: Audio Datagrams (Zero-Wait Jitter Queue)    │   │
│   └───────────────┬────────────────────────────────┬───────────────┘   │
│                   │                                │                   │
│   ┌───────────────▼──────────────┐ ┌───────────────▼───────────────┐   │
│   │    H.264 MediaCodec Decoder  │ │      AudioTrack Engine        │   │
│   │    - Direct Surface Render   │ │   - 48 kHz Stereo Low Latency │   │
│   └──────────────────────────────┘ └───────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

### Milestone Breakdown for Execution:
1. **Milestone 1 (Immediate Fix): Screen-Lock Background Survival**
   - Implement `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.WIFI_MODE_FULL_HIGH_PERF` in `RelayServerService`.
   - Decouple audio and video capture pipelines from `Activity` lifecycle so they remain fully active when `onStop` triggers.
   - Verify continuous audio and video streaming while device screen is locked.
2. **Milestone 2: Sequenced UDP Transport Protocol**
   - Create `UdpRelayServer` and `UdpRelayClient`.
   - Implement MTU fragmentation (1400-byte chunks), frame sequence numbers, and packet assembly.
   - Integrate audio UDP streaming with zero-delay jitter buffer.
3. **Milestone 3: Hardware H.264/HEVC Video Encoding**
   - Implement `HardwareVideoEncoder` using Android `MediaCodec` (H.264 baseline/main, low latency).
   - Implement `HardwareVideoDecoder` on RX rendering straight to `Surface`.
   - Provide toggle in TX settings for H.264 vs MJPEG.
