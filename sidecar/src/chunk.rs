//! Structural source chunking: declarations and blocks with byte ranges and a
//! symbol name. Deliberately not a parser - a brace/indent/declaration-aware
//! scanner covers the languages the suite indexes without per-language C
//! grammars, and byte ranges stay exact even when the heuristic misreads a body.

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Chunk {
    pub symbol: String,
    pub kind: String,
    pub start_byte: usize,
    pub end_byte: usize,
    pub start_line: usize,
    pub end_line: usize,
    pub text: String,
}

#[derive(Clone, Copy, PartialEq)]
enum Family {
    Brace,
    Indent,
    Blocks,
}

struct Line<'a> {
    start: usize,
    end: usize,
    text: &'a str,
}

impl Line<'_> {
    fn indent(&self) -> usize {
        self.text.len() - self.text.trim_start().len()
    }

    fn blank(&self) -> bool {
        self.text.trim().is_empty()
    }
}

const MODIFIERS: &[&str] = &[
    "pub", "public", "private", "protected", "internal", "static", "final", "abstract", "sealed",
    "open", "override", "virtual", "extern", "inline", "suspend", "async", "export", "default",
    "declare", "const", "constexpr", "unsafe", "data", "value", "annotation", "companion",
    "operator", "infix", "tailrec", "actual", "expect", "native", "synchronized", "transient",
    "volatile", "readonly", "partial", "new", "friend", "crate", "reified", "vararg", "lateinit",
];

const DECL_KEYWORDS: &[&str] = &[
    "fn", "fun", "func", "function", "def", "class", "struct", "enum", "interface", "trait",
    "impl", "type", "typealias", "object", "protocol", "extension", "namespace", "module",
    "record", "union",
];

fn kind_of(keyword: &str) -> &'static str {
    match keyword {
        "fn" | "fun" | "func" | "function" | "def" => "function",
        "class" => "class",
        "struct" => "struct",
        "enum" => "enum",
        "interface" | "trait" | "protocol" => "interface",
        "impl" => "impl",
        "type" | "typealias" => "type",
        "object" => "object",
        "extension" => "extension",
        "namespace" | "module" => "namespace",
        "record" => "record",
        "union" => "union",
        _ => "declaration",
    }
}

fn family_for(path: Option<&str>, language: Option<&str>, text: &str) -> Family {
    let hint = language
        .map(str::to_ascii_lowercase)
        .or_else(|| path.and_then(|p| p.rsplit('.').next().map(str::to_ascii_lowercase)));
    match hint.as_deref() {
        Some("py" | "python" | "pyi") => return Family::Indent,
        Some("rs" | "rust" | "java" | "kt" | "kts" | "js" | "jsx" | "mjs" | "cjs" | "ts" | "tsx"
        | "c" | "h" | "cc" | "cpp" | "cxx" | "hpp" | "cs" | "go" | "swift" | "php" | "scala"
        | "dart" | "groovy") => return Family::Brace,
        _ => {}
    }
    if text.lines().any(|l| {
        let t = l.trim_start();
        t.starts_with("def ") || t.starts_with("class ") || t.starts_with("async def ")
    }) {
        Family::Indent
    } else if text.contains('{') {
        Family::Brace
    } else {
        Family::Blocks
    }
}

fn first_token(text: &str) -> &str {
    let end = text
        .find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
        .unwrap_or(text.len());
    &text[..end]
}

fn first_ident(text: &str) -> &str {
    let trimmed = text.trim_start();
    if trimmed.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_') {
        first_token(trimmed)
    } else {
        ""
    }
}

fn skip_balanced(text: &str, open: char, close: char) -> &str {
    let mut depth = 0usize;
    for (i, c) in text.char_indices() {
        if c == open {
            depth += 1;
        } else if c == close {
            depth -= 1;
            if depth == 0 {
                return &text[i + 1..];
            }
        }
    }
    ""
}

