"""Entity lists (leaders, regional figures, parties, districts, seats) and matching."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .textutil import padded

DATA_DIR = Path(__file__).parent / "data"


@dataclass
class Entity:
    id: str
    name_hi: str
    aliases: list[str]
    extra: dict = field(default_factory=dict)
    _padded: list[str] = field(default_factory=list, repr=False)

    def __post_init__(self):
        names = set(self.aliases) | {self.name_hi}
        # Longest aliases first so the most specific phrase is checked first.
        self._padded = sorted({padded(a) for a in names if a.strip()}, key=len, reverse=True)

    def matches(self, text_padded: str) -> bool:
        return any(p in text_padded for p in self._padded if p.strip())

    def public(self) -> dict:
        d = {"id": self.id, "name_hi": self.name_hi}
        d.update(self.extra)
        return d


class EntityIndex:
    def __init__(self, path: Path | str | None = None):
        path = Path(path) if path else DATA_DIR / "entities.yaml"
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.leaders = [self._mk(e, ("name", "party", "role_hi")) for e in raw.get("leaders", [])]
        self.figures = [self._mk(e, ("name", "area_hi")) for e in raw.get("regional_figures", [])]
        self.parties = [self._mk(e, ()) for e in raw.get("parties", [])]
        self.districts = [self._mk(e, ("key",)) for e in raw.get("districts", [])]
        self.seats = [self._mk(e, ()) for e in raw.get("seats", [])]

    @staticmethod
    def _mk(e: dict, extra_keys) -> Entity:
        extra = {k: e[k] for k in extra_keys if k in e}
        return Entity(id=e["id"], name_hi=e["name_hi"], aliases=list(e.get("aliases", [])), extra=extra)

    @staticmethod
    def _match(group: list[Entity], text_padded: str) -> list[str]:
        return [e.id for e in group if e.matches(text_padded)]

    def tag(self, text: str) -> dict[str, list[str]]:
        p = padded(text)
        leaders = self._match(self.leaders, p)
        parties = self._match(self.parties, p)
        # A matched leader implies their party, so party filters find them.
        by_id = {e.id: e for e in self.leaders}
        for lid in leaders:
            party = by_id[lid].extra.get("party")
            if party and party != "other" and party not in parties:
                parties.append(party)
        return {
            "leaders": leaders,
            "figures": self._match(self.figures, p),
            "parties": parties,
            "districts": self._match(self.districts, p),
            "seats": self._match(self.seats, p),
        }

    def public(self) -> dict:
        return {
            "leaders": [e.public() for e in self.leaders],
            "figures": [e.public() for e in self.figures],
            "parties": [e.public() for e in self.parties if e.id != "other"],
            "districts": [e.public() for e in self.districts],
            "seats": [e.public() for e in self.seats],
        }
