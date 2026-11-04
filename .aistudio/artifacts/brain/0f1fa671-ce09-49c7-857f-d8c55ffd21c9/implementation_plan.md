# StreamRelay2: RX Image Quality Upgrade & MJPEG Passthrough Diagnostics

## Summary of Completed Improvements

All five requirements for RX image quality enhancement and MJPEG-path diagnostic instrumentation have been implemented and verified:

### 1. High-Fidelity ARGB_8888 Color Pipeline in `MjpegPlayer.kt`
- Eliminated 16-bit color quantization and color banding by completely removing `Bitmap.Config.RGB_565`.
- `Bitmap.Config.ARGB_8888` is now configured universally across `bitmapOptions.inPreferredConfig`, `Bitmap.createBitmap(w, h, ...)`, `fallbackOpts`, and Compose fallback copy routines (`decoded.copy(decoded.config ?: Bitmap.Config.ARGB_8888, false)`).
- Preserved `inBitmap` buffer reuse (`inMutable = true`) to prevent garbage collection pauses during high-frame-rate rendering.
- Render paint now operates with `Paint(Paint.FILTER_BITMAP_FLAG)` without unnecessary dither overhead on 32-bit color.

### 2. Real Negotiated Hardware Format Reporting in `UvcCaptureEngine.kt`
- `telemetry.bufferFormat` now reflects the real negotiated hardware format from `cameraHelper.previewSize.type` rather than hardcoding "MJPEG":
  - Type 6, 7, 1: `"MJPEG"`
  - Type 0, 4, 5: `"YUY2/Uncompressed"`
- One-time post-negotiation hardware log output: `Hardware stream negotiated: ... type=<type> (<formatName>)`.
- Added high-visibility warning banner on TX screen whenever the negotiated format is not MJPEG:
  *"Card is not sending MJPEG - frames are being re-encoded (quality loss)"*.

### 3. High-Quality Fallback & Real-Time Passthrough Verification Counters in `CaptureViewModel.kt`
- Raised the non-JPEG fallback compression quality from 80 to 95 in `compressYuvToJpeg()`.
- Added `passthroughFrames` and `reEncodedFrames` tracking to `TxRelayStats` on `RelayServer`.
- Verified in the TX diagnostics panel with live counts and percentage (`MJPEG Passthrough: X (100%) | Re-encoded: Y`) so the user can verify that hardware MJPEG passthrough is operating without re-encoding.

### 4. Prominent Active Codec & Compression Quality Notice on TX and RX Screens
- Both TX diagnostics panel and RX overlay diagnostics prominently display the active codec:
  `ACTIVE CODEC: MJPEG PASSTHROUGH` or `ACTIVE CODEC: H.264 RE-ENCODED`.
- When H.264 is active, a clear explanatory note is displayed on both devices:
  *"Re-encoded from decoded frames at 4 Mbps - lower quality than MJPEG"*.

### 5. Clean JPEG Slicing via `JpegUtils.extractCleanJpeg`
- Frame bytes are checked for the standard JPEG End-Of-Image marker (`FF D9`).
- If already ending with `FF D9`, raw bytes are submitted directly without allocation or copying.
- Only when trailing padding bytes exist is `JpegUtils.extractCleanJpeg` called to trim the payload before submission, ensuring zero padding bytes are transmitted over TCP.
