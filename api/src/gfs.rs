// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use anyhow::{Context, Result, anyhow, bail};
use chrono::{DateTime, Datelike, TimeZone, Timelike, Utc};
use ureq::Agent;

use crate::decode::{self, WindField};

/// Requested grib does not exist.
#[derive(Debug)]
pub struct NotFound;

impl std::fmt::Display for NotFound {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("not found")
    }
}

impl std::error::Error for NotFound {}

pub fn is_not_found(err: &anyhow::Error) -> bool {
    err.chain().any(|e| e.is::<NotFound>())
}

/// A GFS forecast cycle (analysis time), rounded down to the 6-hour boundary.
#[derive(Clone, Copy)]
pub struct GfsCycle(DateTime<Utc>);

impl GfsCycle {
    pub fn new(t: DateTime<Utc>) -> Self {
        GfsCycle(t)
    }

    /// `(year, month, day, cycle)` where `cycle` is the 6-hourly hour (0/6/12/18).
    fn fields(&self) -> (i32, u32, u32, u32) {
        let t = self.0;
        (t.year(), t.month(), t.day(), t.hour() / 6 * 6)
    }

    /// The previous 6-hour cycle.
    pub fn prev(&self) -> GfsCycle {
        let (y, m, d, c) = self.fields();
        let base = Utc
            .with_ymd_and_hms(y, m, d, c, 0, 0)
            .single()
            .expect("cycle fields not a valid utc time");
        GfsCycle(base - chrono::Duration::hours(6))
    }
}

impl std::fmt::Display for GfsCycle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let (y, m, d, c) = self.fields();
        write!(f, "{y:04}{m:02}{d:02}.{c:02}")
    }
}

/// Check and convert a 2-decimal-place lng/lat grid precision to an integer
/// (e.g. `0.25` → `25`).
pub fn prec2(prec: f64) -> Result<i64> {
    let prec2 = (prec * 100.0) as i64;
    if prec * 100.0 - prec2 as f64 != 0.0 {
        bail!("precision must be at most two decimal places");
    }
    if prec2 <= 0 || 360 * 100 % prec2 != 0 {
        bail!("precision must divide evenly");
    }
    Ok(prec2)
}

/// The path (relative to the GFS mirror root) of the atmospheric analysis GRIB
/// for the given cycle and precision.
pub fn gfs_path(cycle: &GfsCycle, prec: f64) -> Result<String> {
    let prec2 = prec2(prec)?;
    let (year, month, day, cyc) = cycle.fields();
    let (model, collection, variant, forecast) = ("gfs", "atmos", "pgrb2", "anl");
    Ok(format!(
        "{model}.{year:04}{month:02}{day:02}/{cyc:02}/{collection}/{model}.t{cyc:02}z.{variant}.{p0}p{p1:02}.{forecast}",
        p0 = prec2 / 100,
        p1 = prec2 % 100,
    ))
}

/// Fetch the wind field for the cycle's GRIB at `base`, returning the decoded
/// `(u, v)` field. Returns a [`NotFound`] error if the GRIB index is missing.
pub fn get_wind_grib(agent: &Agent, base: &str, prec: f64, level: &str) -> Result<WindField> {
    prec2(prec)?;

    let components = [("UGRD", level), ("VGRD", level)];
    let names = ["wind u-component", "wind v-component"];

    let idx = grib_index(agent, base, &components)
        .with_context(|| format!("read grib index for {base:?}"))?;

    let mut blob = Vec::new();
    for (c, &(code, lvl)) in components.iter().enumerate() {
        let range = idx[c].ok_or_else(|| {
            anyhow!(
                "read grib index for {base:?}: {} ({code} {lvl:?}) not found",
                names[c]
            )
        })?;
        let data = grib_data(agent, base, range)
            .with_context(|| format!("read grib {} ({code} {lvl:?}) from {base:?}", names[c]))?;
        blob.extend_from_slice(&data);
    }

    decode::decode_wind(&blob, prec as f32).with_context(|| format!("decode grib from {base:?}"))
}

/// A byte range `[start, end]` within the GRIB file (`end == None` means to the
/// end of the file).
type Range = (usize, Option<usize>);

/// Fetch and parse the `.idx` for `base`, returning the byte range of each
/// requested `(code, level)` component (or `None` if absent).
fn grib_index(
    agent: &Agent,
    base: &str,
    components: &[(&str, &str)],
) -> Result<Vec<Option<Range>>> {
    let resp = agent
        .get(format!("{base}.idx"))
        .call()
        .context("fetch index")?;
    match resp.status().as_u16() {
        200 => {}
        404 => return Err(NotFound.into()),
        s => bail!("response status {s}"),
    }
    let body = resp.into_body().read_to_string().context("read index")?;

    // `num:offset:date:CODE:LEVEL:forecast:...`
    //
    // A matched component's range runs from its offset to one byte before the
    // offset of the immediately following record (or to end-of-file if it is
    // the last).
    let mut idx: Vec<Option<Range>> = vec![None; components.len()];
    let mut cur: Option<usize> = None; // component matched by the previous line

    for line in body.lines() {
        if line.is_empty() {
            continue;
        }
        let f: Vec<&str> = line.split(':').collect();
        if f.len() != 7 {
            bail!("parse: unexpected number of fields in line {line:?}");
        }
        let off: usize = f[1]
            .parse()
            .with_context(|| format!("parse: invalid offset {:?}", f[1]))?;
        if let Some(c) = cur.take()
            && let Some(range) = idx[c].as_mut()
        {
            range.1 = Some(off.saturating_sub(1));
        }
        for (i, c) in components.iter().enumerate() {
            if c.0 == f[3] && c.1 == f[4] {
                idx[i] = Some((off, None));
                cur = Some(i);
            }
        }
    }
    Ok(idx)
}

fn grib_data(agent: &Agent, base: &str, range: Range) -> Result<Vec<u8>> {
    let value = match range {
        (start, Some(end)) => format!("bytes={start}-{end}"),
        (start, None) => format!("bytes={start}-"),
    };
    let resp = agent
        .get(base)
        .header("Range", &value)
        .call()
        .context("get grib")?;
    match resp.status().as_u16() {
        206 => {}
        404 => return Err(NotFound.into()),
        200 => bail!("get grib: server did not send the requested range"),
        s => bail!("get grib: response status {s}"),
    }
    let buf = resp.into_body().read_to_vec().context("get grib")?;
    if !buf.starts_with(b"GRIB") {
        bail!("get grib: response does not start with grib magic");
    }
    Ok(buf)
}