/// The declaration a line starts, if any: modifiers, a declaration keyword, a name.
fn brace_decl(line: &str) -> Option<(String, &'static str)> {
    let trimmed = line.trim_start();
    if trimmed.starts_with("//") || trimmed.starts_with("/*") || trimmed.starts_with('*') {
        return None;
    }
    let mut rest = trimmed;
    let mut binding = false;
    loop {
        let word = first_token(rest);
        if MODIFIERS.contains(&word) && word.len() < rest.len() {
            binding |= matches!(word, "const" | "let" | "var");
            rest = rest[word.len()..].trim_start();
        } else {
            break;
        }
    }
    let word = first_token(rest);
    if DECL_KEYWORDS.contains(&word) {
        let mut after = rest[word.len()..].trim_start();
        // Go methods: func (r *Receiver) Name(...)
        if word == "func" && after.starts_with('(') {
            after = skip_balanced(after, '(', ')').trim_start();
        }
        let name = first_ident(after);
        if !name.is_empty() && !MODIFIERS.contains(&name) && !DECL_KEYWORDS.contains(&name) {
            return Some((name.to_string(), kind_of(word)));
        }
        return None;
    }
    // JS/TS arrow functions bound to a name: const foo = (...) => { ... }
    if binding && rest.contains("=>") {
        let name = first_ident(rest);
        if !name.is_empty() {
            return Some((name.to_string(), "function"));
        }
    }
    None
}

/// Tracks strings and comments so braces inside them do not end a block early.
struct Scanner {
    string: Option<char>,
    escaped: bool,
    line_comment: bool,
    block_comment: bool,
}

impl Scanner {
    fn new() -> Scanner {
        Scanner { string: None, escaped: false, line_comment: false, block_comment: false }
    }

    /// Feeds one line; returns the byte positions of significant '{', '}', ';'.
    fn scan_line<'a>(&mut self, line: &'a str, events: &mut Vec<(usize, char)>) {
        self.line_comment = false;
        let bytes = line.as_bytes();
        let mut i = 0usize;
        while i < bytes.len() {
            let c = bytes[i] as char;
            if self.line_comment {
                break;
            }
            if self.block_comment {
                if c == '*' && bytes.get(i + 1) == Some(&b'/') {
                    self.block_comment = false;
                    i += 2;
                    continue;
                }
                i += 1;
                continue;
            }
            if let Some(quote) = self.string {
                if self.escaped {
                    self.escaped = false;
                } else if c == '\\' && quote != '`' {
                    self.escaped = true;
                } else if c == quote {
                    self.string = None;
                }
                i += 1;
                continue;
            }
            match c {
                '/' if bytes.get(i + 1) == Some(&b'/') => {
                    self.line_comment = true;
                    break;
                }
                '/' if bytes.get(i + 1) == Some(&b'*') => {
                    self.block_comment = true;
                    i += 2;
                    continue;
                }
                '#' if bytes.get(i + 1) == Some(&b'!') => break, // shebang
                '"' | '`' => self.string = Some(c),
                '\'' => {
                    // A char literal is one character or an escape; anything else
                    // (Rust lifetimes, labels) is not a string.
                    let char_literal = bytes.get(i + 1) == Some(&b'\\')
                        || (bytes.get(i + 2) == Some(&b'\'') && bytes.get(i + 1).is_some());
                    if char_literal {
                        self.string = Some('\'');
                    }
                }
                '{' | '}' | ';' => events.push((i, c)),
                _ => {}
            }
            i += 1;
        }
    }
}

