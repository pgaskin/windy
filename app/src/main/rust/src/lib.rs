#![cfg(target_os = "android")]

use std::ptr::NonNull;
use std::time::Instant;

use jni::EnvUnowned;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jfloat, jint, jlong, jstring};

use raw_window_handle::{
    AndroidDisplayHandle, AndroidNdkWindowHandle, RawDisplayHandle, RawWindowHandle,
};
use windy_wallpaper_core::{Config, Renderer, Theme};

struct State {
    surface: wgpu::Surface<'static>,
    _window: ndk::native_window::NativeWindow, // MUST be below surface so it outlives it (drop is top-to-bottom)
    surface_config: wgpu::SurfaceConfiguration,
    device: wgpu::Device,
    queue: wgpu::Queue,
    renderer: Renderer,
    dpi_scale: f32,
    gpu_model: String,
    last_frame: Instant,
    _instance: wgpu::Instance, // MUST be last so it outlives everything else
}

impl State {
    fn new(
        window: ndk::native_window::NativeWindow,
        theme_index: usize,
        dpi_scale: f32,
    ) -> Result<State, String> {
        let width = window.width().max(1) as u32;
        let height = window.height().max(1) as u32;

        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            // vulkan is available on API 24+
            backends: wgpu::Backends::VULKAN,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });

        let raw_window_handle = {
            let ptr = NonNull::new(window.ptr().as_ptr() as *mut _)
                .ok_or_else(|| "ANativeWindow pointer is null".to_string())?;
            RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(ptr))
        };
        let raw_display_handle = RawDisplayHandle::Android(AndroidDisplayHandle::new());
        let surface = unsafe {
            instance
                .create_surface_unsafe(wgpu::SurfaceTargetUnsafe::RawHandle {
                    raw_display_handle: Some(raw_display_handle),
                    raw_window_handle,
                })
                .map_err(|e| format!("create surface from ANativeWindow: {}", e))?
        };

        // prefer vulkan since wgpu allocates MUCH more memory and is less
        // efficient on gles
        let adapter = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN))
            .into_iter()
            .find(|a| a.is_surface_supported(&surface))
            .ok_or_else(|| "no suitable gpu adapter".to_string())?;
        let adapter_info = adapter.get_info();
        log::info!("using gpu adapter: {:?}", adapter_info);

        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("windy.device"),
            required_features: wgpu::Features::empty(),
            // keep downlevel_defaults for wider compatibility, but increase the
            // texture limits since max_texture_dimension_2d is too low for most
            // displays
            required_limits: wgpu::Limits::downlevel_defaults().using_resolution(adapter.limits()),
            // use smaller allocations to save memory
            memory_hints: wgpu::MemoryHints::MemoryUsage,
            ..Default::default()
        }))
        .map_err(|e| format!("failed to create device: {}", e))?;

        let caps = surface.get_capabilities(&adapter);
        // prefer non-srgb to avoid linearizing colors and washing them out
        let format = caps
            .formats
            .iter()
            .copied()
            .find(|f| !f.is_srgb())
            .unwrap_or(caps.formats[0]);
        let surface_config = wgpu::SurfaceConfiguration {
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            format,
            width,
            height,
            present_mode: wgpu::PresentMode::AutoVsync,
            alpha_mode: caps.alpha_modes[0],
            view_formats: vec![],
            desired_maximum_frame_latency: 2,
        };
        surface.configure(&device, &surface_config);

        let theme = Theme::ALL.get(theme_index).copied().unwrap_or(Theme::BLUE);
        let mut config = Config::with_theme(&theme);

        config.line_half_width = scale_line_half_width(config.line_half_width, dpi_scale);

        let renderer = Renderer::new(&device, &queue, format, config, width, height);
        Ok(State {
            surface,
            _window: window,
            surface_config,
            device,
            queue,
            renderer,
            dpi_scale,
            gpu_model: adapter_info.name,
            last_frame: Instant::now(),
            _instance: instance,
        })
    }

    fn resize(&mut self, width: u32, height: u32) {
        let width = width.max(1);
        let height = height.max(1);
        if width == self.surface_config.width && height == self.surface_config.height {
            return;
        }
        self.surface_config.width = width;
        self.surface_config.height = height;
        self.surface.configure(&self.device, &self.surface_config);
        self.renderer.resize(&self.device, width, height);
    }

    fn render(&mut self) {
        let now = Instant::now();
        let dt = (now - self.last_frame).as_secs_f32();
        self.last_frame = now;

        use wgpu::CurrentSurfaceTexture;
        let frame = match self.surface.get_current_texture() {
            CurrentSurfaceTexture::Success(f) | CurrentSurfaceTexture::Suboptimal(f) => f,
            CurrentSurfaceTexture::Outdated | CurrentSurfaceTexture::Lost => {
                self.surface.configure(&self.device, &self.surface_config);
                return;
            }
            other => {
                log::warn!("surface unavailable: {other:?}");
                return;
            }
        };
        let view = frame
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());
        self.renderer.render(&self.device, &self.queue, &view, dt);
        frame.present();
    }
}

