import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { ingestRepo, askQuestion } from './api';

const SAMPLE_REPO = 'https://github.com/spring-guides/gs-rest-service.git';
const SAMPLE_QUESTIONS = [
  'What does this repository do?',
  'Where is the main entry point?',
  'How is the REST controller implemented?',
];

export default function App() {
  const [repoUrl, setRepoUrl] = useState('');
  const [branch, setBranch] = useState('');
  const [ingesting, setIngesting] = useState(false);
  const [ingestResult, setIngestResult] = useState(null);
  const [ingestError, setIngestError] = useState('');

  const [question, setQuestion] = useState('');
  const [asking, setAsking] = useState(false);
  const [answer, setAnswer] = useState(null);
  const [queryError, setQueryError] = useState('');

  const repoId = ingestResult?.repoId ?? null;

  async function handleIngest(e) {
    e.preventDefault();
    if (!repoUrl.trim()) return;
    setIngesting(true);
    setIngestError('');
    setIngestResult(null);
    setAnswer(null);
    setQueryError('');
    try {
      const result = await ingestRepo(repoUrl.trim(), branch.trim());
      setIngestResult(result);
    } catch (err) {
      setIngestError(err.message);
    } finally {
      setIngesting(false);
    }
  }

  async function handleAsk(e) {
    e.preventDefault();
    if (!question.trim() || !repoId) return;
    setAsking(true);
    setQueryError('');
    setAnswer(null);
    try {
      const result = await askQuestion(question.trim(), repoId);
      setAnswer(result);
    } catch (err) {
      setQueryError(err.message);
    } finally {
      setAsking(false);
    }
  }

  return (
    <div className="page">
      <header className="hero">
        <h1>Repo Intelligence</h1>
        <p className="tagline">
          A GitHub Copilot <code>@workspace</code>-style codebase Q&amp;A assistant. Index any public
          GitHub repository, then ask questions and get grounded, citation-backed answers.
        </p>
      </header>

      <section className="card">
        <div className="card-head">
          <span className="step">1</span>
          <h2>Index a repository</h2>
        </div>
        <form onSubmit={handleIngest} className="form">
          <input
            type="url"
            className="input"
            placeholder="https://github.com/owner/repo.git"
            value={repoUrl}
            onChange={(e) => setRepoUrl(e.target.value)}
            required
          />
          <input
            type="text"
            className="input input-sm"
            placeholder="branch (optional)"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
          />
          <button type="submit" className="btn" disabled={ingesting}>
            {ingesting ? 'Indexing…' : 'Index Repository'}
          </button>
        </form>
        <button
          type="button"
          className="link"
          onClick={() => setRepoUrl(SAMPLE_REPO)}
          disabled={ingesting}
        >
          Try a sample repo
        </button>

        {ingesting && <p className="muted">Cloning, chunking with tree-sitter, and embedding… this can take a moment.</p>}
        {ingestError && <p className="error">⚠ {ingestError}</p>}
        {ingestResult && (
          <div className="badge-row">
            <span className="badge ok">Indexed ✓</span>
            <span className="badge">repo: {ingestResult.repoId}</span>
            <span className="badge">{ingestResult.filesIndexed} files</span>
            <span className="badge">{ingestResult.chunksIndexed} chunks</span>
          </div>
        )}
      </section>

      <section className={`card ${repoId ? '' : 'disabled'}`}>
        <div className="card-head">
          <span className="step">2</span>
          <h2>Ask a question</h2>
        </div>
        {!repoId && <p className="muted">Index a repository first to enable questions.</p>}
        <form onSubmit={handleAsk} className="form">
          <input
            type="text"
            className="input"
            placeholder="e.g. Where is authentication handled?"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            disabled={!repoId || asking}
            required
          />
          <button type="submit" className="btn" disabled={!repoId || asking}>
            {asking ? 'Thinking…' : 'Ask'}
          </button>
        </form>
        {repoId && (
          <div className="chips">
            {SAMPLE_QUESTIONS.map((q) => (
              <button
                key={q}
                type="button"
                className="chip"
                onClick={() => setQuestion(q)}
                disabled={asking}
              >
                {q}
              </button>
            ))}
          </div>
        )}

        {queryError && <p className="error">⚠ {queryError}</p>}

        {answer && (
          <div className="answer">
            <div className="answer-body">
              <ReactMarkdown>{answer.answer}</ReactMarkdown>
            </div>

            {answer.searchQuery && (
              <p className="search-query">
                <span className="muted">Retrieval query:</span> {answer.searchQuery}
              </p>
            )}

            {answer.citations?.length > 0 && (
              <div className="citations">
                <h3>Citations</h3>
                <ul>
                  {answer.citations.map((c, i) => (
                    <li key={i}>
                      <code>{c.path}</code>
                      {c.symbol && <span className="sym"> :: {c.symbol}</span>}
                      <span className="lines"> (L{c.startLine}–{c.endLine})</span>
                      <span className="score">score {c.score}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </section>

      <footer className="footer">
        Java 21 · Spring Boot · Spring AI · tree-sitter · Pgvector · React
      </footer>
    </div>
  );
}