/// Inclusive last line of the declaration starting at `start`.
fn brace_block_end(lines: &[Line<'_>], start: usize) -> usize {
    let mut scanner = Scanner::new();
    let mut depth = 0i32;
    let mut saw_brace = false;
    let mut events = Vec::new();
    for (li, line) in lines.iter().enumerate().skip(start) {
        events.clear();
        scanner.scan_line(line.text, &mut events);
        for (_, c) in &events {
            match c {
                '{' => {
                    depth += 1;
                    saw_brace = true;
                }
                '}' => {
                    depth -= 1;
                    if saw_brace && depth <= 0 {
                        return li;
                    }
                }
                ';' if !saw_brace => return li,
                _ => {}
            }
        }
        if !saw_brace && li > start {
            // Expression-bodied declarations (Kotlin `fun f() = ...`): the body
            // runs until a blank line, the next declaration, or a line that is
            // no longer a continuation at deeper indent.
            if line.blank() || brace_decl(line.text).is_some() {
                return li - 1;
            }
            let continuation = line.text.trim_start().starts_with([
                '.', '=', '+', '-', '*', '/', '?', ':', ',', '|', '&', ')', '}', ']',
            ]) || line.text.trim_start().starts_with("else")
                || line.text.trim_start().starts_with("catch")
                || line.text.trim_start().starts_with("where");
            if line.indent() <= lines[start].indent() && !continuation {
                return li - 1;
            }
        }
    }
    let mut end = lines.len() - 1;
    while end > start && lines[end].blank() {
        end -= 1;
    }
    end
}

fn python_decl(line: &str) -> Option<(String, &'static str)> {
    let trimmed = line.trim_start();
    let (keyword, rest) = if let Some(rest) = trimmed.strip_prefix("async ") {
        ("def", rest.trim_start())
    } else {
        (first_token(trimmed), trimmed)
    };
    match keyword {
        "def" => {
            let name = first_ident(rest["def".len()..].trim_start());
            if name.is_empty() {
                None
            } else {
                Some((name.to_string(), "function"))
            }
        }
        "class" => {
            let name = first_ident(rest["class".len()..].trim_start());
            if name.is_empty() {
                None
            } else {
                Some((name.to_string(), "class"))
            }
        }
        _ => None,
    }
}

fn make_chunk(lines: &[Line<'_>], source: &str, start: usize, end: usize, symbol: &str, kind: &str) -> Chunk {
    let start_byte = lines[start].start;
    let end_byte = lines[end].end;
    Chunk {
        symbol: symbol.to_string(),
        kind: kind.to_string(),
        start_byte,
        end_byte,
        start_line: start + 1,
        end_line: end + 1,
        text: source[start_byte..end_byte].to_string(),
    }
}

fn split_lines(source: &str) -> Vec<Line<'_>> {
    let mut lines = Vec::new();
    let mut start = 0usize;
    for (idx, c) in source.char_indices() {
        if c == '\n' {
            let mut end = idx;
            if end > start && source.as_bytes()[end - 1] == b'\r' {
                end -= 1;
            }
            lines.push(Line { start, end, text: &source[start..end] });
            start = idx + 1;
        }
    }
    if start < source.len() {
        let mut end = source.len();
        if end > start && source.as_bytes()[end - 1] == b'\r' {
            end -= 1;
        }
        lines.push(Line { start, end, text: &source[start..end] });
    }
    lines
}

fn block_chunks(lines: &[Line<'_>], source: &str, symbol: &str) -> Vec<Chunk> {
    let mut chunks = Vec::new();
    let mut i = 0usize;
    while i < lines.len() {
        let end = (i + 40).min(lines.len()) - 1;
        let chunk = make_chunk(lines, source, i, end, symbol, "block");
        if chunk.text.trim().len() >= 20 {
            chunks.push(chunk);
        }
        i = end + 1;
    }
    chunks
}

pub fn chunk_source(text: &str, path: Option<&str>, language: Option<&str>) -> Vec<Chunk> {
    if text.trim().is_empty() {
        return Vec::new();
    }
    let lines = split_lines(text);
    if lines.is_empty() {
        return Vec::new();
    }
    let block_symbol = path
        .and_then(|p| p.rsplit(['/', '\\']).next())
        .and_then(|name| name.split('.').next())
        .filter(|name| !name.is_empty())
        .unwrap_or("block");
    let family = family_for(path, language, text);
    if family == Family::Blocks {
        return block_chunks(&lines, text, block_symbol);
    }
    let mut chunks = Vec::new();
    let mut gap_start: Option<usize> = None;
    let mut gap_end = 0usize;
    let mut i = 0usize;

    let flush_gap = |chunks: &mut Vec<Chunk>, gap_start: &mut Option<usize>, gap_end: usize| {
        if let Some(start) = gap_start.take() {
            if gap_end >= start {
                chunks.push(make_chunk(&lines, text, start, gap_end, "<module>", "block"));
            }
        }
    };

    while i < lines.len() {
        let declaration = match family {
            Family::Brace => brace_decl(lines[i].text),
            Family::Indent => python_decl(lines[i].text),
            Family::Blocks => None,
        };
        if let Some((symbol, kind)) = declaration {
            let mut start = i;
            if family == Family::Indent {
                // Decorators belong to the declaration they sit on.
                while start > 0
                    && lines[start - 1].text.trim_start().starts_with('@')
                    && lines[start - 1].indent() == lines[i].indent()
                {
                    start -= 1;
                }
            }
            flush_gap(&mut chunks, &mut gap_start, start.saturating_sub(1));
            let mut end = match family {
                Family::Brace => brace_block_end(&lines, i),
                Family::Indent => {
                    let indent = lines[i].indent();
                    let mut end = i;
                    for (li, line) in lines.iter().enumerate().skip(i + 1) {
                        if line.blank() || line.indent() > indent {
                            end = li;
                        } else {
                            break;
                        }
                    }
                    while end > i && lines[end].blank() {
                        end -= 1;
                    }
                    end
                }
                Family::Blocks => unreachable!(),
            };
            if end < start {
                end = start;
            }
            chunks.push(make_chunk(&lines, text, start, end, &symbol, kind));
            i = end + 1;
            gap_end = i.saturating_sub(1);
        } else {
            if !lines[i].blank() {
                if gap_start.is_none() {
                    gap_start = Some(i);
                }
                gap_end = i;
            }
            i += 1;
        }
    }
    flush_gap(&mut chunks, &mut gap_start, gap_end);

    if chunks.is_empty() {
        return block_chunks(&lines, text, block_symbol);
    }
    chunks
}

#[cfg(test)]
mod tests {
    use super::*;

    fn symbols(chunks: &[Chunk]) -> Vec<&str> {
        chunks.iter().map(|c| c.symbol.as_str()).collect()
    }

    #[test]
    fn rust_declarations_carry_names_kinds_and_exact_bytes() {
        let source = "use std::fmt;\n\npub struct Foo {\n    x: i32,\n}\n\nimpl Foo {\n    pub fn bar(&self) -> i32 {\n        self.x\n    }\n}\n\nfn main() {\n    println!(\"hi\");\n}\n";
        let chunks = chunk_source(source, Some("lib.rs"), None);
        assert_eq!(symbols(&chunks), vec!["<module>", "Foo", "Foo", "main"]);
        assert_eq!(chunks[1].kind, "struct");
        assert_eq!(chunks[2].kind, "impl");
        assert_eq!(chunks[3].kind, "function");
        for chunk in &chunks {
            assert_eq!(&source[chunk.start_byte..chunk.end_byte], chunk.text);
            assert!(chunk.start_byte < chunk.end_byte);
        }
        let main = &chunks[3];
        assert!(main.text.starts_with("fn main()"));
        assert!(main.text.ends_with('}'));
        assert_eq!(main.start_line, 13);
    }

    #[test]
    fn braces_inside_strings_and_comments_do_not_end_blocks() {
        let source = "fn tricky() {\n    let s = \"}\";\n    // }\n    /* } */\n    let c = '}';\n}\nfn next() {}\n";
        let chunks = chunk_source(source, Some("a.rs"), None);
        assert_eq!(symbols(&chunks), vec!["tricky", "next"]);
        assert!(chunks[0].text.contains("let c"));
        assert_eq!(chunks[0].end_line, 6);
    }

    #[test]
    fn rust_lifetimes_are_not_char_literals() {
        let source = "fn borrow<'a>(x: &'a str) -> &'a str {\n    x\n}\nfn after() {}\n";
        let chunks = chunk_source(source, Some("a.rs"), None);
        assert_eq!(symbols(&chunks), vec!["borrow", "after"]);
        assert_eq!(chunks[0].end_line, 3);
    }

    #[test]
    fn kotlin_classes_and_expression_bodies() {
        let source = "package demo\n\nclass Greeter(val name: String) {\n    fun greet(): String {\n        return \"hi $name\"\n    }\n}\n\nfun double(x: Int) = x * 2\n\nfun main() {\n    println(double(2))\n}\n";
        let chunks = chunk_source(source, Some("Demo.kt"), None);
        assert_eq!(symbols(&chunks), vec!["<module>", "Greeter", "double", "main"]);
        assert_eq!(chunks[2].text, "fun double(x: Int) = x * 2");
        assert_eq!(chunks[3].start_line, 11);
    }

    #[test]
    fn python_indentation_decorators_and_nesting() {
        let source = "import os\n\n@decorator\nclass Thing:\n    def method(self):\n        return 1\n\n    def other(self):\n        pass\n\ndef top():\n    return 2\n";
        let chunks = chunk_source(source, Some("thing.py"), None);
        assert_eq!(symbols(&chunks), vec!["<module>", "Thing", "top"]);
        assert!(chunks[1].text.starts_with("@decorator"));
        assert!(chunks[1].text.contains("def other"));
        assert_eq!(chunks[1].end_line, 9);
        assert_eq!(chunks[2].text, "def top():\n    return 2");
    }

    #[test]
    fn python_async_def() {
        let source = "async def fetch():\n    await x()\n\ndef sync():\n    pass\n";
        let chunks = chunk_source(source, Some("a.py"), None);
        assert_eq!(symbols(&chunks), vec!["fetch", "sync"]);
        assert_eq!(chunks[0].kind, "function");
    }

    #[test]
    fn javascript_arrows_and_go_methods() {
        let js = "export const handler = async (req) => {\n    return req;\n};\nfunction plain() {}\n";
        let chunks = chunk_source(js, Some("a.js"), None);
        assert_eq!(symbols(&chunks), vec!["handler", "plain"]);
        let go = "package main\n\nfunc (s *Server) Start() error {\n\treturn nil\n}\n\nfunc main() {}\n";
        let chunks = chunk_source(go, Some("a.go"), None);
        assert_eq!(symbols(&chunks), vec!["<module>", "Start", "main"]);
    }

    #[test]
    fn declaration_without_a_body_ends_at_the_semicolon() {
        let source = "type Alias = Other;\nfn real() {}\n";
        let chunks = chunk_source(source, Some("a.rs"), None);
        assert_eq!(symbols(&chunks), vec!["Alias", "real"]);
        assert_eq!(chunks[0].text, "type Alias = Other;");
    }

    #[test]
    fn unknown_text_falls_back_to_line_blocks() {
        let source = "line one\nline two\nline three\n";
        let chunks = chunk_source(source, None, None);
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].kind, "block");
        assert_eq!(chunks[0].symbol, "block");
        assert_eq!(chunks[0].start_byte, 0);
        assert_eq!(chunks[0].end_byte, source.len() - 1);
        let long = "word ".repeat(200).replace("word ", "a line of text\n");
        let chunks = chunk_source(&long, Some("notes.txt"), None);
        assert!(chunks.len() >= 2);
        assert_eq!(chunks[0].symbol, "notes");
    }

    #[test]
    fn unicode_byte_ranges_stay_exact() {
        let source = "// café 日本語\nfn emoji() {\n    let s = \"🦊\";\n}\n";
        let chunks = chunk_source(source, Some("u.rs"), None);
        assert_eq!(symbols(&chunks), vec!["<module>", "emoji"]);
        for chunk in &chunks {
            assert_eq!(&source[chunk.start_byte..chunk.end_byte], chunk.text);
        }
        assert_eq!(chunks[1].start_byte, "// café 日本語\n".len());
    }

    #[test]
    fn crlf_sources_chunk_without_carriage_returns() {
        let source = "fn a() {\r\n    x\r\n}\r\nfn b() {}\r\n";
        let chunks = chunk_source(source, Some("a.rs"), None);
        assert_eq!(symbols(&chunks), vec!["a", "b"]);
        assert_eq!(chunks[0].text, "fn a() {\r\n    x\r\n}");
        assert_eq!(&source[chunks[1].start_byte..chunks[1].end_byte], "fn b() {}");
    }

    #[test]
    fn empty_and_blank_sources_have_no_chunks() {
        assert!(chunk_source("", None, None).is_empty());
        assert!(chunk_source("  \n\n\t\n", None, None).is_empty());
    }

    #[test]
    fn explicit_language_overrides_the_extension() {
        let source = "def f():\n    pass\n";
        let chunks = chunk_source(source, Some("a.txt"), Some("python"));
        assert_eq!(symbols(&chunks), vec!["f"]);
    }
}