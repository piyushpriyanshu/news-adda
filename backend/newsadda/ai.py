"""Optional AI rewrite of publisher-supplied descriptions into short Hindi summaries.

Off unless NEWSADDA_AI_SUMMARY=1 and ANTHROPIC_API_KEY are set. It only works
from text the publisher already provided (title + description), never invents
facts, and the app labels such summaries. Aggregation works fully without it.
"""
from __future__ import annotations

import logging

import httpx

from .store import Store

log = logging.getLogger("newsadda.ai")

PROMPT = (
    "नीचे एक समाचार का शीर्षक और प्रकाशक का विवरण है। केवल इन्हीं में दी गई जानकारी से "
    "एक या दो वाक्यों (अधिकतम 45 शब्द) का तटस्थ हिंदी सारांश लिखें। कोई नया तथ्य, नाम, संख्या "
    "या निष्कर्ष न जोड़ें। आरोप को आरोप ही लिखें और बयान को बोलने वाले से जोड़ें। "
    "केवल सारांश लौटाएं।\n\nशीर्षक: {title}\nविवरण: {desc}"
)


async def summarize_pending(store: Store, settings) -> int:
    rows = store.db.execute(
        "SELECT id, title, summary FROM items WHERE summary IS NOT NULL AND summary_origin='publisher' "
        "AND summary GLOB '*[A-Za-z][A-Za-z][A-Za-z]*' ORDER BY first_seen_at DESC LIMIT ?",
        (settings.ai_max_per_run,),
    ).fetchall()
    if not rows:
        return 0
    done = 0
    async with httpx.AsyncClient(timeout=30) as client:
        for r in rows:
            try:
                resp = await client.post(
                    "https://api.anthropic.com/v1/messages",
                    headers={
                        "x-api-key": settings.ai_api_key,
                        "anthropic-version": "2023-06-01",
                        "content-type": "application/json",
                    },
                    json={
                        "model": settings.ai_model,
                        "max_tokens": 200,
                        "messages": [{"role": "user", "content": PROMPT.format(title=r["title"], desc=r["summary"])}],
                    },
                )
                resp.raise_for_status()
                text = "".join(b.get("text", "") for b in resp.json().get("content", [])).strip()
                if text:
                    store.update_item(r["id"], summary=text[:400], summary_origin="ai")
                    done += 1
            except Exception as exc:
                log.warning("AI summary failed for %s: %s", r["id"], exc)
                break  # service trouble: stop for this run, keep publisher text
    return done
