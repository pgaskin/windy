use std::{
    fs::File,
    io::{BufReader, Write},
};

use grib::{self, Grib2, Grib2Read, Grib2SubmessageDecoder, GribError, SubMessage};
use image::{
    codecs::{jpeg::JpegEncoder, png::PngEncoder},
    imageops::{self, FilterType},
    ColorType, DynamicImage, EncodableLayout, ImageBuffer, ImageEncoder, Rgb32FImage,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let fname = "grib";
    let r = File::open(fname)?;
    let r = BufReader::new(r);
    let r = grib::from_reader(r)?;

    let (uv, (w, h)) = extract_grib_wind(r, 0.25)?;
    let img_f = create_wind_texture(w, h, uv);
    let img_r = imageops::resize(&img_f, (w / 4) as u32, (h / 4) as u32, FilterType::Triangle); // bilinear
    let img_b = imageops::blur(&img_r, 1.0); // kernel is 5x5 (support = 2*sigma = 2*1 = 2)

    let img_f = DynamicImage::from(img_f).into_rgb8();
    let img_b = DynamicImage::from(img_b).into_rgb8();

    let mut wind_field_jpg = vec![];
    JpegEncoder::new_with_quality(&mut wind_field_jpg, 100).encode_image(&img_f)?;

    let mut wind_field_png = vec![];
    PngEncoder::new(&mut wind_field_png).write_image(
        img_f.as_bytes(),
        img_f.width(),
        img_f.height(),
        ColorType::Rgb8,
    )?;

    let mut wind_cache_png_1 = vec![];
    PngEncoder::new(&mut wind_cache_png_1).write_image(
        img_b.as_bytes(),
        img_b.width(),
        img_b.height(),
        ColorType::Rgb8,
    )?;

    File::create("wind_field.jpg")?.write_all(&wind_field_jpg)?;
    File::create("wind_field.png")?.write_all(&wind_field_png)?;
    File::create("wind_cache.1.png")?.write_all(&wind_cache_png_1)?;

    Ok(())
}

/// Convert the wind vector field into a texture.
fn create_wind_texture(width: usize, height: usize, uv: Vec<(f32, f32)>) -> Rgb32FImage {
    let pix = uv
        .iter()
        .flat_map(|(u, v)| {
            let s = f32::sqrt(u * u + v * v);
            let u = u / s;
            let v = v / s;
            return [
                f32::clamp(u + 1.0, 0.0, 2.0) / 2.0,
                f32::clamp(v + 1.0, 0.0, 2.0) / 2.0,
                f32::clamp(s, 0.0, 30.0) / 30.0,
            ];
        })
        .collect();
    ImageBuffer::from_vec(width as u32, height as u32, pix)
        .unwrap()
        .into()
}

/// Extract the wind vector field at the specified lat/lon precision from the
/// grib file, returning the ugrd/vgrd values and width/height.
fn extract_grib_wind<T: Grib2Read>(
    grib2: Grib2<T>,
    precision: f32,
) -> Result<(Vec<(f32, f32)>, (usize, usize)), Box<dyn std::error::Error>> {
    let mut ugrd = None;
    let mut vgrd = None;
    for (_, msg) in grib2.iter() {
        match msg
            .prod_def()
            .parameter_category()
            .zip(msg.prod_def().parameter_number())
        {
            Some((2, 2)) => &mut ugrd,
            Some((2, 3)) => &mut vgrd,
            _ => continue,
        }
        .replace(extract_grib_values(msg, precision)?);
    }
    let (ugrd, ugrd_dim) = ugrd.ok_or("missing UGRD")?;
    let (vgrd, vgrd_dim) = vgrd.ok_or("missing VGRD")?;
    if ugrd_dim != vgrd_dim {
        Err("mismatched grid size for UGRD/VGRD")?;
    }
    Ok((std::iter::zip(ugrd, vgrd).collect(), ugrd_dim))
}

/// Extract row-major values at the specified lat/lon precision from the grib
/// message, returning the values and width/height. The lat/lon grid is
/// [-180,180) and [90,-90].
fn extract_grib_values<R: Grib2Read>(
    submessage: SubMessage<R>,
    precision: f32,
) -> Result<(Vec<f32>, (usize, usize)), GribError> {
    let lat_dim = (180.0 / precision + 1.0) as usize;
    let lon_dim = (360.0 / precision) as usize;

    let latlon = submessage.latlons()?;
    let values = Grib2SubmessageDecoder::from(submessage)?;

    let mut res = vec![0 as f32; lat_dim * lon_dim];
    for ((lat, lon), val) in latlon.zip(values.dispatch()?) {
        let lon = (lon + 180.0) % 360.0 - 180.0; // [0,360) -> [-180,180)
        let lat_idx = ((-lat + 90.0) / precision).round() as usize;
        let lon_idx = ((lon + 180.0) / precision).round() as usize;
        res[lat_idx * lon_dim + lon_idx] = val;
    }
    Ok((res, (lon_dim, lat_dim)))
}
