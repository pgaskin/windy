// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

@vertex
fn vs_main(@builtin(vertex_index) vidx: u32) -> @builtin(position) vec4<f32> {
    // triangle, oversized so entire screen is covered
    let xy = vec2<f32>(f32((vidx << 1u) & 2u), f32(vidx & 2u));

    // multiply target by fade
    return vec4<f32>(xy * 2.0 - 1.0, 0.0, 1.0);
}

@fragment
fn fs_main() -> @location(0) vec4<f32> {
    // discard source (fade is constant)
    return vec4<f32>(0.0);
}
