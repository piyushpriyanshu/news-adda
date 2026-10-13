from datetime import timedelta

import pytest

from newsadda.classify import Classifier
from newsadda.dedup import Clusterer
from newsadda.entities import EntityIndex
from newsadda.timeutil import utcnow

SAME_STORY = [
    ("अखिलेश यादव का योगी सरकार पर हमला, कहा- बीजेपी ने किसानों को धोखा दिया",
     "अखिलेश यादव बोले- भाजपा सरकार ने किसानों के साथ धोखा किया"),
    ("आजमगढ़ में सपा विधायक ने दिया इस्तीफा",
     "आजमगढ़: सपा विधायक का इस्तीफा, पार्टी को झटका"),
    ("चुनाव आयोग ने मिल्कीपुर उपचुनाव की तारीख घोषित की",
     "मिल्कीपुर उपचुनाव की तारीख का ऐलान, चुनाव आयोग ने जारी किया कार्यक्रम"),
]
DIFFERENT_STORY = [
    ("अखिलेश यादव बोले- भाजपा सरकार ने किसानों के साथ धोखा किया",
     "अखिलेश यादव ने कन्नौज में कार्यकर्ताओं के साथ बैठक की"),
    ("लखनऊ में सड़क हादसा, दो की मौत",
     "लखनऊ में भाजपा की प्रदेश कार्यसमिति बैठक"),
    ("मायावती ने कांग्रेस पर साधा निशाना",
     "मायावती ने भाजपा पर साधा निशाना"),
]


@pytest.fixture(scope="module")
def clf():
    return Classifier(EntityIndex())


def _cluster(clf, a, b, same_source=False):
    now = utcnow()
    cl = Clusterer()
    ea = clf.classify(a, None, up_section=True).entities
    cl.add(cl.make_candidate("a", "a", "s1", now - timedelta(minutes=10), a, ea))
    eb = clf.classify(b, None, up_section=True).entities
    cid, score = cl.assign(b, eb, "s1" if same_source else "s2", now)
    return cid, score


@pytest.mark.parametrize("a,b", SAME_STORY)
def test_same_story_grouped(clf, a, b):
    cid, score = _cluster(clf, a, b)
    assert cid == "a", f"expected grouping, score={score:.2f}"


@pytest.mark.parametrize("a,b", DIFFERENT_STORY)
def test_different_story_not_grouped(clf, a, b):
    cid, score = _cluster(clf, a, b)
    assert cid is None, f"wrongly grouped, score={score:.2f}"


def test_same_source_needs_near_identical(clf):
    a, b = SAME_STORY[0]
    assert _cluster(clf, a, b, same_source=True)[0] is None
    assert _cluster(clf, a, a + " (अपडेट)", same_source=True)[0] == "a"


def test_old_items_outside_window_not_grouped(clf):
    a = SAME_STORY[1][0]
    cl = Clusterer()
    ents = clf.classify(a, None, up_section=True).entities
    cl.add(cl.make_candidate("a", "a", "s1", utcnow() - timedelta(hours=40), a, ents))
    assert cl.assign(a, ents, "s2", utcnow())[0] is None
