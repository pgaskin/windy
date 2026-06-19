// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

const PI: f32 = 3.1415926535897932;

struct Globals {
    vector_field_bounds: vec4<f32>, // region of wind texture (u, v_top, du, dv)
    bg_color1: vec4<f32>,
    bg_color2: vec4<f32>,
    color_slow: vec4<f32>,
    color_fast: vec4<f32>,
    size: vec2<f32>,        // android home screen parallax scale (x, y)
    resolution: vec2<f32>,  // aspect-ratio correction for simulation (h/w, 1)
    trail_size: vec2<f32>,  // trail texture dimensions (real px)
    screen_size: vec2<f32>, // output target dimensions (real px)
    time_delta: f32,        // seconds since last frame
    time_acc: f32,          // wrapped time accumulator (used as a seed)
    wind_speed: f32,
    particle_life: f32,
    particle_opacity: f32,
    line_half_width: f32,   // streamline half-width (real px)
    offset_x: f32,          // android home screen parallax offset [-1, 1]
    fade_decay: f32,        // per-frame trail fade multiplier
    particle_count: u32,
    srgb_output: u32,
    _pad0: u32,
    _pad1: u32,
};

struct Particle {
    pos: vec2<f32>,   // current position, normalized to [0,1]
    prev: vec2<f32>,  // previous position (for drawing line)
    uv: vec2<f32>,    // stable per-particle seed coordinate
    age: f32,         // remaining life, ~[0,1]
    _pad: f32,
};

// same as original glsl rand
fn rand(co: vec2<f32>) -> f32 {
    let dt = dot(co, vec2<f32>(12.9898, 78.233));
    let sn = dt % 3.14;
    return fract(sin(sn) * 43758.5453);
}

// latitude equirectangular to mercator
fn equirect_to_mercator(uv: vec2<f32>) -> vec2<f32> {
    let latitude = (uv.y * 2.0 - 1.0) * PI * 0.5;
    var y = atan(sinh(latitude)) / (PI * 0.5);
    y = y * 0.5 + 0.5;
    return vec2<f32>(uv.x, y);
}
