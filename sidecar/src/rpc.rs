//! JSON-RPC 2.0 over newline-delimited JSON on stdio. Two methods:
//! `context/chunk` (pure Rust structural chunking) and `context/search`
//! (cosine over the in-memory index, embeddings from the loopback service).
//! A request that cannot be answered honestly gets an error object, never a
//! fabricated result.

use std::io::{BufRead, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};

use serde_json::{json, Map, Value};

use crate::chunk::chunk_source;
use crate::embed;
use crate::search::Index;

pub struct State {
    pub index: Index,
    pub embed_url: String,
}

impl State {
    pub fn new(embed_url: String) -> State {
        State { index: Index::default(), embed_url }
    }
}

struct RpcError {
    code: i64,
    message: String,
}

impl RpcError {
    fn new(code: i64, message: impl Into<String>) -> RpcError {
        RpcError { code, message: message.into() }
    }

    fn invalid_params(message: impl Into<String>) -> RpcError {
        RpcError::new(-32602, message)
    }
}

fn response(id: Value, result: Result<Value, RpcError>) -> Value {
    match result {
        Ok(value) => json!({ "jsonrpc": "2.0", "id": id, "result": value }),
        Err(error) => json!({
            "jsonrpc": "2.0", "id": id,
            "error": { "code": error.code, "message": error.message }
        }),
    }
}

pub fn serve(reader: impl BufRead, writer: &mut impl Write, state: &mut State) -> std::io::Result<()> {
    for line in reader.lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        if let Some(reply) = handle_line(&line, state) {
            let encoded = serde_json::to_string(&reply).expect("response is always serializable");
            writeln!(writer, "{encoded}")?;
            writer.flush()?;
        }
    }
    Ok(())
}

pub fn handle_line(line: &str, state: &mut State) -> Option<Value> {
    let value: Value = match serde_json::from_str(line) {
        Ok(value) => value,
        Err(error) => {
            return Some(response(
                Value::Null,
                Err(RpcError::new(-32700, format!("Parse error: {error}"))),
            ));
        }
    };
    let id = value.get("id").cloned();
    let notification = id.is_none();
    let id = id.unwrap_or(Value::Null);

    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Value, RpcError> {
        let object = value
            .as_object()
            .ok_or_else(|| RpcError::new(-32600, "Invalid Request: expected a JSON object"))?;
        if object.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
            return Err(RpcError::new(-32600, "Invalid Request: jsonrpc must be \"2.0\""));
        }
        let method = object
            .get("method")
            .and_then(Value::as_str)
            .ok_or_else(|| RpcError::new(-32600, "Invalid Request: method must be a string"))?;
        let params = object.get("params").cloned().unwrap_or(Value::Null);
        dispatch(method, &params, state)
    }));
    let outcome = match outcome {
        Ok(outcome) => outcome,
        Err(_) => Err(RpcError::new(-32603, "Internal error: the request panicked")),
    };
    if notification {
        None
    } else {
        Some(response(id, outcome))
    }
}

fn dispatch(method: &str, params: &Value, state: &mut State) -> Result<Value, RpcError> {
    match method {
        "context/chunk" => context_chunk(params),
        "context/search" => context_search(params, state),
        _ => Err(RpcError::new(-32601, format!("Method not found: {method}"))),
    }
}

fn object_param<'a>(params: &'a Value) -> Result<&'a Map<String, Value>, RpcError> {
    params
        .as_object()
        .ok_or_else(|| RpcError::invalid_params("params must be an object"))
}

fn string_field<'a>(object: &'a Map<String, Value>, key: &str) -> Result<&'a str, RpcError> {
    object
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| RpcError::invalid_params(format!("{key} must be a string")))
}

fn optional_string<'a>(object: &'a Map<String, Value>, key: &str) -> Result<Option<&'a str>, RpcError> {
    match object.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.as_str())),
        Some(_) => Err(RpcError::invalid_params(format!("{key} must be a string"))),
    }
}

fn context_chunk(params: &Value) -> Result<Value, RpcError> {
    let object = object_param(params)?;
    let text = string_field(object, "text")?;
    let path = optional_string(object, "path")?;
    let language = optional_string(object, "language")?;
    let chunks = chunk_source(text, path, language);
    Ok(json!({ "chunks": chunks }))
}

