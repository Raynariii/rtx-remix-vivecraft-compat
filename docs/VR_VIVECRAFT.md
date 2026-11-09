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
| `stereoPresent` | end a Remix frame after every VR eye but the last, so each eye gets its own render | `true` |
| `overlayDepthMask` | VR passes keep what Vivecraft drew (GUI panel, hands, controllers) over the Remix image | `true` |
| `logEveryFrames` | one `[RemixVR]` line per pass every N frames, 0 = off | `600` |
| `logFirstVrFrames` | log every pass of the first N VR frames | `3` |

## Hotkeys (rebindable in Controls, category "RTX Remix VR")

| Key | Command | Action |
| --- | --- | --- |
| F6 | `/remixvr mode` | cycle camera mode AUTO → ORIGINAL → PASS_MATRICES |
| F7 | `/remixvr projection` | projection source REBUILD ↔ RAW |
| F9 | `/remixvr dump` | dump one full frame (all passes, matrices, sizes, Remix settings) to the log |
| F10 | `/remixvr temporal` | denoiser + upscaler off / restore (`rtx.useDenoiser`, `rtx.upscalerType`) |
| — | `/remixvr stereo` | toggle `stereoPresent` |
| — | `/remixvr mask` | toggle `overlayDepthMask` |
| — | `/remixvr status` | show the current config |

Commands work from Vivecraft's in-headset keyboard. Every switch is logged with
the frame number.

## Why each eye needs its own Remix frame

The runtime renders one frame per `Present`. A second `Submit` before it hands
back the first image (the per-pass `vkDone` value in the log does not advance),
so both eyes showed the left eye. With `stereoPresent` every eye pass but the
last ends its Remix frame with a `Present` that skips the swapchain. Remix's
temporal history (denoiser, DLSS) now alternates between the eyes, so expect
smearing in motion until the per-eye history work.

## Test scenario

Enter the test world → stand still 5 s → F9 → turn head left/right → tilt head
sideways → close each eye in turn → open the menu, look at your hands →
F10, look, F9 → `/remixvr stereo`, look, F9, `/remixvr stereo` →
`/remixvr mask`, look, `/remixvr mask` → quit. Send `logs/latest.log` (grep
`[RemixVR]`) and a note of what each eye shows.
