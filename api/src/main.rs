// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

mod config;
mod decode;
mod gfs;
mod server;
mod windy;

use std::net::SocketAddr;
use std::sync::Arc;

use anyhow::{Context, Result};
use clap::Parser;
use tokio::sync::watch;

use crate::config::{Args, Config};
use crate::windy::Windy;

#[tokio::main]
async fn main() {
    let args = Args::parse();

    env_logger::Builder::new()
        .parse_filters(&args.log_level)
        .format_timestamp_secs()
        .init();

    if let Err(e) = run(args).await {
        log::error!("{e:#}");
        std::process::exit(1);
    }
}

async fn run(args: config::Args) -> Result<()> {
    let config = Config::resolve(&args)?;
    let addr = parse_addr(&args.addr)?;
    let windy = Arc::new(Windy::new(config));

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let updater = tokio::spawn(windy.clone().run(shutdown_rx.clone()));
    if args.once {
        return run_once(&windy, shutdown_tx, updater).await;
    }

    {
        let shutdown_tx = shutdown_tx.clone();
        tokio::spawn(async move {
            if tokio::signal::ctrl_c().await.is_ok() {
                log::info!("received interrupt, shutting down");
                let _ = shutdown_tx.send(true);
            }
        });
    }

    let routes = server::routes(windy.clone());
    let mut server_shutdown = shutdown_rx.clone();
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .with_context(|| format!("failed to listen on {addr}"))?;
    let bound = listener.local_addr().unwrap_or(addr);
    log::info!("serving http on {bound}");

    warp::serve(routes)
        .incoming(listener)
        .graceful(async move {
            let _ = server_shutdown.wait_for(|s| *s).await;
        })
        .run()
        .await;

    log::info!("waiting for update worker to shut down");
    let _ = shutdown_tx.send(true);
    let _ = updater.await;
    log::info!("stopped");
    Ok(())
}

async fn run_once(
    windy: &Arc<Windy>,
    shutdown_tx: watch::Sender<bool>,
    updater: tokio::task::JoinHandle<()>,
) -> Result<()> {
    log::info!("waiting for wind field");
    let (data, err) = windy.data(None).await;
    let data = match data {
        Some(data) => data,
        None => {
            let _ = shutdown_tx.send(true);
            let _ = updater.await;
            anyhow::bail!(
                "failed to get wind field images: {}",
                err.as_deref().unwrap_or("no data")
            );
        }
    };

    std::fs::write("wind_field.jpg", &data.jpg.data).context("save wind_field.jpg")?;
    std::fs::write("wind_field.png", &data.png.data).context("save wind_field.png")?;
    log::info!("saved images");

    let _ = shutdown_tx.send(true);
    let _ = updater.await;
    Ok(())
}

fn parse_addr(addr: &str) -> Result<SocketAddr> {
    // kinda like Go
    let normalized = if let Some(port) = addr.strip_prefix(':') {
        format!("0.0.0.0:{port}") // TODO: dual-stack?
    } else {
        addr.to_string()
    };
    normalized
        .parse()
        .with_context(|| format!("invalid listen address {addr:?}"))
}
