// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::fmt;

use crate::config::{Theme, ThemeParams};

/// A theme to render as Rust source (via [`fmt::Display`]).
pub struct ThemeSource<'a> {
    pub name: &'a str,
    pub slow_wind_color: [f32; 4],
    pub fast_wind_color: [f32; 4],
    pub bg_color1: [f32; 4],
    pub bg_color2: [f32; 4],
    pub wallpaper_color: [f32; 3],
    /// Rendered as `None` if it matches the [`ThemeParams`] defaults.
    pub params: ThemeParams,
}

impl<'a> From<&'a Theme> for ThemeSource<'a> {
    fn from(theme: &'a Theme) -> Self {
        Self {
            name: theme.name,
            slow_wind_color: theme.slow_wind_color,
            fast_wind_color: theme.fast_wind_color,
            bg_color1: theme.bg_color1,
            bg_color2: theme.bg_color2,
            wallpaper_color: theme.wallpaper_color,
            params: theme.params.unwrap_or_default(),
        }
    }
}

impl fmt::Display for ThemeSource<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let name = rust_name(self.name);
        let ident = rust_ident(&name);
        let slow = pack_rgba(self.slow_wind_color);
        let fast = pack_rgba(self.fast_wind_color);
        // alpha doesn't affect these, so remove the alpha
        let bg1 = pack_rgba(opaque(self.bg_color1));
        let bg2 = pack_rgba(opaque(self.bg_color2));
        let [r, g, b] = self.wallpaper_color;
        let tint = pack_rgba([r, g, b, 1.0]);

        writeln!(f, "pub const {ident}: Theme = Theme {{")?;
        writeln!(f, "    name: \"{name}\",")?;
        writeln!(f, "    slow_wind_color: rgba8(0x{slow:08X}),")?;
        writeln!(f, "    fast_wind_color: rgba8(0x{fast:08X}),")?;
        writeln!(f, "    bg_color1: rgba8(0x{bg1:08X}),")?;
        writeln!(f, "    bg_color2: rgba8(0x{bg2:08X}),")?;
        writeln!(f, "    wallpaper_color: rgb8(0x{tint:08X}),")?;
        if self.params == ThemeParams::default() {
            writeln!(f, "    params: None,")?;
        } else {
            writeln!(f, "    params: Some(ThemeParams {{")?;
            for (key, value) in [
                ("line_half_width", self.params.line_half_width),
                ("particle_opacity", self.params.particle_opacity),
                ("alpha_decay", self.params.alpha_decay),
                ("wind_speed", self.params.wind_speed),
            ] {
                writeln!(f, "        {key}: {},", rust_float(value))?;
            }
            writeln!(f, "    }}),")?;
        }
        writeln!(f, "}};")
    }
}

/// A theme name, e.g. `"Deep blue"` -> `"DeepBlue"`.
fn rust_name(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    for word in name.split(|c: char| !c.is_ascii_alphanumeric()) {
        let mut chars = word.chars();
        if let Some(first) = chars.next() {
            out.push(first.to_ascii_uppercase());
            out.extend(chars);
        }
    }
    if out.is_empty() {
        out.push_str("Custom");
    }
    out
}

/// A theme constant name, e.g. `"DeepBlue"` -> `"DEEP_BLUE"`.
fn rust_ident(name: &str) -> String {
    let mut out = String::with_capacity(name.len() + 4);
    let mut split = false; // the previous char was lowercase or a digit
    for c in name.chars() {
        if split && c.is_ascii_uppercase() {
            out.push('_');
        }
        split = c.is_ascii_lowercase() || c.is_ascii_digit();
        out.push(c.to_ascii_uppercase());
    }
    out
}

/// `[r, g, b, a]` from `[0,1]` to packed `0xRRGGBBAA` for rgba8/rgb8.
fn pack_rgba(rgba: [f32; 4]) -> u32 {
    let c = |x: f32| (x.clamp(0.0, 1.0) * 255.0).round() as u32;
    (c(rgba[0]) << 24) | (c(rgba[1]) << 16) | (c(rgba[2]) << 8) | c(rgba[3])
}

/// A color with the alpha discarded.
fn opaque(rgba: [f32; 4]) -> [f32; 4] {
    [rgba[0], rgba[1], rgba[2], 1.0]
}

/// A rounded param value without trailing zeros.
fn rust_float(value: f32) -> String {
    let mut out = format!("{value:.4}");
    while out.ends_with('0') {
        out.pop();
    }
    if out.ends_with('.') {
        out.push('0');
    }
    out
}
