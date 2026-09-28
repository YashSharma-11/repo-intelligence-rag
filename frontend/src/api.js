// Thin API client for the Repo Intelligence backend.
// Uses relative URLs so the Vite dev proxy (or same-origin prod build) handles routing.

async function postJson(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { message: text };
  }
  if (!res.ok) {
    throw new Error(data.message || `Request failed (${res.status})`);
  }
  return data;
}

export function ingestRepo(repoUrl, branch) {
  const body = { repoUrl };
  if (branch) body.branch = branch;
  return postJson('/api/ingest', body);
}

export function askQuestion(question, repoId) {
  const body = { question };
  if (repoId) body.repoId = repoId;
  return postJson('/api/query', body);
}
