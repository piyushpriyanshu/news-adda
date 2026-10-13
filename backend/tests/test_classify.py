import pytest

from newsadda.classify import Classifier
from newsadda.entities import EntityIndex


@pytest.fixture(scope="module")
def clf():
    return Classifier(EntityIndex())


def test_entities_districts_and_spelling_variants(clf):
    c = clf.classify("चंद्रशेखर आज़ाद ने सहारनपुर में बसपा पर साधा निशाना", None, up_section=False)
    assert "chandrashekhar" in c.entities["leaders"]
    assert "saharanpur" in c.entities["districts"]
    assert "bsp" in c.entities["parties"] and "asp" in c.entities["parties"]
    assert c.is_up


def test_surname_alone_does_not_match_leader(clf):
    c = clf.classify("पाठक परिवार के घर चोरी", None, up_section=True)
    assert "brajesh_pathak" not in c.entities["leaders"]


def test_non_up_item_from_national_feed_is_dropped(clf):
    c = clf.classify("महाराष्ट्र में बारिश से जनजीवन प्रभावित", None, up_section=False)
    assert not c.is_up


def test_up_marker_in_national_feed_is_kept(clf):
    assert clf.classify("UP: बिजली दरों पर फैसला", None, up_section=False).is_up
    assert clf.classify("उत्तर प्रदेश में नई नीति", None, up_section=False).is_up


def test_resignation_of_politician_is_breaking_with_reasons(clf):
    c = clf.classify("सपा विधायक ने दिया इस्तीफा, भाजपा में शामिल होने की चर्चा", None, up_section=True)
    assert c.breaking
    assert "resignation" in c.reasons
    assert "election" in c.categories or "politics" in c.categories


def test_non_political_crime_is_not_breaking(clf):
    c = clf.classify("मेरठ में चोरी के आरोप में युवक गिरफ्तार", None, up_section=True)
    assert not c.breaking
    assert "crime" in c.categories or "legal" in c.categories


def test_allegation_is_labelled_and_statement_detected(clf):
    c = clf.classify("अखिलेश यादव बोले- सरकार ने भर्ती में किया घोटाला, लगाए गंभीर आरोप", None, up_section=True)
    assert "allegation" in c.labels
    assert c.statement and c.statement_type == "allegation"
    assert "controversy" in c.categories


def test_counter_statement_type(clf):
    c = clf.classify("केशव प्रसाद मौर्य का अखिलेश पर पलटवार", None, up_section=True)
    assert c.statement_type == "response"
    assert "counter" in c.labels


def test_legal_status_reflects_report_wording_only(clf):
    c = clf.classify("धनंजय सिंह पर एफआईआर दर्ज, पुलिस जांच में जुटी", None, up_section=True)
    assert "dhananjay_singh" in c.entities["figures"]
    assert "fir" in c.legal_status and "investigation" in c.legal_status
    assert "convicted" not in c.legal_status


def test_official_statement_label(clf):
    c = clf.classify("चुनाव आयोग ने उपचुनाव की तारीखों का ऐलान किया", None, up_section=True)
    assert "official" in c.labels
    assert c.breaking  # election commission + bypoll in UP politics


def test_x_post_is_statement(clf):
    c = clf.classify("आज लखनऊ में कार्यकर्ताओं से मिला।", None, up_section=True, kind="post")
    assert c.statement
