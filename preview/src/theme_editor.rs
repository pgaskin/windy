// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use windy_wallpaper_core::{Config, Theme};

pub struct ThemeEditor {
    pub name: String,
    pub slow_wind_color: [f32; 4],
    pub fast_wind_color: [f32; 4],
    pub bg_color1: [f32; 4],
    pub bg_color2: [f32; 4],
    pub wind_speed: f32,
    pub particle_life: f32,
    pub particle_opacity: f32,
    pub line_half_width: f32,
    pub alpha_decay: f32,
    pub particle_count: u32,
    pub window_size: f32,
    pub scale: [f32; 2],
    pub open: bool,

    actions: Actions,
}

#[derive(Default)]
pub struct Actions {
    pub restart: bool,
    pub time_skip: bool,
}

impl ThemeEditor {
    pub fn from_theme(theme: &Theme) -> Self {
        Self::from_config(theme.name, &Config::with_theme(theme))
    }

    pub fn from_config(name: &str, c: &Config) -> Self {
        Self {
            name: name.to_string(),
            slow_wind_color: c.slow_wind_color,
            fast_wind_color: c.fast_wind_color,
            bg_color1: c.bg_color1,
            bg_color2: c.bg_color2,
            wind_speed: c.wind_speed,
            particle_life: c.particle_life,
            particle_opacity: c.particle_opacity,
            line_half_width: c.line_half_width,
            alpha_decay: c.alpha_decay,
            particle_count: c.particle_count,
            window_size: c.window_size,
            scale: c.scale,
            open: true,
            actions: Actions {
                restart: false,
                time_skip: false,
            },
        }
    }

    pub fn take_actions(&mut self) -> Actions {
        Actions {
            restart: std::mem::take(&mut self.actions.restart),
            time_skip: std::mem::take(&mut self.actions.time_skip),
        }
    }

    pub fn to_config(&self) -> Config {
        Config {
            window_size: self.window_size,
            scale: self.scale,
            particle_count: self.particle_count,
            wind_speed: self.wind_speed,
            particle_life: self.particle_life,
            slow_wind_color: self.slow_wind_color,
            fast_wind_color: self.fast_wind_color,
            bg_color1: self.bg_color1,
            bg_color2: self.bg_color2,
            particle_opacity: self.particle_opacity,
            line_half_width: self.line_half_width,
            alpha_decay: self.alpha_decay,
            ..Config::default()
        }
    }

    fn load_theme(&mut self, theme: &Theme) {
        let keep_open = self.open;
        *self = Self::from_theme(theme);
        self.open = keep_open;
    }

    pub fn ui(&mut self, ctx: &egui::Context) -> bool {
        if !self.open {
            return false;
        }
        let mut changed = false;
        egui::Window::new("Theme")
            .default_pos([12.0, 12.0])
            .title_bar(false)
            .resizable(false)
            .show(ctx, |ui| {
                let max_h = (ctx.content_rect().height() - 36.0).max(120.0);
                egui::ScrollArea::vertical()
                    .max_height(max_h)
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            let mut chosen: Option<&Theme> = None;
                            egui::ComboBox::from_id_salt("preset")
                                .selected_text(&self.name)
                                .show_ui(ui, |ui| {
                                    for t in Theme::ALL {
                                        if ui
                                            .selectable_label(self.name == t.name, t.name)
                                            .clicked()
                                        {
                                            chosen = Some(t);
                                        }
                                    }
                                });
                            if let Some(t) = chosen {
                                self.load_theme(t);
                                changed = true;
                            }
                            ui.add(
                                egui::TextEdit::singleline(&mut self.name)
                                    .hint_text("name")
                                    .desired_width(120.0),
                            );
                            if ui.button("Restart").clicked() {
                                self.actions.restart = true;
                            }
                            if ui.button("Time skip").clicked() {
                                self.actions.time_skip = true;
                            }
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    if ui.button("Copy theme").clicked() {
                                        ctx.copy_text(self.to_rust_snippet());
                                    }
                                },
                            );
                        });

                        ui.separator();
                        ui.columns(2, |c| {
                            let left = &mut c[0];
                            left.columns(2, |cc| {
                                changed |=
                                    color_row(&mut cc[0], "Slow wind", &mut self.slow_wind_color);
                                changed |=
                                    color_row(&mut cc[1], "Fast wind", &mut self.fast_wind_color);
                            });
                            left.columns(2, |cc| {
                                changed |=
                                    color_row(&mut cc[0], "Background 1", &mut self.bg_color1);
                                changed |=
                                    color_row(&mut cc[1], "Background 2", &mut self.bg_color2);
                            });

                            let right = &mut c[1];
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.wind_speed, 0.0..=0.5)
                                        .text("wind speed"),
                                )
                                .changed();
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.particle_life, 0.5..=30.0)
                                        .text("particle life"),
                                )
                                .changed();
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.particle_opacity, 0.0..=2.0)
                                        .text("line opacity"),
                                )
                                .changed();
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.line_half_width, 0.25..=4.0)
                                        .text("line half-width (px)"),
                                )
                                .changed();
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.alpha_decay, 0.95..=0.9999)
                                        .text("trail decay")
                                        .custom_formatter(|v, _| format!("{v:.4}")),
                                )
                                .changed();
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.particle_count, 256..=8192)
                                        .text("particle count"),
                                )
                                .changed();
                            changed |= right
                                .add(
                                    egui::Slider::new(&mut self.window_size, 10.0..=180.0)
                                        .text("longitude degrees"),
                                )
                                .changed();
                        });
                    });
            });
        changed
    }

    fn to_rust_snippet(&self) -> String {
        let arr = |c: [f32; 4]| format!("[{:.4}, {:.4}, {:.4}, {:.4}]", c[0], c[1], c[2], c[3]);
        format!(
            "pub const {}: Theme = Theme {{\n    \
             name: {:?},\n    \
             slow_wind_color: {},\n    \
             fast_wind_color: {},\n    \
             bg_color1: {},\n    \
             bg_color2: {},\n    \
             wallpaper_color: rgb8({:#010X}),\n}};",
            self.name.to_uppercase().replace([' ', '-'], "_"),
            self.name,
            arr(self.slow_wind_color),
            arr(self.fast_wind_color),
            arr(self.bg_color1),
            arr(self.bg_color2),
            pack_rgba(self.bg_color1) | 0xFF,
        )
    }
}

fn color_row(ui: &mut egui::Ui, label: &str, color: &mut [f32; 4]) -> bool {
    ui.label(egui::RichText::new(label));
    crate::color_picker::color_picker_compact(ui, color)
}

fn pack_rgba(c: [f32; 4]) -> u32 {
    let b = |x: f32| (x.clamp(0.0, 1.0) * 255.0).round() as u32;
    (b(c[0]) << 24) | (b(c[1]) << 16) | (b(c[2]) << 8) | b(c[3])
}
