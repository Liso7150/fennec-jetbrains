//! In-memory relevance index over the chunks the panel just sent.
//!
//! Two scorers share one order contract. Cosine mirrors `tools.semantic_search`
//! in fennec-core and is used when an embedding endpoint answered for both the
//! documents and the query. BM25 over the stored text is used when it did not,
//! so a search still answers on a machine with no embedding service. Which
//! scorer produced the order is returned with the hits, never implied.

use std::collections::HashMap;

use serde::Serialize;

const K1: f64 = 1.5;
const B: f64 = 0.75;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    Semantic,
    Lexical,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Hit {
    pub id: String,
    pub score: f64,
}

#[derive(Debug, Clone)]
pub struct Document {
    pub id: String,
    pub text: String,
    pub vector: Option<Vec<f64>>,
}

#[derive(Default)]
pub struct Index {
    docs: Vec<Document>,
}

impl Index {
    pub fn replace(&mut self, docs: Vec<Document>) {
        self.docs = docs;
    }

    pub fn len(&self) -> usize {
        self.docs.len()
    }

    pub fn is_empty(&self) -> bool {
        self.docs.is_empty()
    }

    /// Rank the stored documents for `query`. `query_vector` is the embedding
    /// of the query, when an endpoint produced one; cosine needs every
    /// document to carry a vector of the same width, otherwise BM25 scores the
    /// text and a width mismatch is never reached.
    pub fn search(
        &self,
        query: &str,
        query_vector: Option<&[f64]>,
        k: usize,
    ) -> Result<(Vec<Hit>, Mode), String> {
        let document_vectors: Option<Vec<&[f64]>> =
            self.docs.iter().map(|doc| doc.vector.as_deref()).collect();
        if let (Some(vector), Some(vectors)) = (query_vector, document_vectors) {
            let mut hits = Vec::with_capacity(self.docs.len());
            for (position, (doc, document_vector)) in self.docs.iter().zip(vectors).enumerate() {
                if document_vector.len() != vector.len() {
                    return Err(format!(
                        "embedding dimension mismatch: document {} has {} values, query has {}",
                        doc.id,
                        document_vector.len(),
                        vector.len()
                    ));
                }
                hits.push((
                    position,
                    Hit { id: doc.id.clone(), score: cosine(vector, document_vector) },
                ));
            }
            hits.sort_by(|a, b| b.1.score.total_cmp(&a.1.score).then(a.0.cmp(&b.0)));
            hits.truncate(k);
            return Ok((hits.into_iter().map(|(_, hit)| hit).collect(), Mode::Semantic));
        }
        Ok((bm25(query, &self.docs, k), Mode::Lexical))
    }
}

pub fn cosine(a: &[f64], b: &[f64]) -> f64 {
    let dot: f64 = a.iter().zip(b).map(|(x, y)| x * y).sum();
    let na: f64 = a.iter().map(|x| x * x).sum::<f64>().sqrt();
    let nb: f64 = b.iter().map(|y| y * y).sum::<f64>().sqrt();
    dot / (na * nb + 1e-9)
}

