// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::fmt::Write as _;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use chrono::{DateTime, Utc};
use image::{DynamicImage, RgbImage};
use jpeg_encoder::{ColorType as JpegColorType, Encoder as JpegEncoder, SamplingFactor};
use sha1::{Digest, Sha1};
use tokio::sync::watch;
use ureq::Agent;

use crate::config::Config;
use crate::decode::{create_wind_texture, filter_wind_texture};
use crate::gfs::{self, GfsCycle};

pub struct Encoded {
    pub data: Vec<u8>,
    pub etag: String,
}

impl Encoded {
    fn new(data: Vec<u8>) -> Self {
        let mut hasher = Sha1::new();
        hasher.update(&data);
        let digest = hasher.finalize();
        let mut etag = String::with_capacity(digest.len() * 2 + 2);
        etag.push('"');
        for b in digest {
            let _ = write!(etag, "{b:02x}");
        }
        etag.push('"');
        Self { data, etag }
    }
}

pub struct WindData {
    pub jpg: Encoded,
    pub png: Encoded,
    pub filtered: Vec<Encoded>, // pre-filtered for ?filter=N
    pub updated: DateTime<Utc>,
    pub cycle: GfsCycle,
    pub source: String,
}

#[derive(Clone, Default)]
struct State {
    data: Option<Arc<WindData>>,
    err: Option<String>, // if failed
    started: u64,        // started > completed if update in-flight
    completed: u64,
}

pub struct Windy {
    config: Config,
    agent: Agent,
    tx: watch::Sender<State>,
}

impl Windy {
    pub fn new(config: Config) -> Self {
        let agent: Agent = Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(config.fetch_timeout)
            .build()
            .into();
        let (tx, _) = watch::channel(State::default());
        Windy { config, agent, tx }
    }

    pub fn response_timeout(&self) -> Option<Duration> {
        self.config.response_timeout
    }

    pub async fn data(&self, timeout: Option<Duration>) -> (Option<Arc<WindData>>, Option<String>) {
        let mut rx = self.tx.subscribe();
        // wait for in-flight update
        let want = rx.borrow().started.max(1);
        let wait = async {
            let _ = rx.wait_for(|s| s.completed >= want).await;
        };
        match timeout {
            Some(t) => {
                let _ = tokio::time::timeout(t, wait).await;
            }
            None => wait.await,
        }
        let s = self.tx.borrow();
        (s.data.clone(), s.err.clone())
    }

    pub async fn run(self: Arc<Self>, mut shutdown: watch::Receiver<bool>) {
        let cfg = &self.config;
        log::info!(
            "starting update worker (gfs={} precision={} level={:?} timeout={:?} fetch_timeout={:?} max_prev_cycles={} max_retry={})",
            cfg.gfs_base,
            cfg.precision,
            cfg.level,
            cfg.timeout,
            cfg.fetch_timeout,
            cfg.max_prev_cycles,
            cfg.max_retry,
        );

        // now, then every interval
        let mut next = tokio::time::Instant::now();
        loop {
            tokio::select! {
                _ = shutdown.wait_for(|s| *s) => {
                    log::info!("update worker stopped");
                    return;
                }
                _ = tokio::time::sleep_until(next) => {}
            }

            log::info!("updating wind field");
            self.tx.send_modify(|s| s.started += 1);
            let result = match cfg.timeout {
                Some(t) => match tokio::time::timeout(t, self.do_update()).await {
                    Ok(r) => r,
                    Err(_) => Err(anyhow::anyhow!("update timed out after {t:?}")),
                },
                None => self.do_update().await,
            };
            self.tx.send_modify(|s| {
                s.completed = s.started;
                match result {
                    Ok(data) => {
                        s.data = Some(Arc::new(data));
                        s.err = None;
                    }
                    Err(e) => {
                        log::error!("failed to update wind field: {e:#}");
                        s.err = Some(format!("{e:#}"));
                    }
                }
            });

            let interval = Duration::from_secs(3600);
            next = tokio::time::Instant::now() + interval;
            log::info!("scheduled next update in {interval:?}");
        }
    }

