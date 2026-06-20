// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::convert::Infallible;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use warp::http::{Method, Response, StatusCode, header};
use warp::path::FullPath;
use warp::reply::Response as Reply;
use warp::{Filter, Rejection};

use crate::windy::{WindData, Windy};

const REDIRECT_URL: &str = "https://github.com/pgaskin/windy";

pub fn routes(windy: Arc<Windy>) -> impl Filter<Extract = (Reply,), Error = Rejection> + Clone {
    warp::any()
        .and(warp::method())
        .and(warp::path::full())
        .and(raw_query())
        .and(warp::header::optional::<String>("if-none-match"))
        .and(warp::header::optional::<String>("if-modified-since"))
        .and(warp::any().map(move || windy.clone()))
        .and_then(handle)
}

fn raw_query() -> impl Filter<Extract = (String,), Error = Infallible> + Clone {
    warp::query::raw().or(warp::any().map(String::new)).unify()
}

async fn handle(
    method: Method,
    path: FullPath,
    query: String,
    if_none_match: Option<String>,
    if_modified_since: Option<String>,
    windy: Arc<Windy>,
) -> Result<Reply, Rejection> {
    let path = path.as_str();
    let rel = path.strip_prefix('/').unwrap_or(path);

    if !rel.contains('/') && (rel.starts_with("wind_field.") || rel.starts_with("wind_cache.")) {
        if method == Method::GET || method == Method::HEAD {
            return Ok(serve_image(
                &method,
                rel,
                &query,
                if_none_match,
                if_modified_since,
                &windy,
            )
            .await);
        }
        return Ok(text(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed")
            .header(header::ALLOW, "HEAD, GET")
            .body("Method Not Allowed".into())
            .unwrap());
    }

    if path == "/" {
        return Ok(Response::builder()
            .status(StatusCode::TEMPORARY_REDIRECT)
            .header(header::LOCATION, REDIRECT_URL)
            .body(Default::default())
            .unwrap());
    }

    Ok(text(StatusCode::NOT_FOUND, "Not Found")
        .body("Not Found".into())
        .unwrap())
}

async fn serve_image(
    method: &Method,
    name: &str,
    query: &str,
    if_none_match: Option<String>,
    if_modified_since: Option<String>,
    windy: &Windy,
) -> Reply {
    let (data, err) = windy.data(windy.response_timeout()).await;

    let Some(data) = data else {
        let (status, msg) = match &err {
            None => (
                StatusCode::SERVICE_UNAVAILABLE,
                "Initial data update not complete yet.".to_string(),
            ),
            Some(e) => (
                StatusCode::SERVICE_UNAVAILABLE,
                format!("No data available (last error: {e})."),
            ),
        };
        let mut b = text(status, &msg);
        if let Some(e) = &err {
            b = b.header("x-gfs-refresh-error", header_value(e));
        }
        return b.body(msg.into()).unwrap();
    };

    let (content_type, encoded) = match name {
        "wind_field.jpg" => ("image/jpeg", &data.jpg),
        "wind_field.png" => ("image/png", &data.png),
        "wind_cache.png" => match select_filter(query, data.filtered.len()) {
            Ok(i) => ("image/png", &data.filtered[i]),
            Err((status, msg)) => return gfs_text(status, &msg, &data, &err),
        },
        other => {
            return gfs_text(
                StatusCode::NOT_FOUND,
                &format!("No image available for {other}."),
                &data,
                &err,
            );
        }
    };

    let not_modified = if_none_match
        .as_deref()
        .map(|inm| inm == "*" || etag_matches(inm, &encoded.etag))
        .unwrap_or(false)
        || (if_none_match.is_none()
            && if_modified_since
                .as_deref()
                .map(|ims| not_modified_since(ims, data.updated))
                .unwrap_or(false));

    let mut b = Response::builder()
        .header(header::ETAG, header_value(&encoded.etag))
        .header(header::LAST_MODIFIED, http_date(data.updated))
        .header("x-gfs-source", header_value(&data.source))
        .header("x-gfs-cycle", header_value(&data.cycle.to_string()));
    if let Some(e) = &err {
        b = b.header("x-gfs-refresh-error", header_value(e));
    }

    if not_modified {
        return b
            .status(StatusCode::NOT_MODIFIED)
            .body(Default::default())
            .unwrap();
    }

    b = b
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type);
    if method == Method::HEAD {
        b.header(header::CONTENT_LENGTH, encoded.data.len())
            .body(Default::default())
            .unwrap()
    } else {
        b.body(encoded.data.clone().into()).unwrap()
    }
}

/// Parse the `?filter=N` query, returning the index into the filtered textures,
/// or an HTTP error to return.
fn select_filter(query: &str, count: usize) -> Result<usize, (StatusCode, String)> {
    let values: Vec<&str> = query
        .split('&')
        .filter_map(|kv| {
            let (k, v) = kv.split_once('=').unwrap_or((kv, ""));
            (k == "filter").then_some(v)
        })
        .collect();
    if values.len() != 1 {
        return Err((
            StatusCode::BAD_REQUEST,
            "Exactly one filter type (?filter=) is required.".to_string(),
        ));
    }
    let v: u64 = values[0].parse().unwrap_or(0);
    if v == 0 {
        return Err((StatusCode::BAD_REQUEST, "Invalid filter type.".to_string()));
    }
    if v as usize > count {
        return Err((
            StatusCode::BAD_REQUEST,
            "Unsupported filter type.".to_string(),
        ));
    }
    Ok(v as usize - 1)
}

fn etag_matches(inm: &str, etag: &str) -> bool {
    inm.split(',')
        .map(str::trim)
        .any(|e| e == etag || e.strip_prefix("W/") == Some(etag))
}

fn not_modified_since(ims: &str, updated: DateTime<Utc>) -> bool {
    match DateTime::parse_from_rfc2822(ims) {
        Ok(t) => updated.timestamp() <= t.timestamp(),
        Err(_) => false,
    }
}

fn http_date(t: DateTime<Utc>) -> String {
    t.format("%a, %d %b %Y %H:%M:%S GMT").to_string() // RFC 1123
}

fn text(status: StatusCode, _msg: &str) -> warp::http::response::Builder {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "text/plain; charset=utf-8")
}

fn gfs_text(status: StatusCode, msg: &str, data: &WindData, err: &Option<String>) -> Reply {
    let mut b = text(status, msg)
        .header("x-gfs-source", header_value(&data.source))
        .header("x-gfs-cycle", header_value(&data.cycle.to_string()))
        .header(header::LAST_MODIFIED, http_date(data.updated));
    if let Some(e) = err {
        b = b.header("x-gfs-refresh-error", header_value(e));
    }
    b.body(msg.to_string().into()).unwrap()
}

/// Sanitise a header value (header values must not contain control characters,
/// but error strings may, so replace them with spaces).
fn header_value(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect()
}
