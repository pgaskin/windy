// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

mod color_picker;
mod theme_editor;

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

use clap::Parser;
use winit::application::ApplicationHandler;
use winit::event::{ElementState, WindowEvent};
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::keyboard::{KeyCode, PhysicalKey};
use winit::window::{Window, WindowId};

use theme_editor::ThemeEditor;
use windy_wallpaper_core::{Config, Renderer, Theme};

pub(crate) const WIND_PNG: &[u8] = include_bytes!("wind_cache.png");

/// Preview and theme editor for the Windy live wallpaper.
#[derive(clap::Parser)]
#[command(version, about, long_about = None)]
struct Args {
    /// Initial theme
    #[arg(long, env = "WINDY_THEME", value_name = "theme")]
    theme: Option<String>,

    /// List themes and exit
    #[arg(long)]
    list: bool,

    /// Render previews to the given directory and exit
    #[arg(long, value_name = "dir")]
    screenshots: Option<PathBuf>,
}

fn main() {
    env_logger::init();

    let args = Args::parse();
    if args.list {
        println!("Themes:");
        for t in Theme::ALL {
            println!("  {}", t.name);
        }
        return;
    }
    if let Some(dir) = args.screenshots {
        screenshots(dir);
        return;
    }
    let theme = resolve_theme(args.theme);

    let event_loop = EventLoop::new().unwrap();
    event_loop.set_control_flow(ControlFlow::Poll);
    let mut app = App::new(theme);
    event_loop.run_app(&mut app).unwrap();
}

fn screenshots(out_dir: PathBuf) {
    std::fs::create_dir_all(&out_dir).expect("create output dir");

    let (w, h) = (960u32, 960u32);
    let frames: usize = 600;
    let (lng, lat) = (-97.0, 38.0);

    let instance = wgpu::Instance::default();
    let adapter =
        pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))
            .expect("no GPU adapter");
    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: None,
        required_features: wgpu::Features::empty(),
        required_limits: wgpu::Limits::default(),
        memory_hints: wgpu::MemoryHints::default(),
        ..Default::default()
    }))
    .unwrap();

    let wind = image::load_from_memory(WIND_PNG).unwrap().to_rgba8();

    for theme in Theme::ALL {
        let mut config = Config::with_theme(theme);

        // zoom in a bit
        config.window_size = 25.0;
        config.line_half_width = 1.5;

        // setup
        let format = wgpu::TextureFormat::Rgba8Unorm;
        let mut renderer = Renderer::new(&device, &queue, format, config, w, h);
        renderer.set_wind_field(&device, &queue, wind.width(), wind.height(), &wind);
        renderer.set_user_location(lng, lat);

        // render
        let target = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("screenshots.target"),
            size: wgpu::Extent3d {
                width: w,
                height: h,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let view = target.create_view(&wgpu::TextureViewDescriptor::default());

        if let Ok(v) = std::env::var("WINDY_WARMUP") {
            renderer.skip(&device, &queue, v.parse().unwrap());
        }

        for _ in 0..frames {
            renderer.render(&device, &queue, &view, 1.0 / 60.0);
        }

        // copy to buffer
        let bytes_per_pixel = 4u32;
        let unpadded = w * bytes_per_pixel;
        let align = wgpu::COPY_BYTES_PER_ROW_ALIGNMENT;
        let padded = unpadded.div_ceil(align) * align;
        let buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("screenshots.buffer"),
            size: (padded * h) as u64,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            wgpu::TexelCopyTextureInfo {
                texture: &target,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::TexelCopyBufferInfo {
                buffer: &buffer,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(padded),
                    rows_per_image: Some(h),
                },
            },
            wgpu::Extent3d {
                width: w,
                height: h,
                depth_or_array_layers: 1,
            },
        );
        queue.submit(Some(encoder.finish()));

        // read buffer
        let slice = buffer.slice(..);
        slice.map_async(wgpu::MapMode::Read, |r| r.unwrap());
        device.poll(wgpu::PollType::wait_indefinitely()).unwrap();

        // drop alpha
        let data = slice.get_mapped_range();
        let mut pixels = Vec::with_capacity((w * h * 3) as usize);
        for row in 0..h {
            let start = (row * padded) as usize;
            let row_rgba = &data[start..start + unpadded as usize];
            for px in row_rgba.chunks_exact(4) {
                pixels.extend_from_slice(&px[..3]);
            }
        }

        // render jpg
        let out_path = out_dir.join(format!("windy_{}.jpg", theme.name.to_lowercase()));
        image::save_buffer(&out_path, &pixels, w, h, image::ColorType::Rgb8).unwrap();
        println!("{}", out_path.display());
    }
}

fn resolve_theme(theme: Option<String>) -> &'static Theme {
    match theme {
        None => &Theme::BLUE,
        Some(name) => match Theme::ALL
            .iter()
            .find(|t| t.name.eq_ignore_ascii_case(&name))
        {
            Some(theme) => theme,
            None => {
                eprintln!("unknown theme: {name:?}");
                std::process::exit(2);
            }
        },
    }
}

struct App {
    theme: &'static Theme,
    state: Option<State>,
}

impl App {
    fn new(theme: &'static Theme) -> Self {
        Self { theme, state: None }
    }
}

struct State {
    window: Arc<Window>,
    device: wgpu::Device,
    queue: wgpu::Queue,
    surface: wgpu::Surface<'static>,
    surface_config: wgpu::SurfaceConfiguration,
    renderer: Renderer,
    last_frame: Instant,

    egui_ctx: egui::Context,
    egui_state: egui_winit::State,
    egui_renderer: egui_wgpu::Renderer,
    editor: ThemeEditor,
}

impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.state.is_some() {
            return;
        }
        let window = Arc::new(
            event_loop
                .create_window(Window::default_attributes().with_title("Windy"))
                .unwrap(),
        );
        self.state = Some(pollster::block_on(State::new(window, self.theme)));
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _id: WindowId, event: WindowEvent) {
        let Some(state) = self.state.as_mut() else {
            return;
        };
        let response = state.egui_state.on_window_event(&state.window, &event);
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::Resized(size) => state.resize(size.width, size.height),
            WindowEvent::KeyboardInput { event: ref key, .. } => {
                if !response.consumed // if not typing
                    && key.state == ElementState::Pressed
                    && !key.repeat
                    && key.physical_key == PhysicalKey::Code(KeyCode::Space)
                {
                    state.editor.open = !state.editor.open;
                    state.window.request_redraw();
                }
            }
            WindowEvent::RedrawRequested => {
                state.render();
                state.window.request_redraw();
            }
            _ => {}
        }
    }

    fn exiting(&mut self, _event_loop: &ActiveEventLoop) {
        // drop the surface and egui before we lose the display
        self.state = None;
    }
}

impl State {
    async fn new(window: Arc<Window>, theme: &'static Theme) -> Self {
        let size = window.inner_size();
        let (width, height) = (size.width.max(1), size.height.max(1));

        let instance = wgpu::Instance::default();
        let surface = instance.create_surface(window.clone()).unwrap();
        let adapter = instance
            .request_adapter(&wgpu::RequestAdapterOptions {
                power_preference: wgpu::PowerPreference::HighPerformance,
                compatible_surface: Some(&surface),
                force_fallback_adapter: false,
            })
            .await
            .expect("no suitable gpu adapter");

        let (device, queue) = adapter
            .request_device(&wgpu::DeviceDescriptor {
                label: Some("windy.device"),
                required_features: wgpu::Features::empty(),
                required_limits: wgpu::Limits::default(),
                memory_hints: wgpu::MemoryHints::default(),
                ..Default::default()
            })
            .await
            .expect("failed to create device");

        let caps = surface.get_capabilities(&adapter);

        // prefer non-srgb to avoid getting washed out colors
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

        let editor = ThemeEditor::from_theme(theme);
        let mut renderer =
            Renderer::new(&device, &queue, format, editor.to_config(), width, height);

        let img = image::load_from_memory(WIND_PNG)
            .expect("decode wind field")
            .to_rgba8();
        renderer.set_wind_field(&device, &queue, img.width(), img.height(), &img);
        renderer.set_user_location(-97.0, 38.0);

        let egui_ctx = egui::Context::default();
        let egui_state = egui_winit::State::new(
            egui_ctx.clone(),
            egui::ViewportId::ROOT,
            &window,
            Some(window.scale_factor() as f32),
            None,
            None,
        );
        let egui_renderer = egui_wgpu::Renderer::new(
            &device,
            format,
            egui_wgpu::RendererOptions {
                msaa_samples: 1,
                depth_stencil_format: None,
                dithering: false,
                ..Default::default()
            },
        );
        let mut visuals = egui::Visuals::dark();
        visuals.popup_shadow = egui::Shadow::NONE;
        visuals.window_shadow = egui::Shadow::NONE;
        egui_ctx.set_visuals(visuals);

        Self {
            window,
            device,
            queue,
            surface,
            surface_config,
            renderer,
            last_frame: Instant::now(),
            egui_ctx,
            egui_state,
            egui_renderer,
            editor,
        }
    }

    fn resize(&mut self, width: u32, height: u32) {
        if width == 0 || height == 0 {
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

        self.renderer
            .set_config(&self.device, self.editor.to_config());

        use wgpu::CurrentSurfaceTexture;
        let frame = match self.surface.get_current_texture() {
            CurrentSurfaceTexture::Success(f) | CurrentSurfaceTexture::Suboptimal(f) => f,
            CurrentSurfaceTexture::Outdated | CurrentSurfaceTexture::Lost => {
                self.surface.configure(&self.device, &self.surface_config);
                return;
            }
            _ => return,
        };
        let view = frame
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());

        self.renderer.render(&self.device, &self.queue, &view, dt);

        let raw_input = self.egui_state.take_egui_input(&self.window);
        let ctx = self.egui_ctx.clone();
        let editor = &mut self.editor;
        let full_output = ctx.run_ui(raw_input, |ui| {
            editor.ui(ui.ctx());
        });
        self.egui_state
            .handle_platform_output(&self.window, full_output.platform_output);

        let actions = self.editor.take_actions();
        if actions.restart {
            self.renderer.restart(&self.device);
        }
        if actions.time_skip {
            self.renderer.skip(&self.device, &self.queue, 300);
        }

        let paint_jobs = self
            .egui_ctx
            .tessellate(full_output.shapes, full_output.pixels_per_point);
        let screen = egui_wgpu::ScreenDescriptor {
            size_in_pixels: [self.surface_config.width, self.surface_config.height],
            pixels_per_point: full_output.pixels_per_point,
        };

        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("windy.egui"),
            });
        for (id, delta) in &full_output.textures_delta.set {
            self.egui_renderer
                .update_texture(&self.device, &self.queue, *id, delta);
        }
        let user_cmds = self.egui_renderer.update_buffers(
            &self.device,
            &self.queue,
            &mut encoder,
            &paint_jobs,
            &screen,
        );
        {
            let rpass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("windy.egui_pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Load,
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            let mut rpass = rpass.forget_lifetime();
            self.egui_renderer.render(&mut rpass, &paint_jobs, &screen);
        }
        self.queue.submit(
            user_cmds
                .into_iter()
                .chain(std::iter::once(encoder.finish())),
        );
        for id in &full_output.textures_delta.free {
            self.egui_renderer.free_texture(id);
        }

        frame.present();
    }
}