    async fn do_update(&self) -> Result<WindData> {
        let cfg = &self.config;
        let updated = cfg.gfs_time.unwrap_or_else(Utc::now);
        let mut cycle = GfsCycle::new(updated);
        let mut prev: i64 = 0;

        let (field, source, cycle) = 'prev: loop {
            let mut retry: i64 = 0;
            'retry: loop {
                let source =
                    gfs::gfs_path(&cycle, cfg.precision).context("failed to generate gfs path")?;
                let url = format!("{}{}", cfg.gfs_base, source);
                log::info!("attempting to fetch wind data (prev={prev} retry={retry} url={url})");

                let agent = self.agent.clone();
                let level = cfg.level.clone();
                let prec = cfg.precision;
                let base = url.clone();
                let res = tokio::task::spawn_blocking(move || {
                    gfs::get_wind_grib(&agent, &base, prec, &level)
                })
                .await
                .context("fetch task panicked")?;

                match res {
                    Ok(field) => break 'prev (field, source, cycle),
                    Err(e) if gfs::is_not_found(&e) => {
                        log::warn!("no gfs data found (cycle={cycle} prev={prev}): {e:#}");
                        if cfg.max_prev_cycles < 0 || prev < cfg.max_prev_cycles {
                            cycle = cycle.prev();
                            prev += 1;
                            continue 'prev;
                        }
                        bail!("no gfs data found after {cycle} ({prev} update cycles ago)");
                    }
                    Err(e) => {
                        log::warn!("failed to get gfs data (cycle={cycle} attempt={retry}): {e:#}");
                        if cfg.max_retry < 0 || retry < cfg.max_retry {
                            retry += 1;
                            continue 'retry;
                        }
                        return Err(e.context(format!("failed to get gfs data ({retry} retries)")));
                    }
                }
            }
        };

        log::info!(
            "got wind data, generating image (cycle={cycle} source={source} lat={} lng={})",
            field.height,
            field.width,
        );

        tokio::task::spawn_blocking(move || build_wind_data(field, updated, cycle, source))
            .await
            .context("image task panicked")?
    }
}

fn build_wind_data(
    field: crate::decode::WindField,
    updated: DateTime<Utc>,
    cycle: GfsCycle,
    source: String,
) -> Result<WindData> {
    let texture = create_wind_texture(field.width, field.height, &field.uv)?;
    let filtered = filter_wind_texture(&texture);

    let full: RgbImage = DynamicImage::from(texture).into_rgb8();
    let filtered: RgbImage = DynamicImage::from(filtered).into_rgb8();

    let full_meta = ImageMeta::new(&source, 0, updated);
    let filtered_meta = ImageMeta::new(&source, 1, updated);

    let jpg = encode_jpeg(&full, &full_meta).context("encode jpeg")?;
    let png = encode_png(&full, &full_meta).context("encode png")?;
    let filtered_png = encode_png(&filtered, &filtered_meta).context("encode filtered png")?;

    Ok(WindData {
        jpg: Encoded::new(jpg),
        png: Encoded::new(png),
        filtered: vec![Encoded::new(filtered_png)],
        updated,
        cycle,
        source,
    })
}

struct ImageMeta {
    /// The version of the API that generated the image.
    version: &'static str,
    /// When the wind field was generated.
    generated: DateTime<Utc>,
    /// The GFS GRIB source path the wind field was generated from.
    grib: String,
    /// The 1-based filter index this texture corresponds to (matching the
    /// `wind_cache.png?filter=N` query), or 0 for the unfiltered `wind_field`.
    filter: usize,
}

impl ImageMeta {
    fn new(source: &str, filter: usize, generated: DateTime<Utc>) -> Self {
        Self {
            version: option_env!("CARGO_PKG_VERSION").unwrap_or("unknown"),
            generated,
            grib: source.to_string(),
            filter,
        }
    }

