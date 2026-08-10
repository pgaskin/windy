<h1 align="center">Windy Live Wallpaper</h1>

<a href="https://github.com/pgaskin/windy/actions/workflows/ci.yml"><img align="right" src="https://github.com/pgaskin/windy/actions/workflows/ci.yml/badge.svg" alt="ci"></a>

**Android live wallpaper visualizing local wind patterns.**

The shaders are inspired by the official Pixel windy live wallpaper, but rewritten from scratch.

- Completely rewritten Java code.
- Completely rewritten shaders.
- Modern render pipeline using WebGPU.
- Correct rendering on newer devices.
- More color schemes.
- Updated wind data (the official data was last updated in 2019).
- Better location handling.
- Lower memory and CPU usage.
- Optionally render static frames to effectively eliminate all resource usage.
- Support for custom themes.
- Other fixes.

[**`Download`**](https://github.com/pgaskin/windy/releases/latest)

#### Screenshots

<table><tbody><tr>
<td><img src="app/src/main/res/drawable/windy_blue.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_green.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_blush.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_maroon.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_midnight.jpg"></td>
</tr><tr>
<td><img src="app/src/main/res/drawable/windy_sepia.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_skybluewhirled.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_sunsetwhirled.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_turquoisewhirled.jpg"></td>
<td><img src="app/src/main/res/drawable/windy_sparkwhirled.jpg"></td>
</tr></tbody></table>

<table><tbody><tr>
<td><img src="metadata/en-US/images/phoneScreenshots/1.png"></td>
<td><img src="metadata/en-US/images/phoneScreenshots/2.png"></td>
<td><img src="metadata/en-US/images/phoneScreenshots/3.png"></td>
<td><img src="metadata/en-US/images/phoneScreenshots/4.png"></td>
<td><img src="metadata/en-US/images/phoneScreenshots/5.png"></td>
</tr></tbody></table>

#### Build

JDK 21 is required. Use `JAVA_HOME` to point to it if it isn't the default.

The `sdk.dir` option in `local.properties` must point to your Android SDK installation, which must have `ndk;28.2.13676358` and `build-tools;36.0.0`.

You will also need a working rustup/cargo installation with `cargo-ndk@4.1.2` installed. Use `rustup toolchain install` to install the correct toolchain from `rust-toolchain.toml`.

The build scripts have not been tested on Windows.

To build the app, run `./gradlew app:assembleDebug`.

#### Local preview

There is a built-in tool to run the wallpaper locally, update the preview images, and create themes.

```bash
# run wallpaper locally
cargo run --package windy-wallpaper-preview

# update preview images
cargo run --package windy-wallpaper-preview -- --screenshots app/src/main/res/drawable
```

Themes can also be created on the device using the app. Tap the version 10 times to enable the dev options including the theme code export.

Themes are defined in [`core/src/config.rs`](./core/src/config.rs) (the rest of the code is generated from that).

<img src="https://github.com/user-attachments/assets/d3c4494f-24ee-4ddd-88e3-faffbfa04abd" alt="theme editor screenshot" height="200">

#### Wind field images

The wind field images used by the live wallpaper are generated from [NOAA GFS](https://www.ncei.noaa.gov/products/weather-climate-models/global-forecast) [0.25° ANL](https://www.nco.ncep.noaa.gov/pmb/products/gfs/) data ([updated](https://www.nco.ncep.noaa.gov/pmb/nwprod/prodstat/) every 6 hours) using the wind vector values ([UGRD, VGRD](https://origin.cpc.ncep.noaa.gov/products/wesley/wgrib2/wind_uv.html)) at 850 mb elevation (this is arbitrary).

The wind vector (in m/s) is extracted from the [GRIB2](https://www.nco.ncep.noaa.gov/pmb/docs/grib2/grib2_doc/) forecast data and mapped into a RGB 8bpp image (equirectangular projection, y: latitude 90° to -90°, longitude -180° to 180°) with one pixel per grid cell (1440x721). The red/green values are the u/v components (east/north) of the unit vector mapped from -1-1 to 0-255, and the blue value is the magnitude of the unit vector clamped and mapped from 0-30 (this value is arbitrary) to 0-255. The image is encoded as a JPEG.

The elevation and wind vector magnitude range I chose seems to produce similar images as the old official one from 2019 (available at [`www.gstatic.com/pixel/livewallpaper/windy/gfs_wind_1000.jpg`](https://www.gstatic.com/pixel/livewallpaper/windy/gfs_wind_1000.jpg)), and the red/green/blue level curves are similar.

To create the texture passed to the particle system and background shaders, the image is scaled down to 1/4 of the size (i.e., 360x180) using bilinear filtering, then blurred using a gaussian kernel of radius 2. This matches what was done by the original live wallpaper. This filtering is done to smooth out the streamlines and remove local outlier values, resulting in less detailed and rounder wallpaper wind trails. Since the wallpaper still looks good, and is interesting in its own way before this filtering, I'm probably going to add variants with an unfiltered wind field later.

See [`windy.api.pgaskin.net/wind_field.jpg`](https://windy.api.pgaskin.net/wind_field.jpg) for the latest wind field image generated by this [code](./api/windy.go), and [`windy.api.pgaskin.net/wind_cache.png?filter=1`](https://windy.api.pgaskin.net/wind_cache.png?filter=1) for the latest filtered texture.

The generated images include `windy:version`, `windy:generated`, `windy:grib-source`, and `windy:filter` metadata fields (PNG tEXT, JPEG EXIF) for debugging.

#### Rendering

The wallpaper is rendered by a Rust library using wgpu with shaders written in WGSL. On Android, Vulkan is used as the backend since OpenGL has performance issues and doesn't support everything we need, and we only target recent versions of Android which require Vulkan support anyways.

The original wallpaper used OpenGL via libGDX with [shaders](https://github.com/pgaskin/windy/tree/1d9553b7d0b30128415a6cedd638541e79b62060/app/src/main/assets/windy) written in GLSL. The `particle_system` shader does the particle simulation by alternating between two RGBA32F FBOs, storing the position in R/G, and the life in B, respawning new particles pseudo-randomly after they die. The `particle` shader stamps a point at the current position of each particle onto the current trail framebuffer. The `trail` shader alternates between two FBOs, copying between them to create the trails, and fading them out. The `background` shader combines everything, draws the background gradient, and colors the particles.

Earlier versions of this app used the shaders extracted from the APK as-is, but due to a number of issues, I ended up figuring out how it works and rewriting them from scratch into a modern render [pipeline](./core/src/render.rs) with three passes. This took a while (I attempted it multiple times over a few years) since it was the first time I've done any non-trivial graphics stuff.

The [`simulate.wgsl`](./core/src/shaders/simulate.wgsl) compute shader does the particle simulation, and respawns each particle when it dies. Unlike the original one, it uses a proper storage buffer for the state, avoiding the space limitations of the original shader, and also being much more efficient.

The [`trail.wgsl`](./core/src/shaders/trail.wgsl) shader draws line segments from the old particle positions to the new ones. By drawing line segments instead of stamping, it avoids dashed lines even when particles move quickly (and independently of the framerate). Compared to the original one, it also fixes the brightness being dependent on the framerate by scaling based on the particle movement instead of just relying on the stamping.

The [`fade.wgsl`](./core/src/shaders/fade.wgsl) shader handles the trail decay fading as part of the trail pass. Unlike the original one, it blends it as part of the pass instead of copying the buffer into another one with one component multiplied. Doing this also fixes a bug where if a fast particle passed a pixel, later particles would keep being colored with the fast color until enough slow ones passed over it, since that component wasn't faded along with the rest of the particle.

The [`composite.wgsl`](./core/src/shaders/composite.wgsl) shader combines everything like the original background shader.

The [`common.wgsl`](./core/src/shaders/common.wgsl) file contains common structs and functions across all shaders. Most of this abstraction is only possible due to WGSL/wgpu features which aren't available in GLES, but it makes everything MUCH easier to understand. By using `wgsl_to_wgpu`, the memory layout of the rust bindings are also automatically kept in sync, and there's a lot less boilerplate around the uniforms.

Apart from being cleaner and much more efficient, the new render pipeline also fixes a number of other issues with the original one, many of which were inherent to the original design.

The original shaders had a precision mismatch on the `v_uv` varying, which was declared as `lowp` in `background.vert` but `highp` in `background.frag`, causing quite a few non-Pixel devices with stricter drivers to fail to compile them.

The next most obvious issue was jagged lines on certain GPUs (especially newer ones, including the Pixel 8's Immortalis-G715). This had two main causes. First, `particle.vert` declared `precision lowp float`, which could be anywhere from 8 bits to 16 bits depending on the GPU, causing the particles to snap to a visible grid and producing odd-looking artifacts in the simulation. Even without this, I wasn't able to get it to work consistently on all GPUs, especially as I ran into hardware limitations on some. In the new shader, everything is a 32-bit float, avoiding the issue entirely. Second, libGDX used NEAREST for the texture attachment, which caused the lines to shimmer in some cases, especially at high resolutions when the parallax effect is applied. In the new one, the trail texture is sampled with linear filtering, and I also simplified the parallax to a plain horizontal shift rather than a rotation (which was barely noticeable anyways).

Another issue which came up when attempting to reduce the power usage was the amount of math in the original Java render loop dependent on the framerate (that's not even considering the fundamental issue with stamping points rather than drawing line segments). The time delta was hardcoded (affecting the wind speed, trail length, and trail brightness) and the frame timing was inconsistent in the render loop. This resulted in the animation being at least 15% slower than intended, even at full speed, along with a number of other rendering bugs. I fixed this by passing in the time delta to make everything framerate-independent.

On high-DPI screens, the line thickness was incorrect. I fixed this by scaling it accordingly, and antialiasing it so it looks consistent without having sharp edges.

I also ran into an issue with the texture formats when making a desktop preview of it for development where if the target framebuffer was sRGB, the colors would appear faded (since they're mixed in plain RGBA8888). In addition, the background colors unnecessarily had alpha channels, which resulted in incorrect rendering in some cases.

I generate the initial particle state with a seeded xorshift generator on the CPU to ensure rendering was consistent across restarts and devices, which is especially useful for generating the preview images.

The new pipeline produces almost identical output to the original one, but works much more consistently across devices and is much better designed.
