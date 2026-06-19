// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use bytemuck::Zeroable;
use wgpu::util::DeviceExt;

use crate::config::Config;
use crate::shaders;
use crate::shaders::simulate::{Globals, Particle};

/// Number of frames to ease in at startup.
const REDRAW_FRAMES: u32 = 240;

/// Startup ease speed multiplier.
const REDRAW_MAX_BOOST: f32 = 7.0;

/// Effective rate (Hz) at the original one fades the trail (this controls the
/// trail length). At 13 fps, the original one fades every other streamline
/// update (every ~1/18s), which is ~10 times per second.
const TRAIL_UPDATE_FPS: f32 = 10.0;

const TRAIL_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba16Float;
const WORKGROUP: u32 = shaders::simulate::compute::MAIN_WORKGROUP_SIZE[0];

pub struct Renderer {
    config: Config,
    globals: Globals,

    globals_buf: wgpu::Buffer,
    particle_buf: wgpu::Buffer,

    wind_view: wgpu::TextureView,
    wind_sampler: wgpu::Sampler,
    trail_view: wgpu::TextureView,
    trail_sampler: wgpu::Sampler,
    trail_cleared: bool,

    sim_bg: shaders::simulate::bind_groups::BindGroup0,
    trail_bg: shaders::trail::bind_groups::BindGroup0,
    composite_bg: shaders::composite::bind_groups::BindGroup0,

    sim_pipeline: wgpu::ComputePipeline,
    fade_pipeline: wgpu::RenderPipeline,
    trail_pipeline: wgpu::RenderPipeline,
    composite_pipeline: wgpu::RenderPipeline,

    width: u32,
    height: u32,
    user_location: [f32; 2], // (lng, lat) degrees
    time_acc: f32,
    current_alpha_decay: f32,
    redraw_counter: u32,
    redraw_target: u32,
}

