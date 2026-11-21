//! In-memory cosine index. The scoring mirrors fennec_core.tools.semantic_search
//! so a query ranks the same way it does through the Python agent.

use serde::Serialize;

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Hit {
    pub id: String,
    pub score: f64,
}

#[derive(Default)]
pub struct Index {
    docs: Vec<(String, Vec<f64>)>,
}

impl Index {
    pub fn replace(&mut self, docs: Vec<(String, Vec<f64>)>) {
        self.docs = docs;
    }

    pub fn len(&self) -> usize {
        self.docs.len()
    }

    pub fn is_empty(&self) -> bool {
        self.docs.is_empty()
    }

    pub fn search(&self, query: &[f64], k: usize) -> Result<Vec<Hit>, String> {
        let mut hits = Vec::with_capacity(self.docs.len());
        for (position, (id, vector)) in self.docs.iter().enumerate() {
            if vector.len() != query.len() {
                return Err(format!(
                    "embedding dimension mismatch: document {id} has {} values, query has {}",
                    vector.len(),
                    query.len()
                ));
            }
            hits.push((position, Hit { id: id.clone(), score: cosine(query, vector) }));
        }
        hits.sort_by(|a, b| b.1.score.total_cmp(&a.1.score).then(a.0.cmp(&b.0)));
        hits.truncate(k);
        Ok(hits.into_iter().map(|(_, hit)| hit).collect())
    }
}

pub fn cosine(a: &[f64], b: &[f64]) -> f64 {
    let dot: f64 = a.iter().zip(b).map(|(x, y)| x * y).sum();
    let na: f64 = a.iter().map(|x| x * x).sum::<f64>().sqrt();
    let nb: f64 = b.iter().map(|y| y * y).sum::<f64>().sqrt();
    dot / (na * nb + 1e-9)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn docs() -> Vec<(String, Vec<f64>)> {
        vec![
            ("a".into(), vec![1.0, 0.0]),
            ("b".into(), vec![0.0, 1.0]),
            ("c".into(), vec![1.0, 1.0]),
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
        index.replace(docs());
        let hits = index.search(&[1.0, 0.0], 8).unwrap();
        assert_eq!(hits[0].id, "a");
        assert!((hits[0].score - 1.0).abs() < 1e-6);
        assert_eq!(hits[1].id, "c");
        assert_eq!(hits[2].id, "b");
        assert_eq!(index.search(&[1.0, 0.0], 1).unwrap().len(), 1);
    }

    #[test]
    fn ties_keep_document_order() {
        let mut index = Index::default();
        index.replace(vec![
            ("first".into(), vec![1.0]),
            ("second".into(), vec![1.0]),
        ]);
        let hits = index.search(&[1.0], 2).unwrap();
        assert_eq!(hits[0].id, "first");
        assert_eq!(hits[1].id, "second");
    }

    #[test]
    fn empty_index_returns_no_hits() {
        let index = Index::default();
        assert!(index.is_empty());
        assert!(index.search(&[1.0], 8).unwrap().is_empty());
    }

    #[test]
    fn dimension_mismatch_is_an_error_not_a_score() {
        let mut index = Index::default();
        index.replace(docs());
        let error = index.search(&[1.0, 0.0, 0.0], 8).unwrap_err();
        assert!(error.contains("dimension mismatch"), "{error}");
    }

    #[test]
    fn replace_drops_the_previous_documents() {
        let mut index = Index::default();
        index.replace(docs());
        index.replace(vec![("only".into(), vec![1.0, 0.0])]);
        assert_eq!(index.len(), 1);
        assert_eq!(index.search(&[1.0, 0.0], 8).unwrap()[0].id, "only");
    }
}