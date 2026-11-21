//! Client for the suite's loopback embedding service. fennec-core's
//! `EngineClient.embed` posts `{"input": [...]}` to `/v1/embeddings` on the NPU
//! server at http://127.0.0.1:8182 and reads `{"data": [{"embedding": [...]}]}`.
//! This is the same contract over a minimal HTTP/1.1 transport: the service is
//! always loopback plain HTTP, so a TLS stack would be dead weight. If it is
//! unreachable the caller gets an error - never a fabricated vector.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use serde_json::{json, Value};

use crate::discovery::Environ;

pub const DEFAULT_EMBED_URL: &str = "http://127.0.0.1:8182";
const MAX_HEADER_BYTES: usize = 64 * 1024;
const MAX_BODY_BYTES: usize = 64 * 1024 * 1024;

pub fn embed_url(environ: &Environ) -> String {
    environ
        .get("FENNEC_EMBED_URL")
        .map(String::as_str)
        .filter(|v| !v.trim().is_empty())
        .unwrap_or(DEFAULT_EMBED_URL)
        .trim_end_matches('/')
        .to_string()
}

struct Origin {
    host: String,
    port: u16,
    authority: String,
    prefix: String,
}

fn parse_origin(base: &str) -> Result<Origin, String> {
    let rest = base
        .strip_prefix("http://")
        .ok_or_else(|| format!("embedding endpoint must be loopback http://: {base}"))?;
    let (authority, prefix) = match rest.find('/') {
        Some(i) => (&rest[..i], rest[i..].trim_end_matches('/').to_string()),
        None => (rest, String::new()),
    };
    let (host, port) = if let Some(bracketed) = authority.strip_prefix('[') {
        let end = bracketed
            .find(']')
            .ok_or_else(|| format!("invalid embedding endpoint: {base}"))?;
        let host = bracketed[..end].to_string();
        let port = match bracketed[end + 1..].strip_prefix(':') {
            Some(p) => p.parse::<u16>().map_err(|_| format!("invalid port in {base}"))?,
            None => 80,
        };
        (host, port)
    } else {
        match authority.rsplit_once(':') {
            Some((host, port)) if port.chars().all(|c| c.is_ascii_digit()) && !port.is_empty() => (
                host.to_string(),
                port.parse::<u16>().map_err(|_| format!("invalid port in {base}"))?,
            ),
            _ => (authority.to_string(), 80),
        }
    };
    if host.is_empty() {
        return Err(format!("invalid embedding endpoint: {base}"));
    }
    Ok(Origin { host, port, authority: authority.to_string(), prefix })
}

fn read_headers(reader: &mut BufReader<&mut TcpStream>) -> Result<(u16, Vec<(String, String)>, Vec<u8>, usize), String> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        let mut headers = [httparse::EMPTY_HEADER; 64];
        let mut response = httparse::Response::new(&mut headers);
        match response.parse(&buf) {
            Ok(httparse::Status::Complete(consumed)) => {
                let status = response.code.unwrap_or(0);
                let parsed = response
                    .headers
                    .iter()
                    .map(|h| {
                        (
                            h.name.to_ascii_lowercase(),
                            String::from_utf8_lossy(h.value).trim().to_string(),
                        )
                    })
                    .collect();
                return Ok((status, parsed, buf, consumed));
            }
            Ok(httparse::Status::Partial) => {
                if buf.len() > MAX_HEADER_BYTES {
                    return Err("embedding service response headers too large".into());
                }
                let read = reader
                    .read(&mut chunk)
                    .map_err(|e| format!("embedding service read failed: {e}"))?;
                if read == 0 {
                    return Err("embedding service closed the connection before responding".into());
                }
                buf.extend_from_slice(&chunk[..read]);
            }
            Err(e) => return Err(format!("embedding service sent a bad response: {e}")),
        }
    }
}

fn header<'a>(headers: &'a [(String, String)], name: &str) -> Option<&'a str> {
    headers.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
}