impl Renderer {
    pub fn new(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        surface_format: wgpu::TextureFormat,
        config: Config,
        width: u32,
        height: u32,
    ) -> Self {
        let width = width.max(1);
        let height = height.max(1);

        let globals_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("windy.globals"),
            size: std::mem::size_of::<Globals>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let particles = init_particles(&config);
        let particle_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("windy.particles"),
            contents: bytemuck::cast_slice(&particles),
            usage: wgpu::BufferUsages::STORAGE,
        });

        let (_, wind_view) = create_wind_texture(device, queue, 1, 1, &[128, 128, 0, 255]); // initial neutral field
        let wind_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("windy.wind_sampler"),
            address_mode_u: wgpu::AddressMode::Repeat, // wrap longitude
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });

        let (_, trail_view) = create_trail_texture(device, width, height, &config);
        let trail_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("windy.trail_sampler"),
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });

        let sim_pipeline = shaders::simulate::compute::create_main_pipeline(device);

        let trail_module = shaders::trail::create_shader_module(device);
        let trail_pl = shaders::trail::create_pipeline_layout(device);
        let trail_blend = wgpu::BlendState {
            color: wgpu::BlendComponent {
                src_factor: wgpu::BlendFactor::SrcAlpha,
                dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
                operation: wgpu::BlendOperation::Add,
            },
            alpha: wgpu::BlendComponent {
                src_factor: wgpu::BlendFactor::SrcAlpha,
                dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
                operation: wgpu::BlendOperation::Add,
            },
        };
        let trail_vs = shaders::trail::vs_main_entry();
        let trail_fs = shaders::trail::fs_main_entry([Some(wgpu::ColorTargetState {
            format: TRAIL_FORMAT,
            blend: Some(trail_blend),
            write_mask: wgpu::ColorWrites::ALL,
        })]);
        let trail_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("windy.trail_pipeline"),
            layout: Some(&trail_pl),
            vertex: shaders::trail::vertex_state(&trail_module, &trail_vs),
            fragment: Some(shaders::trail::fragment_state(&trail_module, &trail_fs)),
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview_mask: None,
            cache: None,
        });

        let fade_module = shaders::fade::create_shader_module(device);
        let fade_pl = shaders::fade::create_pipeline_layout(device);
        let fade_blend = wgpu::BlendComponent {
            src_factor: wgpu::BlendFactor::Zero,
            dst_factor: wgpu::BlendFactor::Constant,
            operation: wgpu::BlendOperation::Add,
        };
        let fade_vs = shaders::fade::vs_main_entry();
        let fade_fs = shaders::fade::fs_main_entry([Some(wgpu::ColorTargetState {
            format: TRAIL_FORMAT,
            blend: Some(wgpu::BlendState {
                color: fade_blend,
                alpha: fade_blend,
            }),
            write_mask: wgpu::ColorWrites::ALL,
        })]);
        let fade_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("windy.fade_pipeline"),
            layout: Some(&fade_pl),
            vertex: shaders::fade::vertex_state(&fade_module, &fade_vs),
            fragment: Some(shaders::fade::fragment_state(&fade_module, &fade_fs)),
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview_mask: None,
            cache: None,
        });

        let composite_module = shaders::composite::create_shader_module(device);
        let composite_pl = shaders::composite::create_pipeline_layout(device);
        let composite_vs = shaders::composite::vs_main_entry();
        let composite_fs = shaders::composite::fs_main_entry([Some(wgpu::ColorTargetState {
            format: surface_format,
            blend: None,
            write_mask: wgpu::ColorWrites::ALL,
        })]);
        let composite_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("windy.composite_pipeline"),
            layout: Some(&composite_pl),
            vertex: shaders::composite::vertex_state(&composite_module, &composite_vs),
            fragment: Some(shaders::composite::fragment_state(
                &composite_module,
                &composite_fs,
            )),
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview_mask: None,
            cache: None,
        });

        let config_alpha_decay = config.alpha_decay;
        let sim_bg = make_sim_bg(
            device,
            &globals_buf,
            &particle_buf,
            &wind_view,
            &wind_sampler,
        );
        let trail_bg = make_trail_bg(device, &globals_buf, &particle_buf);
        let composite_bg = make_composite_bg(
            device,
            &globals_buf,
            &trail_view,
            &trail_sampler,
            &wind_view,
            &wind_sampler,
        );
        let mut renderer = Self {
            globals: Globals::zeroed(),
            config,
            globals_buf,
            particle_buf,
            wind_view,
            wind_sampler,
            trail_view,
            trail_sampler,
            trail_cleared: false,
            sim_bg,
            trail_bg,
            composite_bg,
            sim_pipeline,
            fade_pipeline,
            trail_pipeline,
            composite_pipeline,
            width,
            height,
            user_location: [-97.0, 38.0],
            time_acc: 0.0,
            current_alpha_decay: config_alpha_decay,
            redraw_counter: 0,
            redraw_target: REDRAW_FRAMES,
        };
        renderer.update_static_globals();
        renderer.globals.srgb_output = surface_format.is_srgb() as u32;
        renderer
    }

    pub fn set_wind_field(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        width: u32,
        height: u32,
        rgba: &[u8],
    ) {
        let (_, view) = create_wind_texture(device, queue, width, height, rgba);
        self.wind_view = view;
        self.rebuild_bind_groups(device);
        self.current_alpha_decay = self.config.alpha_decay_changed;
        self.trigger_redraw();
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    pub fn set_config(&mut self, device: &wgpu::Device, config: Config) {
        let reset_particles = config.particle_count != self.config.particle_count;
        self.config = config;
        if reset_particles {
            let particles = init_particles(&self.config);
            self.particle_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("windy.particles"),
                contents: bytemuck::cast_slice(&particles),
                usage: wgpu::BufferUsages::STORAGE,
            });
            self.rebuild_bind_groups(device);
        }
        self.update_static_globals();
    }

    pub fn restart(&mut self, device: &wgpu::Device) {
        let particles = init_particles(&self.config);
        self.particle_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("windy.particles"),
            contents: bytemuck::cast_slice(&particles),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let (_, view) = create_trail_texture(device, self.width, self.height, &self.config);
        self.trail_view = view;
        self.trail_cleared = false;
        self.rebuild_bind_groups(device);
        self.update_static_globals();
        self.trigger_redraw();
    }

    pub fn set_user_location(&mut self, lng: f32, lat: f32) {
        // epsilon matches original one
        if (lng - self.user_location[0]).abs() > 0.1 || (lat - self.user_location[1]).abs() > 0.1 {
            self.current_alpha_decay = self.config.alpha_decay_changed;
            self.trigger_redraw();
        }
        self.user_location = [lng, lat];
        self.update_static_globals();
    }

    pub fn set_offset_x(&mut self, offset: f32) {
        self.globals.offset_x = offset.clamp(-1.0, 1.0); // parallaxs
    }

    pub fn resize(&mut self, device: &wgpu::Device, width: u32, height: u32) {
        let width = width.max(1);
        let height = height.max(1);
        if width == self.width && height == self.height {
            return;
        }
        self.width = width;
        self.height = height;
        let (_, view) = create_trail_texture(device, width, height, &self.config);
        self.trail_view = view;
        self.trail_cleared = false;
        self.rebuild_bind_groups(device);
        self.update_static_globals();
        self.trigger_redraw(); // trail buffer was cleared, ease it in
    }

    pub fn render(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        target: &wgpu::TextureView,
        dt: f32, // seconds since last frame
    ) {
        // on change, ease simulation speed from REDRAW_MAX_BOOST to 1
        let boost = self.ramp_boost();
        let total = dt.clamp(0.0, 1.0 / 18.0) * boost;
        let max_step = 1.0 / 18.0;
        let sub_steps = ((total / max_step).ceil() as u32).max(1);
        let sub_dt = total / sub_steps as f32;

        // run extra simulation steps
        for _ in 1..sub_steps {
            self.advance(queue, sub_dt);
            let mut enc = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("windy.substep"),
            });
            self.encode_simulate_and_trails(&mut enc);
            queue.submit(Some(enc.finish()));
        }

        // run simulation
        self.advance(queue, sub_dt);
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("windy.encoder"),
        });
        self.encode_simulate_and_trails(&mut encoder);

        // render
        {
            let mut rpass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("windy.composite"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: target,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            rpass.set_pipeline(&self.composite_pipeline);
            self.composite_bg.set(&mut rpass);
            rpass.draw(0..3, 0..1);
        }

        queue.submit(Some(encoder.finish()));
    }

    pub fn skip(&mut self, device: &wgpu::Device, queue: &wgpu::Queue, frames: u32) {
        // instantly run simulation without ease
        for _ in 0..frames {
            self.advance(queue, 1.0 / 30.0);
            let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("windy.warmup"),
            });
            self.encode_simulate_and_trails(&mut encoder);
            queue.submit(Some(encoder.finish()));
        }
    }

    fn ramp_boost(&mut self) -> f32 {
        if self.redraw_counter >= self.redraw_target {
            return 1.0;
        }
        let t = self.redraw_counter as f32 / self.redraw_target as f32;
        let cosv = (std::f32::consts::PI * t).cos() * 0.5 + 0.5; // 1 -> 0
        self.redraw_counter += 1;
        1.0 + (REDRAW_MAX_BOOST - 1.0) * cosv
    }

    fn trigger_redraw(&mut self) {
        self.redraw_counter = 0;
        self.redraw_target = REDRAW_FRAMES;
    }

    fn advance(&mut self, queue: &wgpu::Queue, dt: f32) {
        let dt = dt.clamp(0.0, 1.0 / 18.0); // match the original's max step
        self.time_acc = (self.time_acc + dt) % 64.0;

        // ease trail decay back down from `alpha_decay_changed` (fps-independent)
        let ease = 1.0 - (1.0f32 - 0.019).powf((dt * 60.0).max(0.0));
        self.current_alpha_decay += (self.config.alpha_decay - self.current_alpha_decay) * ease;

        self.globals.time_delta = dt;
        self.globals.time_acc = self.time_acc;

        // trail length fade (fps-independent)
        self.globals.fade_decay = self
            .current_alpha_decay
            .powf((dt * TRAIL_UPDATE_FPS).max(0.0));
        queue.write_buffer(&self.globals_buf, 0, bytemuck::bytes_of(&self.globals));
    }

    fn encode_simulate_and_trails(&mut self, encoder: &mut wgpu::CommandEncoder) {
        {
            let mut cpass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("windy.simulate"),
                timestamp_writes: None,
            });
            cpass.set_pipeline(&self.sim_pipeline);
            self.sim_bg.set(&mut cpass);
            let groups = (self.config.particle_count + WORKGROUP - 1) / WORKGROUP;
            cpass.dispatch_workgroups(groups, 1, 1);
        }

        let load = if self.trail_cleared {
            wgpu::LoadOp::Load
        } else {
            wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT)
        };
        self.trail_cleared = true;

        let mut rpass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("windy.trails"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view: &self.trail_view,
                depth_slice: None,
                resolve_target: None,
                ops: wgpu::Operations {
                    load,
                    store: wgpu::StoreOp::Store,
                },
            })],
            depth_stencil_attachment: None,
            timestamp_writes: None,
            occlusion_query_set: None,
            multiview_mask: None,
        });

        let d = self.globals.fade_decay as f64;
        rpass.set_blend_constant(wgpu::Color {
            r: d,
            g: d,
            b: d,
            a: d,
        });
        rpass.set_pipeline(&self.fade_pipeline);
        rpass.draw(0..3, 0..1);

        rpass.set_pipeline(&self.trail_pipeline);
        self.trail_bg.set(&mut rpass);
        rpass.draw(0..6, 0..self.config.particle_count);
    }

    fn rebuild_bind_groups(&mut self, device: &wgpu::Device) {
        self.sim_bg = make_sim_bg(
            device,
            &self.globals_buf,
            &self.particle_buf,
            &self.wind_view,
            &self.wind_sampler,
        );
        self.trail_bg = make_trail_bg(device, &self.globals_buf, &self.particle_buf);
        self.composite_bg = make_composite_bg(
            device,
            &self.globals_buf,
            &self.trail_view,
            &self.trail_sampler,
            &self.wind_view,
            &self.wind_sampler,
        );
    }

    /// Recompute stuff that only changes on resize / location / config.
    fn update_static_globals(&mut self) {
        let c = &self.config;
        self.globals.vector_field_bounds = self.compute_bounds();
        self.globals.bg_color1 = c.bg_color1;
        self.globals.bg_color2 = c.bg_color2;
        self.globals.color_slow = c.slow_wind_color;
        self.globals.color_fast = c.fast_wind_color;
        self.globals.size = c.scale;
        self.globals.resolution = [self.height as f32 / self.width as f32, 1.0];
        self.globals.trail_size = [
            (self.width as f32 * c.scale[0]).max(1.0),
            (self.height as f32 * c.scale[1]).max(1.0),
        ];
        self.globals.screen_size = [self.width as f32, self.height as f32];
        self.globals.wind_speed = c.wind_speed;
        self.globals.particle_life = c.particle_life;
        self.globals.particle_opacity = c.particle_opacity;
        self.globals.line_half_width = c.line_half_width;
        self.globals.particle_count = c.particle_count;
    }

    fn compute_bounds(&self) -> [f32; 4] {
        // like the original wind field region code
        let [lng, lat] = self.user_location;
        let wnd_lng = self.config.window_size * (self.width as f32 / self.height as f32);
        let wnd_lat = self.config.window_size;
        let bound_l = (lng - wnd_lng / 2.0).clamp(-180.0, 180.0);
        let bound_t = (lat + wnd_lat / 2.0).clamp(-90.0, 90.0);
        let bound_r = (bound_l + wnd_lng).clamp(-180.0, 180.0);
        let bound_b = (bound_t - wnd_lat).clamp(-90.0, 90.0);
        let u = lng_to_ratio(bound_l);
        let v_top = lat_to_ratio(bound_t);
        let u2 = lng_to_ratio(bound_r);
        let v_bot = lat_to_ratio(bound_b);
        [u, v_top, u2 - u, v_bot - v_top]
    }
}

