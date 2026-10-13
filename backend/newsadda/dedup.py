"""Near-duplicate detection and story grouping.

Two reports join the same story ("cluster") when their headlines are similar
enough. Entity aliases are folded to ids first (बीजेपी = भाजपा = @bjp), then
stemmed word overlap and character-trigram overlap are combined. Grouping is
deliberately conservative: a wrong merge would hide a separate development,
while a missed merge only shows two cards.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from .textutil import char_ngrams, content_terms, jaccard

WINDOW = timedelta(hours=36)


def story_terms(title: str, entities: dict[str, list[str]]) -> set[str]:
    terms = content_terms(title)
    for group in ("leaders", "figures", "parties", "districts", "seats"):
        for eid in entities.get(group, []):
            terms.add("@" + eid)
    return terms


@dataclass
class Candidate:
    item_id: str
    cluster_id: str
    source_id: str
    first_seen: datetime
    terms: set[str]
    grams: set[str]
    key_entities: set[str]
    all_entities: set[str]


def similarity(a_terms, a_grams, b_terms, b_grams) -> tuple[float, float, float]:
    tj = jaccard(a_terms, b_terms)
    gj = jaccard(a_grams, b_grams)
    return 0.6 * tj + 0.4 * gj, tj, gj


def _conflict(a: set[str], b: set[str]) -> bool:
    """Both headlines name a different entity of the same kind (e.g. "Mayawati
    attacks Congress" vs "Mayawati attacks BJP"): different stories."""
    for group in ("leaders", "figures", "parties", "districts", "seats"):
        pa = {e for e in a if e.startswith(group + ":")}
        pb = {e for e in b if e.startswith(group + ":")}
        if pa - pb and pb - pa:
            return True
    return False


def is_same_story(a: Candidate | tuple, b: Candidate, same_source: bool) -> tuple[bool, float]:
    a_terms, a_grams, a_keys, a_all = a if isinstance(a, tuple) else (
        a.terms, a.grams, a.key_entities, a.all_entities)
    score, tj, gj = similarity(a_terms, a_grams, b.terms, b.grams)
    shared_key = bool(a_keys & b.key_entities)
    if _conflict(a_all, b.all_entities) and score < 0.8:
        return False, score
    if same_source:
        # Same outlet: only near-identical headlines (rewrites/updates).
        return score >= 0.72, score
    if score >= 0.55:
        return True, score
    if shared_key and score >= 0.40 and tj >= 0.30:
        return True, score
    return False, score


class Clusterer:
    """Assigns cluster ids to new items against a window of recent items."""

    def __init__(self):
        self.cands: list[Candidate] = []

    @staticmethod
    def key_entities(entities: dict[str, list[str]]) -> set[str]:
        return {f"{g}:{e}" for g in ("leaders", "figures", "seats", "districts") for e in entities.get(g, [])}

    @staticmethod
    def all_entities(entities: dict[str, list[str]]) -> set[str]:
        return {f"{g}:{e}" for g in ("leaders", "figures", "parties", "seats", "districts")
                for e in entities.get(g, [])}

    def add(self, cand: Candidate):
        self.cands.append(cand)

    def assign(self, title: str, entities: dict, source_id: str, now: datetime) -> tuple[str | None, float]:
        terms = story_terms(title, entities)
        grams = char_ngrams(title)
        keys = self.key_entities(entities)
        alls = self.all_entities(entities)
        best, best_score = None, 0.0
        for c in self.cands:
            if now - c.first_seen > WINDOW:
                continue
            ok, score = is_same_story((terms, grams, keys, alls), c, same_source=(c.source_id == source_id))
            if ok and score > best_score:
                best, best_score = c, score
        return (best.cluster_id if best else None), best_score

    def make_candidate(self, item_id, cluster_id, source_id, first_seen, title, entities) -> Candidate:
        return Candidate(
            item_id=item_id,
            cluster_id=cluster_id,
            source_id=source_id,
            first_seen=first_seen,
            terms=story_terms(title, entities),
            grams=char_ngrams(title),
            key_entities=self.key_entities(entities),
            all_entities=self.all_entities(entities),
        )