fn read_body(reader: &mut impl BufRead, headers: &[(String, String)]) -> Result<Vec<u8>, String> {
    let mut body = Vec::new();
    let chunked = header(headers, "transfer-encoding")
        .map(|v| v.to_ascii_lowercase().contains("chunked"))
        .unwrap_or(false);
    if chunked {
        loop {
            let mut size_line = String::new();
            reader
                .read_line(&mut size_line)
                .map_err(|e| format!("embedding service chunk read failed: {e}"))?;
            let size_text = size_line.trim().split(';').next().unwrap_or("");
            let size = usize::from_str_radix(size_text, 16)
                .map_err(|_| format!("embedding service sent a bad chunk size: {size_text:?}"))?;
            if size == 0 {
                // Consume trailers up to the empty line that ends the body.
                loop {
                    let mut trailer = String::new();
                    reader
                        .read_line(&mut trailer)
                        .map_err(|e| format!("embedding service trailer read failed: {e}"))?;
                    if trailer.trim().is_empty() {
                        break;
                    }
                }
                break;
            }
            if body.len() + size > MAX_BODY_BYTES {
                return Err("embedding service response too large".into());
            }
            let mut part = vec![0u8; size];
            reader
                .read_exact(&mut part)
                .map_err(|e| format!("embedding service chunk read failed: {e}"))?;
            body.extend_from_slice(&part);
            let mut crlf = [0u8; 2];
            reader
                .read_exact(&mut crlf)
                .map_err(|e| format!("embedding service chunk read failed: {e}"))?;
        }
        return Ok(body);
    }
    match header(headers, "content-length").and_then(|v| v.parse::<usize>().ok()) {
        Some(length) => {
            if length > MAX_BODY_BYTES {
                return Err("embedding service response too large".into());
            }
            if body.len() < length {
                let mut rest = vec![0u8; length - body.len()];
                reader
                    .read_exact(&mut rest)
                    .map_err(|e| format!("embedding service body read failed: {e}"))?;
                body.extend_from_slice(&rest);
            }
            body.truncate(length);
            Ok(body)
        }
        None => {
            reader
                .take(MAX_BODY_BYTES as u64)
                .read_to_end(&mut body)
                .map_err(|e| format!("embedding service body read failed: {e}"))?;
            Ok(body)
        }
    }
}