fn lng_to_ratio(lng: f32) -> f32 {
    (180.0 + lng) / 360.0
}

fn lat_to_ratio(lat: f32) -> f32 {
    1.0 - ((90.0 + lat) / 180.0)
}

fn init_particles(config: &Config) -> Vec<Particle> {
    let count = config.particle_count as usize;
    let dim = (count as f32).sqrt().ceil() as u32;
    let mut rng = Rng::new(0x9E3779B9);
    (0..count)
        .map(|i| {
            let gx = (i as u32 % dim) as f32 / dim as f32;
            let gy = (i as u32 / dim) as f32 / dim as f32;
            let pos = [rng.next_f32(), rng.next_f32()];
            Particle {
                pos,
                prev: pos,
                uv: [gx, gy],
                age: rng.next_f32(),
                _pad: 0.0,
            }
        })
        .collect()
}

fn create_wind_texture(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    width: u32,
    height: u32,
    rgba: &[u8],
) -> (wgpu::Texture, wgpu::TextureView) {
    let size = wgpu::Extent3d {
        width,
        height,
        depth_or_array_layers: 1,
    };
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("windy.wind"),
        size,
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm, // data, not color: linear
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    queue.write_texture(
        wgpu::TexelCopyTextureInfo {
            texture: &texture,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        rgba,
        wgpu::TexelCopyBufferLayout {
            offset: 0,
            bytes_per_row: Some(4 * width),
            rows_per_image: Some(height),
        },
        size,
    );
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    (texture, view)
}

fn create_trail_texture(
    device: &wgpu::Device,
    width: u32,
    height: u32,
    config: &Config,
) -> (wgpu::Texture, wgpu::TextureView) {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("windy.trail"),
        size: wgpu::Extent3d {
            width: (width as f32 * config.scale[0]).max(1.0) as u32,
            height: (height as f32 * config.scale[1]).max(1.0) as u32,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: TRAIL_FORMAT,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    (texture, view)
}

fn make_sim_bg(
    device: &wgpu::Device,
    globals_buf: &wgpu::Buffer,
    particle_buf: &wgpu::Buffer,
    wind_view: &wgpu::TextureView,
    wind_sampler: &wgpu::Sampler,
) -> shaders::simulate::bind_groups::BindGroup0 {
    shaders::simulate::bind_groups::BindGroup0::from_bindings(
        device,
        shaders::simulate::bind_groups::BindGroupLayout0 {
            g: globals_buf.as_entire_buffer_binding(),
            particles: particle_buf.as_entire_buffer_binding(),
            wind_tex: wind_view,
            wind_samp: wind_sampler,
        },
    )
}

fn make_trail_bg(
    device: &wgpu::Device,
    globals_buf: &wgpu::Buffer,
    particle_buf: &wgpu::Buffer,
) -> shaders::trail::bind_groups::BindGroup0 {
    shaders::trail::bind_groups::BindGroup0::from_bindings(
        device,
        shaders::trail::bind_groups::BindGroupLayout0 {
            g: globals_buf.as_entire_buffer_binding(),
            particles: particle_buf.as_entire_buffer_binding(),
        },
    )
}

fn make_composite_bg(
    device: &wgpu::Device,
    globals_buf: &wgpu::Buffer,
    trail_view: &wgpu::TextureView,
    trail_sampler: &wgpu::Sampler,
    wind_view: &wgpu::TextureView,
    wind_sampler: &wgpu::Sampler,
) -> shaders::composite::bind_groups::BindGroup0 {
    shaders::composite::bind_groups::BindGroup0::from_bindings(
        device,
        shaders::composite::bind_groups::BindGroupLayout0 {
            g: globals_buf.as_entire_buffer_binding(),
            trail_tex: trail_view,
            trail_samp: trail_sampler,
            wind_tex: wind_view,
            wind_samp: wind_sampler,
        },
    )
}

// xorshift rng for particle seed for reproducibility
struct Rng(u32);
impl Rng {
    fn new(seed: u32) -> Self {
        Self(seed | 1)
    }
    fn next_u32(&mut self) -> u32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        x
    }
    fn next_f32(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 / (1u32 << 24) as f32
    }
}
