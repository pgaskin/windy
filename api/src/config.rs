// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::time::Duration;

use anyhow::{Context, Result, bail};
use chrono::{DateTime, TimeZone, Utc};
use clap::Parser;

/// API serving wind data for the Windy live wallpaper.
#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Args {
    /// Listen address
    #[arg(long, env = "WINDY_ADDR", default_value = ":8080")]
    pub addr: String,

    /// Log level: debug, info, warn, or error
    #[arg(long, env = "WINDY_LOG_LEVEL", default_value = "info")]
    pub log_level: String,

    /// Override the response timeout in seconds, after which old data is served
    /// if available (negative for no limit)
    #[arg(long, env = "WINDY_WIND_RESPONSE_TIMEOUT")]
    pub wind_response_timeout: Option<f64>,

    /// Override the default GFS forecast data mirror
    #[arg(long, env = "WINDY_WIND_GFS")]
    pub wind_gfs: Option<String>,

    /// Override the GFS lng/lat grid precision to use
    #[arg(long, env = "WINDY_WIND_GFS_PRECISION")]
    pub wind_gfs_precision: Option<f64>,

    /// Override the GFS wind data elevation to use
    #[arg(long, env = "WINDY_WIND_GFS_LEVEL")]
    pub wind_gfs_level: Option<String>,

    /// Override the GFS forecast time (unix time)
    #[arg(long, env = "WINDY_WIND_GFS_TIME")]
    pub wind_gfs_time: Option<i64>,

    /// Override the default timeout in seconds for an update
    #[arg(long, env = "WINDY_WIND_TIMEOUT")]
    pub wind_timeout: Option<f64>,

    /// Override the default timeout in seconds for fetching a single cycle of
    /// wind data (negative for no limit)
    #[arg(long, env = "WINDY_WIND_FETCH_TIMEOUT")]
    pub wind_fetch_timeout: Option<f64>,

    /// Override the default maximum number of previous wind data cycles to try
    /// if the current one isn't available (negative for no limit)
    #[arg(long, env = "WINDY_WIND_MAX_PREV_CYCLES")]
    pub wind_max_prev_cycles: Option<i64>,

    /// Override the default maximum number of times to attempt to fetch a single
    /// wind data cycle if it isn't non-existent (negative for no limit)
    #[arg(long, env = "WINDY_WIND_MAX_RETRY")]
    pub wind_max_retry: Option<i64>,

    /// Dump the wind field images to the current directory instead of starting
    /// the server
    #[arg(long, env = "WINDY_ONCE")]
    pub once: bool,
}

pub struct Config {
    /// GFS mirror root, guaranteed to end with `/`.
    pub gfs_base: String,
    pub precision: f64,
    pub level: String,
    /// Overall per-update timeout (`None` = no limit).
    pub timeout: Option<Duration>,
    /// Per-fetch timeout (`None` = no limit).
    pub fetch_timeout: Option<Duration>,
    /// HTTP handler response timeout (`None` = no limit).
    pub response_timeout: Option<Duration>,
    /// Max previous cycles to try (`< 0` = unlimited).
    pub max_prev_cycles: i64,
    /// Max retries per cycle (`< 0` = unlimited).
    pub max_retry: i64,
    /// Fixed forecast time override (`None` = use current time each update).
    pub gfs_time: Option<DateTime<Utc>>,
}

impl Config {
    pub fn resolve(args: &Args) -> Result<Config> {
        let mut gfs_base = match &args.wind_gfs {
            Some(u) => {
                if !(u.starts_with("http://") || u.starts_with("https://")) {
                    bail!("invalid gfs url {u:?}: must be an absolute http(s) url");
                }
                u.clone()
            }
            None => "https://noaa-gfs-bdp-pds.s3.amazonaws.com/".to_string(),
        };
        if !gfs_base.ends_with('/') {
            gfs_base.push('/');
        }

        let precision = args.wind_gfs_precision.unwrap_or(0.0);
        let precision = if precision == 0.0 { 0.25 } else { precision };
        if precision <= 0.0 {
            bail!("gfs precision must be positive");
        }

        let level = args
            .wind_gfs_level
            .clone()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "850 mb".to_string());

        let gfs_time = match args.wind_gfs_time {
            Some(t) if t != 0 => Some(
                Utc.timestamp_opt(t, 0)
                    .single()
                    .context("invalid gfs forecast time")?,
            ),
            _ => None,
        };

        Ok(Config {
            gfs_base,
            precision,
            level,
            timeout: dur_opt(args.wind_timeout, 100.0),
            fetch_timeout: dur_opt(args.wind_fetch_timeout, 20.0),
            response_timeout: dur_opt(args.wind_response_timeout, 2.0),
            max_prev_cycles: count_opt(args.wind_max_prev_cycles, 3 * 4), // 3 days
            max_retry: count_opt(args.wind_max_retry, 3),
            gfs_time,
        })
    }
}

fn dur_opt(val: Option<f64>, default_secs: f64) -> Option<Duration> {
    match val {
        None | Some(0.0) => Some(Duration::from_secs_f64(default_secs)),
        Some(v) if v < 0.0 => None,
        Some(v) => Some(Duration::from_secs_f64(v)),
    }
}

fn count_opt(val: Option<i64>, default: i64) -> i64 {
    match val {
        None | Some(0) => default,
        Some(v) if v < 0 => -1,
        Some(v) => v,
    }
}
