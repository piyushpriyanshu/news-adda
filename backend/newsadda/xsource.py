"""Public posts from X via the official X API v2 (recent search).

Requires X_BEARER_TOKEN. Account names and handles come from the API response,
never from local configuration, so nothing about an account is invented.
"""
from __future__ import annotations

import httpx

from .parser import RawItem
from .timeutil import parse_feed_time

SEARCH_URL = "https://api.x.com/2/tweets/search/recent"


def build_query(handles: list[str]) -> str:
    froms = " OR ".join(f"from:{h}" for h in handles)
    return f"({froms}) -is:retweet"


def parse_x_response(payload: dict) -> list[RawItem]:
    users = {u["id"]: u for u in payload.get("includes", {}).get("users", [])}
    out: list[RawItem] = []
    for t in payload.get("data", []) or []:
        user = users.get(t.get("author_id"))
        if not user:
            continue  # without a resolved account we cannot attribute the post
        text = (t.get("note_tweet") or {}).get("text") or t.get("text") or ""
        refs = {r.get("type") for r in t.get("referenced_tweets", []) or []}
        published, _ = parse_feed_time(t.get("created_at"))
        context = None
        if "quoted" in refs:
            context = "quote"
        elif "replied_to" in refs:
            context = "reply"
        item = RawItem(
            url=f"https://x.com/{user['username']}/status/{t['id']}",
            title=text.strip(),
            description=None,
            published_at=published,
            published_raw=t.get("created_at"),
            updated_at=None,
            tz_assumed=False,
            publisher=f"{user.get('name', '')} (@{user['username']})",
            author=user.get("name"),
            handle=user["username"],
            post_context=context,
        )
        out.append(item)
    return out


async def fetch_x(client: httpx.AsyncClient, src, state: dict, settings):
    from .fetcher import FetchResult

    if not settings.x_bearer_token:
        return FetchResult(source=src, error="x_token_missing")
    params = {
        "query": build_query(src.handles),
        "max_results": "50",
        "tweet.fields": "created_at,author_id,referenced_tweets,lang,note_tweet",
        "expansions": "author_id",
        "user.fields": "name,username,verified",
    }
    if state.get("etag"):  # since_id stored in the etag column for this source
        params["since_id"] = state["etag"]
    res = FetchResult(source=src, url=SEARCH_URL)
    try:
        resp = await client.get(
            SEARCH_URL, params=params, headers={"Authorization": f"Bearer {settings.x_bearer_token}"}
        )
    except httpx.HTTPError as exc:
        res.error = f"network: {type(exc).__name__}"
        return res
    res.http_status = resp.status_code
    if resp.status_code != 200:
        res.error = f"X API HTTP {resp.status_code}"
        if resp.status_code == 429:
            reset = resp.headers.get("x-rate-limit-reset")
            if reset and reset.isdigit():
                import time

                res.extra_state["retry_after"] = max(int(reset) - int(time.time()), 60)
        return res
    payload = resp.json()
    res.items = parse_x_response(payload)
    res.ok = True
    newest = (payload.get("meta") or {}).get("newest_id")
    res.extra_state = {"etag": newest or state.get("etag"), "active_url": SEARCH_URL}
    return res
