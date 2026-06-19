#![cfg(target_os = "android")]

use std::ptr::NonNull;
use std::time::Instant;

use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jfloat, jint, jlong};
use jni::EnvUnowned;

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
    last_frame: Instant,
    _instance: wgpu::Instance, // MUST be last so it outlives everything else
}

impl State {
    fn new(window: ndk::native_window::NativeWindow, theme_index: usize, dpi_scale: f32) -> State {
        let width = window.width().max(1) as u32;
        let height = window.height().max(1) as u32;

        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            // vulkan is available on API 24+, fall back to gles
            backends: wgpu::Backends::VULKAN | wgpu::Backends::GL,
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });

        let raw_window_handle = {
            let ptr = NonNull::new(window.ptr().as_ptr() as *mut _)
                .expect("ANativeWindow pointer is null");
            RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(ptr))
        };
        let raw_display_handle = RawDisplayHandle::Android(AndroidDisplayHandle::new());
        let surface = unsafe {
            instance
                .create_surface_unsafe(wgpu::SurfaceTargetUnsafe::RawHandle {
                    raw_display_handle: Some(raw_display_handle),
                    raw_window_handle,
                })
                .expect("create surface from ANativeWindow")
        };

        // prefer vulkan since wgpu allocates MUCH more memory and is less
        // efficient on gles
        let adapter = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN))
            .into_iter()
            .find(|a| a.is_surface_supported(&surface))
            .or_else(|| {
                log::warn!("no vulkan adapter for surface, falling back to gles");
                pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
                    power_preference: wgpu::PowerPreference::LowPower,
                    compatible_surface: Some(&surface),
                    force_fallback_adapter: false,
                }))
                .ok()
            })
            .expect("no suitable gpu adapter");
        log::info!("using gpu adapter: {:?}", adapter.get_info());

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
        .expect("failed to create device");

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

        config.line_half_width = (config.line_half_width * dpi_scale).max(1.0);

        let renderer = Renderer::new(&device, &queue, format, config, width, height);
        State {
            surface,
            _window: window,
            surface_config,
            device,
            queue,
            renderer,
            last_frame: Instant::now(),
            _instance: instance,
        }
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
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeCreate(
    env: EnvUnowned,
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

    let window = unsafe {
        ndk::native_window::NativeWindow::from_surface(env.as_raw().cast(), surface.as_raw())
    };
    let Some(window) = window else {
        log::error!("failed to get ANativeWindow from Surface");
        return 0;
    };

    let state = State::new(window, theme_index.max(0) as usize, dpi_scale as f32);
    Box::into_raw(Box::new(state)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeResize(
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
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeRender(
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
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeSetOffset(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    offset: jfloat,
) {
    if handle == 0 {
        return;
    }
    unsafe { state(handle) }.renderer.set_offset_x(offset as f32);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeSetUserLocation(
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
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeSetWindField(
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
    env.with_env(|env| { // with_env catches panics
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
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeDestroy(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    drop(unsafe { Box::from_raw(handle as *mut State) });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_net_pgaskin_windy_NativeRenderer_nativeThemeColor(
    _env: EnvUnowned,
    _class: JClass,
    theme_index: jint,
) -> jint {
    let theme = Theme::ALL
        .get(theme_index.max(0) as usize)
        .copied()
        .unwrap_or(Theme::BLUE);
    let [r, g, b] = theme.wallpaper_color;
    let to8 = |c: f32| ((c.clamp(0.0, 1.0) * 255.0).round() as i32) & 0xff;
    (to8(r) << 16) | (to8(g) << 8) | to8(b)
}