/// SAFETY: handle must be from `nativeCreate` before `nativeDestroy` on a
/// single thread
unsafe fn state<'a>(handle: jlong) -> &'a mut State {
    unsafe { &mut *(handle as *mut State) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeCreate(
    mut env: EnvUnowned,
    _class: JClass,
    surface: JObject,
    theme_index: jint,
    dpi_scale: jfloat,
) -> jlong {
    // warn to avoid flooding logcat with wgpu-core per-frame logs
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Warn)
            .with_tag("WindyNative"),
    );

    let env_raw = env.as_raw();
    env.with_env(|inner_env| -> Result<jlong, jni::errors::Error> {
        let window = unsafe {
            ndk::native_window::NativeWindow::from_surface(env_raw.cast(), surface.as_raw())
        };
        let Some(window) = window else {
            inner_env.throw_new(
                jni::strings::JNIString::from("java/lang/RuntimeException"),
                jni::strings::JNIString::from("failed to get ANativeWindow from Surface"),
            )?;
            return Ok(0);
        };
        match State::new(window, theme_index.max(0) as usize, dpi_scale as f32) {
            Ok(state) => Ok(Box::into_raw(Box::new(state)) as jlong),
            Err(e) => {
                inner_env.throw_new(
                    jni::strings::JNIString::from("java/lang/RuntimeException"),
                    jni::strings::JNIString::from(e),
                )?;
                Ok(0)
            }
        }
    })
    .resolve::<LogErrorAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeResize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { state(handle) }.resize(width.max(0) as u32, height.max(0) as u32);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeRender(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe { state(handle) }.render();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeSkip(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    frames: jint,
) {
    if handle == 0 {
        return;
    }
    let st = unsafe { state(handle) };
    st.renderer
        .skip(&st.device, &st.queue, frames.max(0) as u32);
    st.last_frame = Instant::now(); // don't count the skipped time as frame time
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeRestart(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let st = unsafe { state(handle) };
    st.renderer.restart(&st.device);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeSetOffset(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    offset: jfloat,
) {
    if handle == 0 {
        return;
    }
    unsafe { state(handle) }
        .renderer
        .set_offset_x(offset as f32);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeSetColors(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    slow: jint,
    fast: jint,
    bg1: jint,
    bg2: jint,
) {
    if handle == 0 {
        return;
    }
    let st = unsafe { state(handle) };
    let mut config = st.renderer.config().clone();
    config.slow_wind_color = unpack_argb(slow);
    config.fast_wind_color = unpack_argb(fast);
    config.bg_color1 = unpack_argb(bg1);
    config.bg_color2 = unpack_argb(bg2);
    st.renderer.set_config(&st.device, config);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeSetParams(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    line_half_width: jfloat,
    particle_opacity: jfloat,
    alpha_decay: jfloat,
    wind_speed: jfloat,
) {
    if handle == 0 {
        return;
    }
    let st = unsafe { state(handle) };
    let mut config = st.renderer.config().clone();
    config.line_half_width = scale_line_half_width(line_half_width as f32, st.dpi_scale);
    config.particle_opacity = particle_opacity as f32;
    config.alpha_decay = alpha_decay as f32;
    config.wind_speed = wind_speed as f32;
    st.renderer.set_config(&st.device, config); // note: this eases
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeSetUserLocation(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    lng: jfloat,
    lat: jfloat,
) {
    if handle == 0 {
        return;
    }
    unsafe { state(handle) }
        .renderer
        .set_user_location(lng as f32, lat as f32);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeSetWindField(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    rgba: JByteArray,
    width: jint,
    height: jint,
) {
    if handle == 0 {
        return;
    }
    let st = unsafe { state(handle) };
    env.with_env(|env| {
        // with_env catches panics
        let bytes = env.convert_byte_array(&rgba)?;
        st.renderer.set_wind_field(
            &st.device,
            &st.queue,
            width.max(0) as u32,
            height.max(0) as u32,
            &bytes,
        );
        Ok::<(), jni::errors::Error>(()) // leave unchanged on error
    })
    .resolve::<LogErrorAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeGpuModel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let st = unsafe { state(handle) };
    env.with_env(|env| -> Result<JString<'local>, jni::errors::Error> {
        env.new_string(&st.gpu_model) // returns null on error
    })
    .resolve::<LogErrorAndDefault>()
    .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeDestroy(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    drop(unsafe { Box::from_raw(handle as *mut State) });
}

// must match java
const COLOR_SLOW: jint = 0;
const COLOR_FAST: jint = 1;
const COLOR_BG1: jint = 2;
const COLOR_BG2: jint = 3;

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeThemeColor(
    _env: EnvUnowned,
    _class: JClass,
    theme_index: jint,
    component: jint,
) -> jint {
    let theme = Theme::ALL
        .get(theme_index.max(0) as usize)
        .copied()
        .unwrap_or(Theme::BLUE);
    let rgba = match component {
        COLOR_SLOW => theme.slow_wind_color,
        COLOR_FAST => theme.fast_wind_color,
        // alpha doesn't matter
        COLOR_BG1 => opaque(theme.bg_color1),
        COLOR_BG2 => opaque(theme.bg_color2),
        _ => {
            let [r, g, b] = theme.wallpaper_color;
            [r, g, b, 1.0]
        }
    };
    pack_argb(rgba)
}

// must match java
const PARAM_LINE_HALF_WIDTH: jint = 0;
const PARAM_PARTICLE_OPACITY: jint = 1;
const PARAM_ALPHA_DECAY: jint = 2;

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_WindyWallpaperNative_nativeThemeParam(
    _env: EnvUnowned,
    _class: JClass,
    theme_index: jint,
    param: jint,
) -> jfloat {
    let theme = Theme::ALL
        .get(theme_index.max(0) as usize)
        .copied()
        .unwrap_or(Theme::BLUE);
    let config = Config::with_theme(&theme);
    let value = match param {
        PARAM_LINE_HALF_WIDTH => config.line_half_width, // dp, scaled when applied
        PARAM_PARTICLE_OPACITY => config.particle_opacity,
        PARAM_ALPHA_DECAY => config.alpha_decay,
        _ => config.wind_speed,
    }; // 3 is the wind speed
    value as jfloat
}

// keep it density-independent for custom themes too
fn scale_line_half_width(value: f32, dpi_scale: f32) -> f32 {
    (value * dpi_scale).max(1.0)
}

/// `[r, g, b, a]` from `[0,1]` to packed `0xAARRGGBB`.
fn pack_argb(rgba: [f32; 4]) -> jint {
    let to8 = |c: f32| ((c.clamp(0.0, 1.0) * 255.0).round() as i32) & 0xff;
    (to8(rgba[3]) << 24) | (to8(rgba[0]) << 16) | (to8(rgba[1]) << 8) | to8(rgba[2])
}

/// Packed `0xAARRGGBB` to `[r, g, b, a]` from `[0,1]`.
fn unpack_argb(argb: jint) -> [f32; 4] {
    let at = |shift: u32| ((argb >> shift) & 0xff) as f32 / 255.0;
    [at(16), at(8), at(0), at(24)]
}

fn opaque(rgba: [f32; 4]) -> [f32; 4] {
    [rgba[0], rgba[1], rgba[2], 1.0]
}
