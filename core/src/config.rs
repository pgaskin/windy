// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

#[derive(Clone, Debug)]
pub struct Config {
    /// Degrees of longitude shown at once.
    pub window_size: f32,

    /// Parallax overscan (trail buffer is `screen * scale`).
    pub scale: [f32; 2],

    /// Number of simulated particles.
    pub particle_count: u32,

    /// Base speed of simulated particles.
    pub wind_speed: f32,

    // Lifetime of simulated particles.
    pub particle_life: f32,

    pub slow_wind_color: [f32; 4], // rgba [0,1]
    pub fast_wind_color: [f32; 4], // rgba [0,1]
    pub bg_color1: [f32; 4],       // rgba [0,1]
    pub bg_color2: [f32; 4],       // rgba [0,1]

    /// Trail fade per 1/60s step.
    pub alpha_decay: f32,

    /// Faster trail fade applied briefly after the wind field or location
    /// changes, to clear old streamlines.
    pub alpha_decay_changed: f32,

    /// Streamline opacity (i.e., how much color is accumulated each step).
    pub particle_opacity: f32,

    /// Streamline core half-width (real pixels). Should be scaled by the real
    /// display density for consistency across devices.
    pub line_half_width: f32,
}

impl Config {
    /// A [`Config`] using a named [`Theme`]'s colors with default dynamics.
    pub fn with_theme(theme: &Theme) -> Self {
        Self {
            slow_wind_color: theme.slow_wind_color,
            fast_wind_color: theme.fast_wind_color,
            bg_color1: theme.bg_color1,
            bg_color2: theme.bg_color2,
            ..Self::default()
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Theme {
    pub name: &'static str,
    pub slow_wind_color: [f32; 4],
    pub fast_wind_color: [f32; 4],
    pub bg_color1: [f32; 4],
    pub bg_color2: [f32; 4],
    pub wallpaper_color: [f32; 3], // does not affect rendering, currently only for Android
}

impl Theme {
    pub const BLUE: Theme = Theme {
        name: "Blue",
        slow_wind_color: [0.498_039_22, 0.819_607_85, 0.584_313_75, 0.30],
        fast_wind_color: [0.980_392_16, 0.941_176_5, 0.823_529_4, 0.25],
        bg_color1: rgba8(0x044866FF),
        bg_color2: rgba8(0x0085AAFF),
        wallpaper_color: rgb8(0x085173FF),
    };
    pub const GREEN: Theme = Theme {
        name: "Green",
        slow_wind_color: [0.50, 0.82, 0.18, 0.30],
        fast_wind_color: [0.98, 0.94, 0.12, 0.25],
        bg_color1: rgba8(0x044822FF),
        bg_color2: rgba8(0x008533FF),
        wallpaper_color: rgb8(0x085112FF),
    };
    pub const BLUSH: Theme = Theme {
        name: "Blush",
        slow_wind_color: [0.850_980_4, 0.690_196_1, 0.917_647_06, 0.30],
        fast_wind_color: [0.862_745_1, 0.964_705_9, 1.0, 0.50],
        bg_color1: rgba8(0x4078C8FF),
        bg_color2: rgba8(0xD9B0EAFF),
        wallpaper_color: rgb8(0x4A7CC9FF),
    };
    pub const MIDNIGHT: Theme = Theme {
        name: "Midnight",
        slow_wind_color: [0.215_686_28, 0.219_607_84, 0.215_686_28, 0.25],
        fast_wind_color: [0.729_411_8, 0.741_176_5, 0.737_254_9, 0.30],
        bg_color1: rgba8(0x000000AF),
        bg_color2: rgba8(0x464749FF),
        wallpaper_color: rgb8(0x101110FF),
    };
    pub const MAROON: Theme = Theme {
        name: "Maroon",
        slow_wind_color: [0.576, 0.192, 0.192, 0.25],
        fast_wind_color: [0.792, 0.376, 0.376, 0.30],
        bg_color1: rgba8(0x1A0909FF),
        bg_color2: rgba8(0x451717FF),
        wallpaper_color: rgb8(0x4F1A1AFF),
    };
    pub const SEPIA: Theme = Theme {
        name: "Sepia",
        slow_wind_color: [0.26, 0.16, 0.05, 0.25],
        fast_wind_color: [0.44, 0.28, 0.11, 0.30],
        bg_color1: rgba8(0xBDA682FF),
        bg_color2: rgba8(0xC49F64FF),
        wallpaper_color: rgb8(0xD87900FF),
    };
    pub const SUNSET_WHIRLED: Theme = Theme {
        name: "SunsetWhirled",
        slow_wind_color: [0.976_470_6, 0.862_745_1, 0.647_058_84, 0.60],
        fast_wind_color: [1.0, 1.0, 1.0, 0.70],
        bg_color1: rgba8(0xE58186DF),
        bg_color2: rgba8(0xF7B38DDF),
        wallpaper_color: rgb8(0xE88991FF),
    };
    pub const TURQUOISE_WHIRLED: Theme = Theme {
        name: "TurquoiseWhirled",
        slow_wind_color: [0.498_039_22, 0.819_607_85, 0.584_313_75, 0.60],
        fast_wind_color: [1.0, 1.0, 1.0, 0.50],
        bg_color1: rgba8(0x0093B9DF),
        bg_color2: rgba8(0xEFDD81DF),
        wallpaper_color: rgb8(0x0996B7FF),
    };
    pub const SKY_BLUE_WHIRLED: Theme = Theme {
        name: "SkyBlueWhirled",
        slow_wind_color: [1.0, 1.0, 1.0, 0.50],
        fast_wind_color: [0.956_862_75, 1.0, 0.529_411_8, 0.25],
        bg_color1: rgba8(0x75AAFAFF),
        bg_color2: rgba8(0xF4FF87FF),
        wallpaper_color: rgb8(0x7BAEF5FF),
    };
    pub const SPARK_WHIRLED: Theme = Theme {
        name: "SparkWhirled",
        slow_wind_color: [0.25, 0.00, 0.50, 0.85],
        fast_wind_color: [1.00, 0.50, 0.00, 0.65],
        bg_color1: rgba8(0x270D03FF),
        bg_color2: rgba8(0x031A27FF),
        wallpaper_color: rgb8(0x96241AFF),
    };

    // indexes must match app
    pub const ALL: &'static [Theme] = &[
        Theme::BLUE,
        Theme::GREEN,
        Theme::BLUSH,
        Theme::MIDNIGHT,
        Theme::MAROON,
        Theme::SEPIA,
        Theme::SUNSET_WHIRLED,
        Theme::TURQUOISE_WHIRLED,
        Theme::SKY_BLUE_WHIRLED,
        Theme::SPARK_WHIRLED,
    ];
}

impl Default for Config {
    fn default() -> Self {
        Self {
            window_size: 75.0,
            scale: [1.2, 1.15],
            particle_count: 2048,
            wind_speed: 0.1,
            particle_life: 8.0,
            slow_wind_color: [1.0, 0.0, 1.0, 1.0],
            fast_wind_color: [0.0, 1.0, 1.0, 1.0],
            bg_color1: [0.1, 0.0, 0.1, 0.1],
            bg_color2: [0.1, 0.0, 0.1, 0.1],
            alpha_decay: 0.9965,
            alpha_decay_changed: 0.91,
            particle_opacity: 1.0,
            line_half_width: 1.0,
        }
    }
}

/// Packed `0xRRGGBBAA` to `[r, g, b, a]` from `[0,1]`.
const fn rgba8(hex: u32) -> [f32; 4] {
    [
        ((hex >> 24) & 0xff) as f32 / 255.0,
        ((hex >> 16) & 0xff) as f32 / 255.0,
        ((hex >> 8) & 0xff) as f32 / 255.0,
        (hex & 0xff) as f32 / 255.0,
    ]
}

/// Packed `0xRRGGBBAA` to `[r, g, b]` from `[0,1]`, discarding alpha.
const fn rgb8(hex: u32) -> [f32; 3] {
    [
        ((hex >> 24) & 0xff) as f32 / 255.0,
        ((hex >> 16) & 0xff) as f32 / 255.0,
        ((hex >> 8) & 0xff) as f32 / 255.0,
    ]
}
