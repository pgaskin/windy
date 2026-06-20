// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::io::Cursor;

use anyhow::{Result, anyhow, bail};
use grib::{Grib2, Grib2Read, Grib2SubmessageDecoder, LatLons, SubMessage};
use image::{ImageBuffer, Rgb32FImage, imageops::FilterType};

/// Decoded equirectangular wind field (longitude `[-180,180)`, ` latitude
/// `[90,-90]`).
pub struct WindField {
    pub uv: Vec<(f32, f32)>,
    pub width: usize,
    pub height: usize,
}

pub fn decode_wind(data: &[u8], precision: f32) -> Result<WindField> {
    let grib2 = grib::from_reader(Cursor::new(data))?;
    extract_grib_wind(grib2, precision)
}

/// Convert the wind field into a normalised RGB texture (direction in R/G,
/// speed in B).
pub fn create_wind_texture(width: usize, height: usize, uv: &[(f32, f32)]) -> Result<Rgb32FImage> {
    let pix = uv
        .iter()
        .flat_map(|&(u, v)| {
            let s = f32::sqrt(u * u + v * v);
            let u = u / s;
            let v = v / s;
            [
                f32::clamp(u + 1.0, 0.0, 2.0) / 2.0,
                f32::clamp(v + 1.0, 0.0, 2.0) / 2.0,
                f32::clamp(s, 0.0, 30.0) / 30.0,
            ]
        })
        .collect();
    ImageBuffer::from_vec(width as u32, height as u32, pix)
        .ok_or_else(|| anyhow!("wind texture buffer size does not match {width}x{height}"))
}

/// Downscale the wind texture by 1/4 (bilinear) and blur it.
pub fn filter_wind_texture(img: &Rgb32FImage) -> Rgb32FImage {
    let resized = image::imageops::resize(
        img,
        img.width() / 4,
        img.height() / 4,
        FilterType::Triangle, // bilinear
    );
    // sigma 1.0 = 5x5 kernel (support = 2*sigma)
    image::imageops::blur(&resized, 1.0)
}

/// Extract the wind vector field at the specified lng/lat precision from the
/// grib, returning the ugrd/vgrd values and width/height.
fn extract_grib_wind<T: Grib2Read>(grib2: Grib2<T>, precision: f32) -> Result<WindField> {
    let mut ugrd = None;
    let mut vgrd = None;
    for (_, msg) in grib2.iter() {
        let slot = match msg
            .prod_def()
            .parameter_category()
            .zip(msg.prod_def().parameter_number())
        {
            Some((2, 2)) => &mut ugrd,
            Some((2, 3)) => &mut vgrd,
            _ => continue,
        };
        slot.replace(extract_grib_values(msg, precision)?);
    }
    let (ugrd, ugrd_dim) = ugrd.ok_or_else(|| anyhow!("missing UGRD"))?;
    let (vgrd, vgrd_dim) = vgrd.ok_or_else(|| anyhow!("missing VGRD"))?;
    if ugrd_dim != vgrd_dim {
        bail!("mismatched grid size for UGRD ({ugrd_dim:?}) and VGRD ({vgrd_dim:?})");
    }
    let (width, height) = ugrd_dim;
    Ok(WindField {
        uv: std::iter::zip(ugrd, vgrd).collect(),
        width,
        height,
    })
}

/// Extract row-major values at the specified lng/lat precision from the grib
/// message, returning the values and width/height. The lat/lon grid is
/// `[-180,180)` and `[90,-90]`.
fn extract_grib_values<R: Grib2Read>(
    submessage: SubMessage<R>,
    precision: f32,
) -> Result<(Vec<f32>, (usize, usize))> {
    let lat_dim = (180.0 / precision + 1.0) as usize;
    let lon_dim = (360.0 / precision) as usize;

    let latlon = submessage.latlons()?;
    let values = Grib2SubmessageDecoder::from(submessage)?;

    let mut res = vec![0_f32; lat_dim * lon_dim];
    for ((lat, lon), val) in latlon.zip(values.dispatch()?) {
        let lon = (lon + 180.0) % 360.0 - 180.0; // [0,360) -> [-180,180)
        let lat_idx = ((-lat + 90.0) / precision).round() as usize;
        let lon_idx = ((lon + 180.0) / precision).round() as usize;
        let idx = lat_idx * lon_dim + lon_idx;
        let slot = res.get_mut(idx).ok_or_else(|| {
            anyhow!("grib point ({lat}, {lon}) maps outside the {lon_dim}x{lat_dim} grid")
        })?;
        *slot = val;
    }
    Ok((res, (lon_dim, lat_dim)))
}
