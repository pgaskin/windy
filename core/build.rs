// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::path::Path;

use wgsl_to_wgpu::{ValidationOptions, WriteOptions, create_shader_modules, demangle_identity};

fn main() {
    let out_dir = std::env::var("OUT_DIR").expect("OUT_DIR");

    println!("cargo:rerun-if-changed=src/shaders/common.wgsl");
    let common = std::fs::read_to_string("src/shaders/common.wgsl").expect("read common.wgsl");

    let options = WriteOptions {
        derive_bytemuck_host_shareable: true, // derive Pod/Zeroable
        validate: Some(ValidationOptions::default()),
        ..Default::default()
    };

    for name in &["simulate", "trail", "fade", "composite"] {
        println!("cargo:rerun-if-changed=src/shaders/{name}.wgsl");
        let body =
            std::fs::read_to_string(format!("src/shaders/{name}.wgsl")).expect("read shader");
        let source = format!("{common}\n{body}");
        let generated = create_shader_modules(&source, options, demangle_identity)
            .unwrap_or_else(|e| panic!("{name}.wgsl: {e}"));
        std::fs::write(Path::new(&out_dir).join(format!("{name}.rs")), generated)
            .expect("write generated bindings");
    }
}
