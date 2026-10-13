"""Hindi/English text helpers: cleaning, normalisation, tokens, light stemming."""
from __future__ import annotations

import html
import re
import unicodedata

# Nukta letters (precomposed) -> base letter; standalone nukta removed.
_NUKTA_MAP = {
    "क़": "क",  # क़ -> क
    "ख़": "ख",  # ख़ -> ख
    "ग़": "ग",  # ग़ -> ग
    "ज़": "ज",  # ज़ -> ज
    "ड़": "ड",  # ड़ -> ड
    "ढ़": "ढ",  # ढ़ -> ढ
    "फ़": "फ",  # फ़ -> फ
    "य़": "य",  # य़ -> य
}
_NUKTA = "़"
_CHANDRABINDU = "ँ"
_ANUSVARA = "ं"
_ZW = re.compile("[​‌‍﻿]")

# A "word" is a run of Devanagari letters/marks or Latin letters/digits.
_TOKEN = re.compile(r"[ऀ-ॣ०-ॿ]+|[A-Za-z0-9]+")
_TAG = re.compile(r"<[^>]+>")
_WS = re.compile(r"\s+")

# Common Hindi function words and news filler; ignored for similarity.
STOPWORDS = frozenset(
    """
    का के की को में से पर ने है हैं था थे थी हो होगा होगी होंगे और या भी तो ही
    एक यह वह ये वे इस उस इन उन जो कि लिए साथ बाद तक अब कर करने करें किया किए
    गया गई गए रहा रही रहे दिया दी दिए लेकर बीच बताया कहा कहना बोले बोली
    अपने अपनी अपना सबसे बड़ा बड़ी बड़े नहीं न जा जाएगा जाएगी हुआ हुई हुए
    खबर न्यूज़ न्यूज live लाइव अपडेट update video वीडियो photos फोटो देखें पढ़ें
    the a an of in on for to and or is are was were with by at from as
    """.split()
)

# Light suffix stripper (after Ramanathan & Rao 2003), longest first.
_SUFFIXES = sorted(
    """
    ाएंगी ाएंगे ाऊंगी ाऊंगा ाइयाँ ाइयों ाइयां
    ियों ियां ाओं ाएं ुओं ुएं ुआं ेंगे ेंगी ाया ाई ाए ाती ाते ाना ाने ानी
    ों ें ाँ ां ीं ूं ा ी े ि ु ू ो
    """.split(),
    key=len,
    reverse=True,
)


def strip_html(text: str | None) -> str:
    if not text:
        return ""
    # Some feeds escape their HTML (&lt;p&gt;), so strip tags again after unescaping.
    text = _TAG.sub(" ", html.unescape(_TAG.sub(" ", text)))
    text = _ZW.sub("", text)
    return _WS.sub(" ", text).strip()


def normalize(text: str | None) -> str:
    """Fold case, nukta and chandrabindu so spelling variants compare equal."""
    if not text:
        return ""
    text = unicodedata.normalize("NFC", text)
    text = "".join(_NUKTA_MAP.get(ch, ch) for ch in text)
    text = text.replace(_NUKTA, "").replace(_CHANDRABINDU, _ANUSVARA)
    text = _ZW.sub("", text)
    return text.lower()


def tokens(text: str | None) -> list[str]:
    return _TOKEN.findall(normalize(text))


def stem(word: str) -> str:
    if len(word) <= 3 or word.isascii():
        return word
    for suf in _SUFFIXES:
        if word.endswith(suf) and len(word) - len(suf) >= 2:
            return word[: -len(suf)]
    return word


def content_terms(text: str | None) -> set[str]:
    """Stemmed tokens without stopwords: the basis for story similarity."""
    out = set()
    for t in tokens(text):
        if t in STOPWORDS or len(t) < 2 or t.isdigit():
            continue
        out.add(stem(t))
    return out


def char_ngrams(text: str | None, n: int = 3) -> set[str]:
    s = " ".join(t for t in tokens(text) if t not in STOPWORDS)
    if len(s) < n:
        return {s} if s else set()
    return {s[i : i + n] for i in range(len(s) - n + 1)}


def jaccard(a: set, b: set) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


def padded(text: str | None) -> str:
    """Token string with sentinel spaces, for whole-phrase alias matching."""
    return " " + " ".join(tokens(text)) + " "


def phrase_in(phrase_padded: str, text_padded: str) -> bool:
    return phrase_padded in text_padded


_SENTENCE_END = re.compile(r"(?<=[।\.\?!|])\s+")


def short_summary(description: str | None, title: str | None, limit: int = 240) -> str | None:
    """First sentence(s) of a feed description, if it adds something to the title.

    Never generates new text: it only trims what the publisher supplied.
    """
    text = strip_html(description)
    if not text:
        return None
    # Drop trailing "Read more" style boilerplate.
    text = re.sub(r"\s*(और पढ़ें|पूरी खबर पढ़ें|Read more|Continue reading)\s*.*$", "", text, flags=re.I)
    if len(text) < 25:
        return None
    nt, nd = normalize(title).strip(), normalize(text).strip()
    if nt and (nd == nt or nd.startswith(nt) and len(nd) - len(nt) < 20):
        return None
    if jaccard(content_terms(title), content_terms(text)) > 0.85:
        return None
    parts = _SENTENCE_END.split(text)
    out = ""
    for p in parts:
        if not out:
            out = p
        elif len(out) + 1 + len(p) <= limit:
            out = out + " " + p
        else:
            break
    if len(out) > limit:
        cut = out[:limit]
        sp = cut.rfind(" ")
        out = (cut[:sp] if sp > limit * 0.6 else cut).rstrip(" ,;:-") + "…"
    return out
