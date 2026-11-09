# Vivecraft VR camera (remix-vivecraft-compat fork)

This fork of RTX Remix for Minecraft feeds Remix the camera of each Vivecraft
render pass (eye pose with head roll, asymmetric OpenVR frustum) instead of the
desktop yaw/pitch/FOV camera. Vivecraft is optional: without it, or with
`enabled=false`, the mod behaves like upstream v0.1.1. Tested against Vivecraft
1.21.1-1.3.15 (Fabric); Vivecraft is reached by reflection only.

VR code lives in `src/main/java/com/nvidia/rtxremix/minecraft/vr/`. Upstream
files carry one-line hooks marked `remix-vivecraft-compat`.

## Install

Download the `remix-vivecraft-compat-<sha>` artifact from the `vr-build`
workflow run of the branch, replace the RTX Remix JAR in the instance `mods`
folder (keep the original v0.1.1 JAR outside `mods`). The JAR bundles the
runtime; the first launch re-extracts it.

## Config

`config/remix-vivecraft-compat.properties` (written on first launch):

| Key | Values | Default |
| --- | --- | --- |
| `enabled` | `true` / `false` | `true` |
| `cameraMode` | `AUTO` (pass matrices in Vivecraft VR passes), `ORIGINAL`, `PASS_MATRICES` | `AUTO` |
| `projection` | `REBUILD` (pass frustum, near 0.05 / far 1000), `RAW` | `REBUILD` |
| `transposeMatrices` | diagnostic only | `false` |
| `logEveryFrames` | one `[RemixVR]` line per pass every N frames, 0 = off | `600` |
| `logFirstVrFrames` | log every pass of the first N VR frames | `3` |

## Hotkeys (rebindable, category "RTX Remix VR")

| Key | Action |
| --- | --- |
| Numpad 7 | cycle camera mode AUTO → ORIGINAL → PASS_MATRICES |
| Numpad 4 | projection source REBUILD ↔ RAW |
| Numpad 8 | dump one full frame (all passes, matrices, sizes, Remix settings) to the log |
| Numpad 9 | denoiser + upscaler off / restore (`rtx.useDenoiser`, `rtx.upscalerType`) |

Every switch is logged with the frame number.

## Test scenario

Enter the test world → stand still 5 s → Numpad 8 → turn head left/right →
tilt head sideways → close each eye in turn → Numpad 9, look, Numpad 8 →
Numpad 7 (ORIGINAL), Numpad 8 → quit. Send `logs/latest.log` (grep
`[RemixVR]`) and a note of what each eye shows.