fn context_search(params: &Value, state: &mut State) -> Result<Value, RpcError> {
    let object = object_param(params)?;
    let query = optional_string(object, "query")?;
    let documents = match object.get("documents") {
        None | Some(Value::Null) => None,
        Some(Value::Array(items)) => Some(items),
        Some(_) => return Err(RpcError::invalid_params("documents must be an array")),
    };
    if query.is_none() && documents.is_none() {
        return Err(RpcError::invalid_params("context/search needs a query, documents, or both"));
    }
    let k = match object.get("k") {
        None | Some(Value::Null) => 8,
        Some(Value::Number(n)) => n
            .as_u64()
            .filter(|k| *k <= 1000)
            .ok_or_else(|| RpcError::invalid_params("k must be a positive integer"))?
            as usize,
        Some(_) => return Err(RpcError::invalid_params("k must be a positive integer")),
    };
    let k = k.clamp(1, 20);

    let mut result = Map::new();
    if let Some(items) = documents {
        let mut ids = Vec::with_capacity(items.len());
        let mut texts = Vec::with_capacity(items.len());
        for item in items {
            let doc = item
                .as_object()
                .ok_or_else(|| RpcError::invalid_params("each document must be an object"))?;
            let id = string_field(doc, "id")?;
            if id.is_empty() {
                return Err(RpcError::invalid_params("document id must not be empty"));
            }
            ids.push(id.to_string());
            texts.push(string_field(doc, "text")?.to_string());
        }
        let vectors = embed::embed(&state.embed_url, &texts)
            .map_err(|e| RpcError::new(-32000, e))?;
        state.index.replace(ids.into_iter().zip(vectors).collect());
        result.insert("indexed".into(), json!(state.index.len()));
    }
    if let Some(query) = query {
        let vectors = embed::embed(&state.embed_url, &[query.to_string()])
            .map_err(|e| RpcError::new(-32000, e))?;
        let hits = state
            .index
            .search(&vectors[0], k)
            .map_err(RpcError::invalid_params)?;
        result.insert("results".into(), json!(hits));
    }
    result.insert("total".into(), json!(state.index.len()));
    Ok(Value::Object(result))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> State {
        State::new("http://127.0.0.1:1".into())
    }

    fn call(line: &str, state: &mut State) -> Value {
        handle_line(line, state).expect("a request with an id gets a response")
    }

    #[test]
    fn chunk_requests_return_structural_chunks() {
        let reply = call(
            r#"{"jsonrpc":"2.0","id":1,"method":"context/chunk","params":{"text":"fn main() {}\n","path":"a.rs"}}"#,
            &mut state(),
        );
        assert_eq!(reply["id"], json!(1));
        assert_eq!(reply["result"]["chunks"][0]["symbol"], json!("main"));
        assert_eq!(reply["result"]["chunks"][0]["start_byte"], json!(0));
    }

    #[test]
    fn chunk_requires_text() {
        let reply = call(
            r#"{"jsonrpc":"2.0","id":2,"method":"context/chunk","params":{}}"#,
            &mut state(),
        );
        assert_eq!(reply["error"]["code"], json!(-32602));
    }

    #[test]
    fn unknown_methods_and_bad_envelopes_get_spec_errors() {
        let mut s = state();
        let reply = call(r#"{"jsonrpc":"2.0","id":3,"method":"nope"}"#, &mut s);
        assert_eq!(reply["error"]["code"], json!(-32601));
        let reply = call("{not json", &mut s);
        assert_eq!(reply["error"]["code"], json!(-32700));
        assert_eq!(reply["id"], Value::Null);
        let reply = call(r#"{"id":4,"method":"context/chunk"}"#, &mut s);
        assert_eq!(reply["error"]["code"], json!(-32600));
        let reply = call(r#"{"jsonrpc":"2.0","id":5}"#, &mut s);
        assert_eq!(reply["error"]["code"], json!(-32600));
    }

    #[test]
    fn notifications_get_no_response() {
        let mut s = state();
        assert!(handle_line(
            r#"{"jsonrpc":"2.0","method":"context/chunk","params":{"text":"fn a() {}"}}"#,
            &mut s
        )
        .is_none());
    }

    #[test]
    fn search_without_a_service_is_an_error_not_a_fake_score() {
        let mut s = state();
        let reply = call(
            r#"{"jsonrpc":"2.0","id":6,"method":"context/search","params":{"query":"anything","documents":[{"id":"d","text":"body"}]}}"#,
            &mut s,
        );
        assert_eq!(reply["error"]["code"], json!(-32000));
        assert!(reply["error"]["message"].as_str().unwrap().contains("embedding service unavailable"));
        assert!(reply.get("result").is_none());
        assert_eq!(s.index.len(), 0);
    }

    #[test]
    fn search_needs_a_query_or_documents() {
        let reply = call(
            r#"{"jsonrpc":"2.0","id":7,"method":"context/search","params":{}}"#,
            &mut state(),
        );
        assert_eq!(reply["error"]["code"], json!(-32602));
    }

    #[test]
    fn document_shape_is_validated() {
        let mut s = state();
        let reply = call(
            r#"{"jsonrpc":"2.0","id":8,"method":"context/search","params":{"documents":[{"id":"","text":"x"}]}}"#,
            &mut s,
        );
        assert_eq!(reply["error"]["code"], json!(-32602));
        let reply = call(
            r#"{"jsonrpc":"2.0","id":9,"method":"context/search","params":{"documents":"nope"}}"#,
            &mut s,
        );
        assert_eq!(reply["error"]["code"], json!(-32602));
    }

    #[test]
    fn serve_answers_line_by_line_and_skips_blanks() {
        let input = "\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"context/chunk\",\"params\":{\"text\":\"fn a() {}\"}}\n\n";
        let mut output = Vec::new();
        serve(input.as_bytes(), &mut output, &mut state()).unwrap();
        let text = String::from_utf8(output).unwrap();
        assert_eq!(text.lines().count(), 1);
        let reply: Value = serde_json::from_str(text.lines().next().unwrap()).unwrap();
        assert_eq!(reply["result"]["chunks"][0]["symbol"], json!("a"));
    }
}