"""Shared local-Ollama summarizer with map-reduce for long texts.

A single LLM call can't digest a huge transcript/chat (17MB!). For long input we
summarize it in chunks, then summarize those partial summaries into the final
5W1H record. Very long inputs keep the most recent portion (chats are
chronological) and note that older history was truncated.
"""

import json
import os
import urllib.request

CHUNK = int(os.environ.get("SUMMARY_CHUNK_CHARS", "35000"))
MAX_TOTAL = int(os.environ.get("SUMMARY_MAX_CHARS", "400000"))

CHUNK_PROMPT = (
    "다음은 긴 대화/회의 기록의 일부입니다. 한국어로 핵심만 불릿으로 요약하세요. "
    "인물·날짜·숫자·결정·약속 위주로 적고, 없는 내용은 지어내지 마세요:\n\n"
)


def _generate(prompt, model, host, timeout=1800):
    body = json.dumps({"model": model, "prompt": prompt, "stream": False})
    req = urllib.request.Request(
        host.rstrip("/") + "/api/generate",
        data=body.encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8")).get("response", "").strip()


def summarize(text, final_prompt, model, host, logfn=None):
    """Summarize `text` using `final_prompt` as the output format instruction.
    Short text -> one pass. Long text -> map-reduce."""
    log = logfn or (lambda *a: None)

    if len(text) <= CHUNK:
        return _generate(final_prompt + text, model, host)

    truncated = len(text) > MAX_TOTAL
    if truncated:
        text = text[-MAX_TOTAL:]  # keep the most recent portion

    chunks = [text[i:i + CHUNK] for i in range(0, len(text), CHUNK)]
    partials = []
    for idx, chunk in enumerate(chunks, 1):
        log(f"    청크 {idx}/{len(chunks)} 요약…")
        partials.append(_generate(CHUNK_PROMPT + chunk, model, host))

    combined = "\n".join(partials)
    result = _generate(final_prompt + combined, model, host)
    if truncated:
        result += "\n\n> (참고: 기록이 매우 길어 최근 부분 위주로 요약했습니다.)"
    return result
