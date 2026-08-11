// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use material_colors::color::Argb;
use material_colors::hct::Hct;

// Android uses the CAM16 hue and CIELAB L* for the Material You palette, and
// requires a chroma of at least 5.

use crate::config::{Theme, ThemeColors};

// average color coverage for a few locations and default colors/params
const BG_SPEED_RATIO: f32 = 0.3110; // average mix from bg1 to bg2 (i.e., wind speed)
const FG_RATIO: f32 = 0.2141; // average screen coverage of streamlines if fully opaque
const FAST_COVERAGE: f32 = 0.0228; // average mix from slow to fast color (considering only streamlines)
const FAST_COVERAGE_WEIGHTED: f32 = 0.0061; // with twice the weight for the fast color (since most themes have different alphas for fast and slow)

// color adjustments for android
const MIN_CHROMA: f64 = 8.0; // minimum chroma to clamp tint to
const MAX_CHROMA: f64 = 60.0; // maximum chroma to clamp tint to
const MIN_TONE: f64 = 15.0; // minimum tone to clamp to (so it doesn't turn monochrome)
const MAX_TONE: f64 = 90.0; // minimum tone to clamp to (so it doesn't turn monochrome)

impl ThemeColors {
    fn all(&self) -> [[f32; 4]; 4] {
        [
            self.bg_color1,
            self.bg_color2,
            self.slow_wind_color,
            self.fast_wind_color,
        ]
    }

    /// Roughly estimate the percentage of a frame covered by each color.
    pub fn area(&self) -> [f32; 4] {
        // take streamlines out first, then split the rest for the background
        let slow_alpha = self.slow_wind_color[3];
        let extra_alpha = self.fast_wind_color[3] - slow_alpha;
        let slow = slow_alpha * (FG_RATIO - FAST_COVERAGE)
            + extra_alpha * (FAST_COVERAGE - FAST_COVERAGE_WEIGHTED);
        let fast = slow_alpha * FAST_COVERAGE + extra_alpha * FAST_COVERAGE_WEIGHTED;
        let bg = 1.0 - slow - fast;
        [bg * (1.0 - BG_SPEED_RATIO), bg * BG_SPEED_RATIO, slow, fast]
    }

    /// Compute the Android system wallpaper color for a theme.
    pub fn wallpaper_color(&self) -> [f32; 3] {
        let area = self.area();
        let mut best = hct(self.bg_color1);
        let mut best_weight = f32::MIN;
        for (color, area) in self.all().into_iter().zip(area) {
            let hct = hct(color);
            let chroma = hct.get_chroma() as f32;
            let weight = area.max(0.0) * chroma * chroma;
            if weight > best_weight {
                best = hct;
                best_weight = weight;
            }
        }
        rgb(Hct::from(
            best.get_hue(),
            best.get_chroma().clamp(MIN_CHROMA, MAX_CHROMA),
            best.get_tone().clamp(MIN_TONE, MAX_TONE),
        ))
    }
}

impl Theme {
    pub fn wallpaper_color(&self) -> [f32; 3] {
        self.colors.wallpaper_color()
    }
}

/// `[r, g, b, a]` from `[0,1]`, discarding the alpha.
fn argb(rgba: [f32; 4]) -> Argb {
    let to8 = |c: f32| (c.clamp(0.0, 1.0) * 255.0).round() as u8;
    Argb::new(255, to8(rgba[0]), to8(rgba[1]), to8(rgba[2]))
}

fn hct(rgba: [f32; 4]) -> Hct {
    Hct::new(argb(rgba))
}

fn rgb(hct: Hct) -> [f32; 3] {
    let argb = Argb::from(hct);
    [
        f32::from(argb.red) / 255.0,
        f32::from(argb.green) / 255.0,
        f32::from(argb.blue) / 255.0,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn theme_tints() {
        // the tints the original themes shipped with (surprisingly, they pretty
        // much matched after tweaking the ratios slightly, so they must have
        // done something like this to create the official themes)
        for (theme, tint) in [
            (Theme::BLUE, 0x044866),
            (Theme::BLUSH, 0x4078C8),
            (Theme::SUNSET_WHIRLED, 0xE58186),
            (Theme::TURQUOISE_WHIRLED, 0x0093B9),
            (Theme::SKY_BLUE_WHIRLED, 0x75AAFA),
        ] {
            assert_eq!(hex(theme.wallpaper_color()), tint, "{}", theme.name);
        }
    }

    #[test]
    fn area_percent() {
        for theme in Theme::ALL {
            let area = theme.colors.area();
            let total: f32 = area.iter().sum();
            assert!((total - 1.0).abs() < 1e-4, "{}: {area:?}", theme.name);
            assert!(area.iter().all(|a| *a >= 0.0), "{}: {area:?}", theme.name);
        }
    }

    fn hex(rgb: [f32; 3]) -> u32 {
        let c = |x: f32| (x.clamp(0.0, 1.0) * 255.0).round() as u32;
        (c(rgb[0]) << 16) | (c(rgb[1]) << 8) | c(rgb[2])
    }
}
