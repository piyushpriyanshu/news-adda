"""Rule-based, explainable classification of UP news items.

Every decision records a human-readable reason (in Hindi) so the app can show
*why* a story was marked breaking. No popularity or ideology signals are used.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .entities import EntityIndex
from .textutil import padded


def _kw(*phrases: str) -> list[str]:
    return [padded(p) for p in phrases]


def _hits(kws: list[str], text_padded: str) -> list[str]:
    return [k.strip() for k in kws if k in text_padded]


UP_MARKERS = _kw("उत्तर प्रदेश", "यूपी", "Uttar Pradesh", "प्रदेश सरकार", "योगी सरकार")
_UP_LATIN = re.compile(r"\bUP\b")

CATEGORIES: dict[str, tuple[str, list[str]]] = {
    "politics": ("राजनीति", _kw(
        "राजनीति", "राजनीतिक", "सियासत", "सियासी", "विधायक", "सांसद", "मंत्री", "राज्यमंत्री",
        "मुख्यमंत्री", "उपमुख्यमंत्री", "विपक्ष", "रैली", "विधानसभा", "लोकसभा", "विधान परिषद",
        "एमएलसी", "प्रदेश अध्यक्ष", "जिलाध्यक्ष", "योगी सरकार", "गठबंधन", "politics", "MLA",
    )),
    "controversy": ("राजनीतिक विवाद", _kw(
        "आरोप", "आरोपों", "पलटवार", "निशाना", "निशाने", "हमला बोला", "घेरा", "विवाद", "विवादित",
        "बयानबाजी", "घमासान", "तंज", "कटाक्ष", "माफी", "सफाई", "हंगामा", "साजिश", "घोटाला",
        "भ्रष्टाचार", "भड़के", "भड़कीं", "आपत्तिजनक", "अपमान", "controversy", "allegation",
    )),
    "election": ("चुनाव", _kw(
        "चुनाव", "चुनावी", "उपचुनाव", "मतदान", "वोट", "वोटिंग", "टिकट", "प्रत्याशी", "उम्मीदवार",
        "नामांकन", "चुनाव आयोग", "निर्वाचन", "मतदाता सूची", "एसआईआर", "दलबदल", "पाला बदला",
        "बीएलओ", "पंचायत चुनाव", "election", "bypoll",
    )),
    "legal": ("कोर्ट-कानून", _kw(
        "कोर्ट", "अदालत", "हाईकोर्ट", "हाई कोर्ट", "सुप्रीम कोर्ट", "फैसला", "सजा", "जमानत", "बरी",
        "एफआईआर", "मुकदमा", "गिरफ्तार", "गिरफ्तारी", "चार्जशीट", "आरोप पत्र", "ईडी", "सीबीआई",
        "छापा", "छापेमारी", "कुर्क", "कुर्की", "गैंगस्टर", "court", "arrest", "FIR",
    )),
    "administration": ("प्रशासन", _kw(
        "तबादला", "तबादले", "निलंबित", "बर्खास्त", "डीएम", "आईएएस", "आईपीएस", "कैबिनेट",
        "शासनादेश", "अधिसूचना", "आदेश जारी", "बुलडोजर", "योजना", "बजट", "प्रशासन",
    )),
    "protest": ("विरोध-प्रदर्शन", _kw(
        "प्रदर्शन", "धरना", "आंदोलन", "घेराव", "अनशन", "विरोध प्रदर्शन", "protest",
    )),
    "crime": ("अपराध", _kw(
        "हत्या", "लूट", "दुष्कर्म", "अपहरण", "गोली मारकर", "मर्डर", "चोरी", "एनकाउंटर", "मुठभेड़",
        "बदमाश", "murder",
    )),
}
CATEGORY_NAMES = {k: v[0] for k, v in CATEGORIES.items()} | {"other": "अन्य"}

# Breaking signals in the *headline*, with weights and the reason shown.
BREAKING_SIGNALS: list[tuple[list[str], int, str]] = [
    (_kw("ब्रेकिंग", "breaking", "बड़ी खबर", "बिग ब्रेकिंग"), 3, "प्रकाशक ने ब्रेकिंग बताया"),
    (_kw("इस्तीफा", "इस्तीफे", "त्यागपत्र", "resigns"), 3, "इस्तीफा"),
    (_kw("गिरफ्तार", "गिरफ्तारी", "हिरासत में", "arrested"), 3, "गिरफ्तारी"),
    (_kw("निलंबित", "बर्खास्त", "निष्कासित", "पार्टी से निकाला", "suspended", "expelled"), 3, "निलंबन/निष्कासन"),
    (_kw("में शामिल", "दलबदल", "पाला बदला", "थामा दामन", "दामन थामा", "joins"), 2, "दलबदल/पार्टी में शामिल"),
    (_kw("टिकट", "प्रत्याशी घोषित", "उम्मीदवारों की सूची", "प्रत्याशियों की सूची", "उम्मीदवार घोषित"), 2, "टिकट/प्रत्याशी घोषणा"),
    (_kw("चुनाव आयोग", "निर्वाचन आयोग", "Election Commission"), 2, "चुनाव आयोग"),
    (_kw("उपचुनाव", "bypoll"), 2, "उपचुनाव"),
    (_kw("हाईकोर्ट", "हाई कोर्ट", "सुप्रीम कोर्ट", "High Court", "Supreme Court"), 2, "उच्च/सर्वोच्च न्यायालय"),
    (_kw("सजा", "दोषी करार", "बरी", "जमानत", "convicted", "acquitted"), 2, "अदालती फैसला"),
    (_kw("छापा", "छापेमारी", "ईडी", "सीबीआई", "raid"), 2, "जांच एजेंसी की कार्रवाई"),
    (_kw("निधन", "passes away"), 2, "निधन"),
    (_kw("गठबंधन टूटा", "गठबंधन", "alliance"), 1, "गठबंधन"),
    (_kw("कैबिनेट विस्तार", "मंत्रिमंडल विस्तार", "शपथ"), 2, "मंत्रिमंडल/शपथ"),
    (_kw("हंगामा", "बवाल", "लाठीचार्ज"), 1, "हंगामा"),
]
BREAKING_THRESHOLD = 3
# Bump when rules change: stored items are then re-labelled on the next run.
RULES_VERSION = "2"
_LIVE = re.compile(r"\blive\b|लाइव", re.I)

ALLEGATION = _kw("आरोप", "आरोपों", "आरोपित", "आरोप लगाया", "accused", "alleges", "alleged")
COUNTER = _kw("पलटवार", "जवाबी हमला", "जवाब दिया", "पलटवार किया", "पलटवार करते", "hits back")
OFFICIAL = _kw(
    "सरकार ने", "शासनादेश", "आदेश जारी", "अधिसूचना जारी", "प्रेस विज्ञप्ति", "आधिकारिक",
    "चुनाव आयोग ने", "आयोग ने", "प्रवक्ता ने", "पुलिस ने बताया", "प्रशासन ने", "official",
)
STATEMENT = _kw(
    "बोले", "बोलीं", "बोला", "कहा", "बयान", "दावा", "तंज", "निशाना", "निशाने", "पलटवार", "हमला बोला",
    "घेरा", "ट्वीट", "पोस्ट", "एक्स पर", "सोशल मीडिया", "प्रेस कॉन्फ्रेंस", "प्रेस कांफ्रेंस",
    "पत्रकार वार्ता", "संबोधित", "says", "said", "slams",
)
ANNOUNCE = _kw("ऐलान", "घोषणा", "घोषित", "announces", "announced")

# Legal status *as mentioned in the report*. Order: most final first.
LEGAL_STATUS: list[tuple[str, list[str]]] = [
    ("दोषसिद्ध", _kw("दोषी करार", "दोषी ठहराया", "सजा सुनाई", "उम्रकैद", "कैद की सजा", "convicted")),
    ("बरी", _kw("बरी", "acquitted")),
    ("जमानत", _kw("जमानत", "bail")),
    ("गिरफ्तारी", _kw("गिरफ्तार", "गिरफ्तारी", "हिरासत में", "arrested")),
    ("आरोप-पत्र", _kw("चार्जशीट", "आरोप पत्र", "आरोपपत्र", "chargesheet")),
    ("एफआईआर", _kw("एफआईआर", "मुकदमा दर्ज", "केस दर्ज", "रिपोर्ट दर्ज", "FIR")),
    ("जांच", _kw("जांच", "ईडी", "सीबीआई", "एसआईटी", "छापा", "probe")),
    ("आरोप", _kw("आरोप", "आरोपों", "आरोपित", "accused")),
]


@dataclass
class Classification:
    is_up: bool
    categories: list[str]
    entities: dict[str, list[str]]
    breaking: bool
    breaking_score: int
    reasons: list[str]
    labels: list[str]
    statement: bool
    statement_type: str | None
    legal_status: list[str] = field(default_factory=list)


class Classifier:
    def __init__(self, entities: EntityIndex):
        self.entities = entities

    def classify(self, title: str, summary: str | None, *, up_section: bool, kind: str = "article",
                 official_source: bool = False) -> Classification:
        body = f"{title} {summary or ''}"
        tp, bp = padded(title), padded(body)
        ents = self.entities.tag(body)
        has_person = bool(ents["leaders"] or ents["figures"])
        political_entity = has_person or bool(ents["parties"])

        up_hit = bool(_hits(UP_MARKERS, bp) or _UP_LATIN.search(body))
        is_up = up_section or up_hit or bool(
            ents["districts"] or ents["seats"] or ents["leaders"] or ents["figures"]
        )

        cats = [cid for cid, (_, kws) in CATEGORIES.items() if _hits(kws, bp)]
        if political_entity and "politics" not in cats:
            cats.insert(0, "politics")
        # Crime stories that touch a political figure are political, not just crime.
        if not cats:
            cats = ["other"]

        reasons: list[str] = []
        score = 0
        live_blog = bool(_LIVE.search(title or ""))
        for kws, weight, reason in BREAKING_SIGNALS:
            if live_blog and reason == "प्रकाशक ने ब्रेकिंग बताया":
                continue  # running live pages always say "breaking"
            if _hits(kws, tp):
                score += weight
                reasons.append(reason)
        political = political_entity or any(c in cats for c in ("politics", "election"))
        if not political and "controversy" in cats:
            # An allegation in an ordinary crime report is not a political controversy.
            cats.remove("controversy")
            if not cats:
                cats = ["other"]
        breaking = political and score >= BREAKING_THRESHOLD
        if political_entity:
            names = [e.name_hi for e in self.entities.leaders if e.id in ents["leaders"]]
            names += [e.name_hi for e in self.entities.figures if e.id in ents["figures"]]
            if names:
                reasons.append("नेता: " + ", ".join(names[:3]))

        labels: list[str] = []
        if _hits(ALLEGATION, bp):
            labels.append("आरोप")
        if _hits(COUNTER, bp):
            labels.append("पलटवार")
        if official_source or _hits(OFFICIAL, bp):
            labels.append("आधिकारिक बयान")

        # Hindi headlines quote with "X बोले- ..." / "X का आरोप- ..." / "X: ...".
        quote_form = re.search(r"^[^:：\-–—]{3,60}(?:[:：]|\s?[-–—]\s)", title or "")
        statement = kind == "post" or (has_person or bool(ents["parties"])) and bool(
            _hits(STATEMENT, tp) or (has_person and _hits(ALLEGATION, tp)) or quote_form
        )
        stype = None
        if statement:
            if _hits(COUNTER, bp):
                stype = "प्रतिक्रिया"
            elif _hits(ALLEGATION, bp):
                stype = "आरोप"
            elif _hits(ANNOUNCE, bp):
                stype = "घोषणा"
            else:
                stype = "बयान/राय"
            if "controversy" not in cats and ("आरोप" in labels or "पलटवार" in labels):
                cats.append("controversy")

        legal = [name for name, kws in LEGAL_STATUS if _hits(kws, bp)]
        return Classification(
            is_up=is_up,
            categories=cats,
            entities=ents,
            breaking=breaking,
            breaking_score=score,
            reasons=reasons,
            labels=labels,
            statement=statement,
            statement_type=stype,
            legal_status=legal,
        )