/// Lowercase runs of alphanumeric characters and underscores, each followed by
/// its underscore-separated pieces, so `parse_chunks` answers to both the whole
/// identifier and `chunks`. Everything else is a separator, which means
/// `fennec-acp.exe` indexes as `fennec`, `acp`, `exe`.
fn tokenize(text: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    for ch in text.chars() {
        if ch.is_alphanumeric() || ch == '_' {
            current.extend(ch.to_lowercase());
        } else if !current.is_empty() {
            push_run(&mut tokens, std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        push_run(&mut tokens, current);
    }
    tokens
}

fn push_run(tokens: &mut Vec<String>, run: String) {
    tokens.push(run.clone());
    if run.contains('_') {
        for piece in run.split('_') {
            if !piece.is_empty() {
                tokens.push(piece.to_string());
            }
        }
    }
}

fn bm25(query: &str, docs: &[Document], k: usize) -> Vec<Hit> {
    let query_terms = tokenize(query);
    if query_terms.is_empty() {
        return Vec::new();
    }
    let frequencies: Vec<HashMap<String, usize>> = docs
        .iter()
        .map(|doc| {
            let mut counts = HashMap::new();
            for token in tokenize(&doc.text) {
                *counts.entry(token).or_insert(0) += 1;
            }
            counts
        })
        .collect();
    let lengths: Vec<usize> = frequencies.iter().map(|counts| counts.values().sum()).collect();
    let total: usize = lengths.iter().sum();
    let average = total as f64 / docs.len().max(1) as f64;

    let mut document_frequency: HashMap<&str, usize> = HashMap::new();
    for term in &query_terms {
        let term = term.as_str();
        if document_frequency.contains_key(term) {
            continue;
        }
        document_frequency.insert(
            term,
            frequencies.iter().filter(|counts| counts.contains_key(term)).count(),
        );
    }

    let total_docs = docs.len() as f64;
    let mut scored: Vec<(usize, Hit)> = Vec::with_capacity(docs.len());
    for (position, doc) in docs.iter().enumerate() {
        let mut score = 0.0;
        for term in &query_terms {
            let Some(found) = frequencies[position].get(term) else {
                continue;
            };
            let df = *document_frequency.get(term.as_str()).unwrap_or(&0) as f64;
            let idf = ((total_docs - df + 0.5) / (df + 0.5) + 1.0).ln();
            let norm = K1 * (1.0 - B + B * lengths[position] as f64 / average.max(1.0));
            score += idf * (*found as f64 * (K1 + 1.0)) / (*found as f64 + norm);
        }
        if score > 0.0 {
            scored.push((position, Hit { id: doc.id.clone(), score }));
        }
    }
    scored.sort_by(|a, b| b.1.score.total_cmp(&a.1.score).then(a.0.cmp(&b.0)));
    scored.truncate(k);
    scored.into_iter().map(|(_, hit)| hit).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vector_docs() -> Vec<Document> {
        vec![
            Document { id: "a".into(), text: "alpha".into(), vector: Some(vec![1.0, 0.0]) },
            Document { id: "b".into(), text: "bravo".into(), vector: Some(vec![0.0, 1.0]) },
            Document { id: "c".into(), text: "charlie".into(), vector: Some(vec![1.0, 1.0]) },
        ]
    }

    fn text_docs() -> Vec<Document> {
        vec![
            Document {
                id: "parser".into(),
                text: "fn parse_chunks(result: JsonObject) -> Vec<Chunk>".into(),
                vector: None,
            },
            Document {
                id: "network".into(),
                text: "fn embed(base: &str, inputs: &[String]) -> Result<Vec<f64>, String>".into(),
                vector: None,
            },
            Document {
                id: "empty".into(),
                text: "fn noop() {}".into(),
                vector: None,
            },
        ]
    }

    #[test]
    fn cosine_matches_the_python_formula() {
        let score = cosine(&[1.0, 0.0], &[1.0, 1.0]);
        let expected = 1.0 / (1.0 * 2.0f64.sqrt() + 1e-9);
        assert!((score - expected).abs() < 1e-12);
        assert_eq!(cosine(&[0.0, 0.0], &[1.0, 0.0]), 0.0);
    }

    #[test]
    fn search_ranks_by_similarity_and_honors_k() {
        let mut index = Index::default();
        index.replace(vector_docs());
        let (hits, mode) = index.search("anything", Some(&[1.0, 0.0]), 8).unwrap();
        assert_eq!(mode, Mode::Semantic);
        assert_eq!(hits[0].id, "a");
        assert!((hits[0].score - 1.0).abs() < 1e-6);
        assert_eq!(hits[1].id, "c");
        assert_eq!(hits[2].id, "b");
        assert_eq!(index.search("anything", Some(&[1.0, 0.0]), 1).unwrap().0.len(), 1);
    }

    #[test]
    fn ties_keep_document_order() {
        let mut index = Index::default();
        index.replace(vec![
            Document { id: "first".into(), text: "x".into(), vector: Some(vec![1.0]) },
            Document { id: "second".into(), text: "x".into(), vector: Some(vec![1.0]) },
        ]);
        let (hits, _) = index.search("anything", Some(&[1.0]), 2).unwrap();
        assert_eq!(hits[0].id, "first");
        assert_eq!(hits[1].id, "second");
    }

    #[test]
    fn empty_index_returns_no_hits() {
        let index = Index::default();
        assert!(index.is_empty());
        assert!(index.search("anything", Some(&[1.0]), 8).unwrap().0.is_empty());
        assert!(index.search("anything", None, 8).unwrap().0.is_empty());
    }

    #[test]
    fn dimension_mismatch_is_an_error_not_a_score() {
        let mut index = Index::default();
        index.replace(vector_docs());
        let error = index.search("anything", Some(&[1.0, 0.0, 0.0]), 8).unwrap_err();
        assert!(error.contains("dimension mismatch"), "{error}");
    }

    #[test]
    fn replace_drops_the_previous_documents() {
        let mut index = Index::default();
        index.replace(vector_docs());
        index.replace(vec![Document {
            id: "only".into(),
            text: "x".into(),
            vector: Some(vec![1.0, 0.0]),
        }]);
        assert_eq!(index.len(), 1);
        assert_eq!(index.search("anything", Some(&[1.0, 0.0]), 8).unwrap().0[0].id, "only");
    }

    #[test]
    fn a_missing_vector_on_any_document_falls_back_to_lexical() {
        let mut index = Index::default();
        index.replace(vector_docs());
        let mut partial = vector_docs();
        partial[1].vector = None;
        index.replace(partial);
        let (hits, mode) = index.search("alpha", Some(&[1.0, 0.0]), 8).unwrap();
        assert_eq!(mode, Mode::Lexical);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, "a");
    }

    #[test]
    fn lexical_search_ranks_the_document_holding_the_term() {
        let mut index = Index::default();
        index.replace(text_docs());
        let (hits, mode) = index.search("parse_chunks", None, 8).unwrap();
        assert_eq!(mode, Mode::Lexical);
        assert_eq!(hits.len(), 1, "{hits:?}");
        assert_eq!(hits[0].id, "parser");
        assert!(hits[0].score > 0.0);
    }

    #[test]
    fn lexical_search_prefers_the_rarer_term_and_honors_k() {
        let mut index = Index::default();
        index.replace(text_docs());
        let (hits, _) = index.search("fn noop", None, 8).unwrap();
        assert_eq!(hits[0].id, "empty");
        assert_eq!(hits.len(), 3);
        let (two, _) = index.search("fn noop", None, 2).unwrap();
        assert_eq!(two.len(), 2);
    }

    #[test]
    fn lexical_search_reports_nothing_for_a_query_with_no_terms() {
        let mut index = Index::default();
        index.replace(text_docs());
        assert!(index.search("::/--", None, 8).unwrap().0.is_empty());
    }

    #[test]
    fn lexical_search_ignores_case_and_punctuation() {
        let mut index = Index::default();
        index.replace(text_docs());
        let (hits, _) = index.search("Parse  CHUNKS!", None, 8).unwrap();
        assert_eq!(hits[0].id, "parser");
    }
}