    fn fields(&self) -> [(&'static str, String); 4] {
        [
            ("windy:version", self.version.to_string()),
            (
                "windy:generated",
                self.generated.format("%Y-%m-%dT%H:%M:%SZ").to_string(),
            ),
            ("windy:grib", self.grib.clone()),
            ("windy:filter", self.filter.to_string()),
        ]
    }
}

fn encode_png(img: &RgbImage, meta: &ImageMeta) -> Result<Vec<u8>> {
    let mut buf = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut buf, img.width(), img.height());
        encoder.set_color(png::ColorType::Rgb);
        encoder.set_depth(png::BitDepth::Eight);
        for (key, value) in meta.fields() {
            encoder.add_text_chunk(key.to_string(), value)?;
        }
        let mut writer = encoder.write_header()?;
        writer.write_image_data(img.as_raw())?;
    }
    Ok(buf)
}

fn encode_jpeg(img: &RgbImage, meta: &ImageMeta) -> Result<Vec<u8>> {
    let mut buf = Vec::new();
    let mut encoder = JpegEncoder::new(&mut buf, 100);
    // 4:2:0 chroma subsampling, matching Go's image/jpeg and the original Google
    // texture (image 0.25's built-in encoder forces 4:4:4)
    encoder.set_sampling_factor(SamplingFactor::R_4_2_0);
    encoder.add_exif_metadata(&build_exif(&meta.fields()))?;
    encoder.encode(
        img.as_raw(),
        img.width() as u16,
        img.height() as u16,
        JpegColorType::Rgb,
    )?;
    Ok(buf)
}

/// Builds a minimal little-endian EXIF/TIFF blob storing the `(key, value)`
/// pairs as EXIF ASCII tags.
///
/// EXIF has no arbitrary key/value support, so each field is written to a
/// private ASCII tag in the 0xFExx range and also combined into the standard
/// ImageDescription (0x010E) tag for existing tools.
fn build_exif(fields: &[(&str, String)]) -> Vec<u8> {
    // must be emitted in ascending tag order according to the tiff spec
    let description = fields
        .iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect::<Vec<_>>()
        .join("; ");
    let mut entries: Vec<(u16, String)> = vec![(0x010E, description)];
    for (i, (_, v)) in fields.iter().enumerate() {
        entries.push((0xFE00 + i as u16, v.clone()));
    }
    entries.sort_by_key(|(tag, _)| *tag);

    let mut hdr = Vec::new();
    hdr.extend_from_slice(b"II"); // little-endian
    hdr.extend_from_slice(&42u16.to_le_bytes()); // tiff magic
    hdr.extend_from_slice(&8u32.to_le_bytes()); // offset to ifd0

    // values longer than 4 bytes live in a data area after the ifd
    let ifd_size = 2 + 12 * entries.len() + 4;
    let data_start = 8 + ifd_size;
    let mut data = Vec::new();

    hdr.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    for (tag, value) in &entries {
        let mut bytes = value.clone().into_bytes();
        bytes.push(0); // ascii values are null-terminated
        hdr.extend_from_slice(&tag.to_le_bytes());
        hdr.extend_from_slice(&2u16.to_le_bytes()); // type = ascii
        hdr.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
        if bytes.len() <= 4 {
            let mut inline = [0u8; 4];
            inline[..bytes.len()].copy_from_slice(&bytes);
            hdr.extend_from_slice(&inline);
        } else {
            hdr.extend_from_slice(&((data_start + data.len()) as u32).to_le_bytes());
            data.extend_from_slice(&bytes);
            if data.len() % 2 != 0 {
                data.push(0); // values are word-aligned
            }
        }
    }
    hdr.extend_from_slice(&0u32.to_le_bytes()); // no next ifd
    hdr.extend_from_slice(&data);
    hdr
}
