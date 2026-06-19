// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

@group(0) @binding(0) var<uniform> g: Globals;
@group(0) @binding(1) var trail_tex: texture_2d<f32>;
@group(0) @binding(2) var trail_samp: sampler;
@group(0) @binding(3) var wind_tex: texture_2d<f32>;
@group(0) @binding(4) var wind_samp: sampler;

struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vidx: u32) -> VsOut {
    let xy = vec2<f32>(f32((vidx << 1u) & 2u), f32(vidx & 2u));
    var out: VsOut;
    out.clip = vec4<f32>(xy * 2.0 - 1.0, 0.0, 1.0);
    out.uv = vec2<f32>(xy.x, 1.0 - xy.y);
    return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    // screen uv to normalized field position + overscan centering and panning
    let parallax = vec2<f32>(g.offset_x * (g.size.x - 1.0) * 0.5, 0.0);
    let t = (in.uv - 0.5) / g.size + 0.5 + parallax;

    // background tint from wind speed (field blue channel).
    var buv = g.vector_field_bounds.xy + t * g.vector_field_bounds.zw * g.size;
    buv = equirect_to_mercator(buv);
    let speed = textureSample(wind_tex, wind_samp, buv).b;
    let bg = mix(g.bg_color1, g.bg_color2, speed);

    // streamlines
    let trail = textureSample(trail_tex, trail_samp, t);
    let particle_color = mix(g.color_slow, g.color_fast, trail.r);

    // discard the alpha to match how the original one is rendered
    var out = vec4<f32>(mix(bg, particle_color, particle_color.a * trail.g).rgb, 1.0);

    // fix colors on srgb targets
    if (g.srgb_output != 0u) {
        out = vec4<f32>(srgb_to_linear(out.rgb), out.a);
    }
    return out;
}

fn srgb_to_linear(c: vec3<f32>) -> vec3<f32> {
    let low = c / 12.92;
    let high = pow((c + 0.055) / 1.055, vec3<f32>(2.4));
    return select(high, low, c <= vec3<f32>(0.04045));
}
