// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

@group(0) @binding(0) var<uniform> g: Globals;
@group(0) @binding(1) var<storage, read> particles: array<Particle>;

struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) local: vec2<f32>, // (along, across) in px relative to segment center
    @location(1) half_len: f32,    // half the segment length in px
    @location(2) selector: f32,    // 0 = slow color, 1 = fast color
    @location(3) alpha: f32,       // life-based opacity
};

@vertex
fn vs_main(@builtin(vertex_index) vidx: u32, @builtin(instance_index) iidx: u32) -> VsOut {
    let p = particles[iidx];

    let a = p.prev * g.trail_size;
    let b = p.pos * g.trail_size;
    let center = (a + b) * 0.5;

    var dir = b - a;
    let len = length(dir);
    if (len > 1e-4) {
        dir = dir / len;
    } else {
        dir = vec2<f32>(1.0, 0.0);
    }
    let normal = vec2<f32>(-dir.y, dir.x);

    // pad by half-width+1px to give room for antialiasing
    let half_w = g.line_half_width + 1.0;
    let half_l = len * 0.5;

    // fill the screen
    var corners = array<vec2<f32>, 6>(
        vec2<f32>(-1.0, -1.0), vec2<f32>(1.0, -1.0), vec2<f32>(1.0, 1.0),
        vec2<f32>(-1.0, -1.0), vec2<f32>(1.0, 1.0), vec2<f32>(-1.0, 1.0),
    );
    let c = corners[vidx];
    let along = (half_l + half_w) * c.x;
    let across = half_w * c.y;

    let px = center + dir * along + normal * across;
    let ndc = vec2<f32>(px.x / g.trail_size.x * 2.0 - 1.0, 1.0 - px.y / g.trail_size.y * 2.0);

    var out: VsOut;
    out.clip = vec4<f32>(ndc, 0.0, 1.0);
    out.local = vec2<f32>(along, across);
    out.half_len = half_l;
    out.selector = step(0.7, rand(p.uv));

    // fade in on spawn, out on death
    let life = smoothstep(0.0, 0.05, 1.0 - p.age) * smoothstep(0.0, 0.25, p.age);

    // accumulate proportional to distance travelled
    // (instead of per-frame like the original one, which made things brighter on slower framerates)
    let r = min(len / max(2.0 * g.line_half_width, 1e-3), 1.0);
    out.alpha = life * r;
    return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    // round line caps
    let qx = max(abs(in.local.x) - in.half_len, 0.0);
    let dist = length(vec2<f32>(qx, in.local.y)); // distance from centerline

    // smooth edges with thin line like the original point sprite
    let dn = dist / g.line_half_width;
    let falloff = 1.0 - smoothstep(0.1, 1.0, dn * dn);

    let alpha = falloff * in.alpha * g.particle_opacity;
    return vec4<f32>(
        in.selector, // red: slow/fast
        1.0, // green: blending
        0.0, // blue: n/a
        alpha, // blending
    );
}