pub fn embed(base: &str, texts: &[String]) -> Result<Vec<Vec<f64>>, String> {
    if texts.is_empty() {
        return Ok(Vec::new());
    }
    let origin = parse_origin(base)?;
    let payload = serde_json::to_vec(&json!({ "input": texts })).map_err(|e| e.to_string())?;
    let request = format!(
        "POST {}/v1/embeddings HTTP/1.1\r\nHost: {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        origin.prefix, origin.authority, payload.len()
    );
    let unavailable = |e: std::io::Error| format!("embedding service unavailable at {base}: {e}");
    let address = (origin.host.as_str(), origin.port)
        .to_socket_addrs()
        .map_err(|e| unavailable(e))?
        .next()
        .ok_or_else(|| format!("embedding service unavailable at {base}: no address"))?;
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_secs(5)).map_err(unavailable)?;
    stream
        .set_write_timeout(Some(Duration::from_secs(10)))
        .map_err(unavailable)?;
    stream
        .set_read_timeout(Some(Duration::from_secs(120)))
        .map_err(unavailable)?;
    stream.write_all(request.as_bytes()).map_err(unavailable)?;
    stream.write_all(&payload).map_err(unavailable)?;
    stream.flush().map_err(unavailable)?;

    let mut reader = BufReader::new(&mut stream);
    let (status, headers, buf, consumed) = read_headers(&mut reader)?;
    // Body bytes can sit in two places: past the headers in the parse buffer,
    // and in the BufReader's own buffer. Chain both ahead of the socket.
    let mut leftover = buf[consumed..].to_vec();
    leftover.extend_from_slice(reader.buffer());
    let mut combined = BufReader::new(std::io::Cursor::new(leftover).chain(reader.into_inner()));
    let body = read_body(&mut combined, &headers)?;
    if status != 200 {
        return Err(format!(
            "embedding service at {base} returned HTTP {status}: {}",
            String::from_utf8_lossy(&body).chars().take(200).collect::<String>()
        ));
    }
    let value: Value = serde_json::from_slice(&body)
        .map_err(|e| format!("embedding service at {base} sent invalid JSON: {e}"))?;
    let rows = value
        .get("data")
        .and_then(Value::as_array)
        .ok_or_else(|| format!("embedding service at {base} sent no data array"))?;
    if rows.len() != texts.len() {
        return Err(format!(
            "embedding service at {base} returned {} embeddings for {} inputs",
            rows.len(),
            texts.len()
        ));
    }
    let mut vectors = Vec::with_capacity(rows.len());
    let mut dimension = None;
    for row in rows {
        let vector: Vec<f64> = row
            .get("embedding")
            .and_then(Value::as_array)
            .ok_or_else(|| format!("embedding service at {base} sent a row without an embedding"))?
            .iter()
            .map(|x| {
                x.as_f64()
                    .filter(|v| v.is_finite())
                    .ok_or_else(|| format!("embedding service at {base} sent a non-numeric embedding"))
            })
            .collect::<Result<_, _>>()?;
        if vector.is_empty() {
            return Err(format!("embedding service at {base} sent an empty embedding"));
        }
        match dimension {
            Some(d) if d != vector.len() => {
                return Err(format!("embedding service at {base} sent inconsistent dimensions"));
            }
            _ => dimension = Some(vector.len()),
        }
        vectors.push(vector);
    }
    Ok(vectors)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::thread;

    /// One-shot loopback fake: captures the request, replies with `response`.
    fn fake_server(response: String) -> (u16, mpsc::Receiver<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = mpsc::channel();
        thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let mut reader = BufReader::new(&stream);
            let mut request = String::new();
            let mut length = 0usize;
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                if line.to_ascii_lowercase().starts_with("content-length:") {
                    length = line.split(':').nth(1).unwrap().trim().parse().unwrap();
                }
                request.push_str(&line);
                if line == "\r\n" {
                    break;
                }
            }
            let mut body = vec![0u8; length];
            reader.read_exact(&mut body).unwrap();
            request.push_str(&String::from_utf8_lossy(&body));
            tx.send(request).unwrap();
            let mut stream = stream;
            stream.write_all(response.as_bytes()).unwrap();
            stream.flush().unwrap();
        });
        (port, rx)
    }

    fn ok_response(body: &str) -> String {
        format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body)
    }

    fn closed_port() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        port
    }

    #[test]
    fn posts_the_openai_compatible_payload_and_reads_vectors() {
        let (port, rx) = fake_server(ok_response(
            r#"{"data":[{"embedding":[1.0,0.0]},{"embedding":[0.0,1.0]}]}"#,
        ));
        let vectors = embed(
            &format!("http://127.0.0.1:{port}"),
            &["cat".to_string(), "dog".to_string()],
        )
        .unwrap();
        assert_eq!(vectors, vec![vec![1.0, 0.0], vec![0.0, 1.0]]);
        let request = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(request.starts_with("POST /v1/embeddings HTTP/1.1\r\n"), "{request}");
        assert!(request.contains("\"input\":[\"cat\",\"dog\"]"), "{request}");
    }

    #[test]
    fn decodes_chunked_responses() {
        let body = r#"{"data":[{"embedding":[0.5,0.5]}]}"#;
        let chunk = format!("{:x}\r\n{}\r\n0\r\n\r\n", body.len(), body);
        let response = format!(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n{chunk}"
        );
        let (port, _rx) = fake_server(response);
        let vectors = embed(&format!("http://127.0.0.1:{port}"), &["x".to_string()]).unwrap();
        assert_eq!(vectors, vec![vec![0.5, 0.5]]);
    }

    #[test]
    fn a_base_path_prefix_is_kept() {
        let (port, rx) = fake_server(ok_response(r#"{"data":[{"embedding":[1.0]}]}"#));
        embed(&format!("http://127.0.0.1:{port}/api"), &["x".to_string()]).unwrap();
        let request = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(request.starts_with("POST /api/v1/embeddings "), "{request}");
    }

    #[test]
    fn http_errors_are_reported_with_the_status() {
        let (port, _rx) = fake_server(
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndown".into(),
        );
        let error = embed(&format!("http://127.0.0.1:{port}"), &["x".to_string()]).unwrap_err();
        assert!(error.contains("503"), "{error}");
    }

    #[test]
    fn an_unreachable_service_is_an_error() {
        let port = closed_port();
        let error = embed(&format!("http://127.0.0.1:{port}"), &["x".to_string()]).unwrap_err();
        assert!(error.contains("unavailable"), "{error}");
    }

    #[test]
    fn a_count_mismatch_is_an_error() {
        let (port, _rx) = fake_server(ok_response(r#"{"data":[{"embedding":[1.0]}]}"#));
        let error = embed(
            &format!("http://127.0.0.1:{port}"),
            &["a".to_string(), "b".to_string()],
        )
        .unwrap_err();
        assert!(error.contains("1 embeddings for 2 inputs"), "{error}");
    }

    #[test]
    fn https_and_garbage_origins_are_rejected() {
        assert!(embed("https://example.com", &["x".to_string()]).is_err());
        assert!(embed("127.0.0.1:8182", &["x".to_string()]).is_err());
    }

    #[test]
    fn empty_input_needs_no_service() {
        assert!(embed("http://127.0.0.1:1", &[]).unwrap().is_empty());
    }

    #[test]
    fn the_url_comes_from_the_environment_or_the_suite_default() {
        let mut env = Environ::new();
        assert_eq!(embed_url(&env), DEFAULT_EMBED_URL);
        env.insert("FENNEC_EMBED_URL".into(), "http://127.0.0.1:9999/".into());
        assert_eq!(embed_url(&env), "http://127.0.0.1:9999");
        env.insert("FENNEC_EMBED_URL".into(), "  ".into());
        assert_eq!(embed_url(&env), DEFAULT_EMBED_URL);
    }
}