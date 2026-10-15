package com.newsadda.app.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.newsadda.app.data.Prefs

/**
 * Every piece of interface text in English and Hindi, side by side.
 * News content (headlines, summaries, posts) is never translated: it is always
 * shown exactly as the publisher wrote it, in Hindi.
 */
data class Str(val hindi: Boolean) {
    private fun t(en: String, hi: String) = if (hindi) hi else en
    private fun pl(n: Int, one: String, many: String) = if (n == 1) one else many

    companion object {
        fun of(ctx: Context) = Str(Prefs.get(ctx).language == "hi")
    }

    // ---- app & sections
    val appName get() = t("News Adda", "न्यूज़ अड्डा")
    val tagline get() = t("Uttar Pradesh politics", "उत्तर प्रदेश राजनीति")
    val home get() = t("Home", "होम")
    val breaking get() = t("Breaking", "ब्रेकिंग न्यूज़")
    val leaders get() = t("Leader tracker", "नेता ट्रैकर")
    val controversy get() = t("Controversies", "राजनीतिक विवाद")
    val seats get() = t("Seats & Bahubalis", "सीट और बाहुबली")
    val statements get() = t("Statements", "नेताओं के बयान")
    val districts get() = t("Districts", "जिला समाचार")
    val saved get() = t("Saved", "सहेजी गई")
    val settings get() = t("Settings", "सेटिंग्स")
    val search get() = t("Search", "खोज")
    val back get() = t("Back", "वापस")
    val updateNow get() = t("Update now", "अभी अपडेट करें")
    val updating get() = t("Updating…", "अपडेट हो रहा…")
    fun updatedAt(time: String, ago: String) = t("Updated $time ($ago)", "अपडेट: $time ($ago)")

    // ---- time
    val months: List<String>
        get() = if (hindi) listOf("जन", "फ़र", "मार्च", "अप्रै", "मई", "जून", "जुल", "अग", "सित", "अक्टू", "नव", "दिस")
        else listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val yesterday get() = t("Yesterday", "कल")
    val justNow get() = t("just now", "अभी")
    fun minAgo(n: Long) = t("$n min ago", "$n मिनट पहले")
    fun hoursAgo(n: Long) = t("$n h ago", "$n घंटे पहले")
    fun daysAgo(n: Long) = t("$n d ago", "$n दिन पहले")
    val notAvailable get() = t("not available", "उपलब्ध नहीं")

    // ---- tags / labels (codes come from the backend)
    val tagBreaking get() = t("Breaking", "ब्रेकिंग")
    val tagNew get() = t("New", "नई खबर")
    val tagXPost get() = t("X post", "X पोस्ट")
    fun multiSource(n: Int) = t("Reported by $n sources", "$n स्रोतों से रिपोर्ट")
    fun label(code: String) = when (code) {
        "allegation" -> t("Allegation", "आरोप")
        "counter" -> t("Counter-attack", "पलटवार")
        "official" -> t("Official statement", "आधिकारिक बयान")
        "awaiting_confirmation" -> t("Awaiting confirmation", "पुष्टि की प्रतीक्षा")
        "multi_source" -> t("Multiple sources", "एकाधिक स्रोत")
        else -> code
    }
    fun statementType(code: String) = when (code) {
        "response" -> t("Response", "प्रतिक्रिया")
        "allegation" -> t("Allegation", "आरोप")
        "announcement" -> t("Announcement", "घोषणा")
        "statement" -> t("Statement", "बयान")
        else -> code
    }
    fun legal(code: String) = when (code) {
        "convicted" -> t("Conviction", "दोषसिद्धि")
        "acquitted" -> t("Acquittal", "बरी")
        "bail" -> t("Bail", "जमानत")
        "arrest" -> t("Arrest", "गिरफ्तारी")
        "chargesheet" -> t("Chargesheet", "आरोप-पत्र")
        "fir" -> t("FIR", "एफआईआर")
        "investigation" -> t("Investigation", "जांच")
        "allegation" -> t("Allegation", "आरोप")
        else -> code
    }
    fun category(code: String) = when (code) {
        "politics" -> t("Politics", "राजनीति")
        "controversy" -> t("Political controversy", "राजनीतिक विवाद")
        "election" -> t("Elections", "चुनाव")
        "legal" -> t("Courts & law", "कोर्ट-कानून")
        "administration" -> t("Administration", "प्रशासन")
        "protest" -> t("Protests", "विरोध-प्रदर्शन")
        "crime" -> t("Crime", "अपराध")
        else -> t("Other", "अन्य")
    }
    fun reason(code: String, names: (String) -> String): String {
        if (code.startsWith("leaders:")) {
            val who = code.removePrefix("leaders:").split(',').filter { it.isNotBlank() }.joinToString(", ") { names(it) }
            return t("Leader: $who", "नेता: $who")
        }
        if (code.startsWith("swarm:")) {
            val n = code.removePrefix("swarm:")
            return t("$n publishers within 2 hours", "$n स्रोतों ने 2 घंटे में रिपोर्ट किया")
        }
        return when (code) {
            "publisher_breaking" -> t("Publisher marked it breaking", "प्रकाशक ने ब्रेकिंग बताया")
            "resignation" -> t("Resignation", "इस्तीफा")
            "arrest" -> t("Arrest", "गिरफ्तारी")
            "suspension" -> t("Suspension/expulsion", "निलंबन/निष्कासन")
            "defection" -> t("Defection/joining a party", "दलबदल/पार्टी में शामिल")
            "ticket" -> t("Ticket/candidate announcement", "टिकट/प्रत्याशी घोषणा")
            "election_commission" -> t("Election Commission", "चुनाव आयोग")
            "bypoll" -> t("Bypoll", "उपचुनाव")
            "high_court" -> t("High Court/Supreme Court", "उच्च/सर्वोच्च न्यायालय")
            "court_verdict" -> t("Court verdict", "अदालती फैसला")
            "agency_action" -> t("Investigating agency action", "जांच एजेंसी की कार्रवाई")
            "death" -> t("Death", "निधन")
            "alliance" -> t("Alliance", "गठबंधन")
            "cabinet" -> t("Cabinet/oath", "मंत्रिमंडल/शपथ")
            "uproar" -> t("Uproar", "हंगामा")
            else -> code
        }
    }

    // ---- redesign: feed furniture
    val live get() = t("LIVE", "लाइव")
    val breakingNow get() = t("Breaking Now", "अभी ब्रेकिंग")
    val liveWire get() = t("Live Wire", "लाइव वायर")
    val liveWireNote get() = t("Straight from publishers' feeds, read by your phone on every refresh. Not yet grouped or checked.",
        "प्रकाशकों की फ़ीड से सीधे, हर अपडेट पर आपका फ़ोन पढ़ता है। अभी समूहित या जांची नहीं गईं।")
    val latest get() = t("Latest", "ताज़ा खबरें")
    val seeAll get() = t("See all", "सभी देखें")
    val showLess get() = t("Show less", "कम दिखाएं")
    fun sources(n: Int) = t("$n " + pl(n, "source", "sources"), "$n स्रोत")
    val read get() = t("Read", "पढ़ें")
    val open get() = t("Open", "खोलें")
    val coverage get() = t("Coverage", "कवरेज")
    fun alsoReportedBy(n: Int) = t("Also reported by ($n)", "इन्होंने भी रिपोर्ट किया ($n)")
    val breakingAlert get() = t("BREAKING", "ब्रेकिंग")
    val dismiss get() = t("Dismiss", "हटाएं")
    val direct get() = t("Direct", "सीधे")
    val liveWireSetting get() = t("Live Wire on this phone", "इस फ़ोन पर लाइव वायर")
    val liveWireSettingNote get() = t("Your phone also reads the fastest publisher feeds itself on every refresh, so headlines can appear before the server's next run. Uses a little more data.",
        "आपका फ़ोन हर अपडेट पर सबसे तेज़ प्रकाशक फ़ीड खुद भी पढ़ता है, ताकि सर्वर के अगले चक्र से पहले शीर्षक दिख सकें। थोड़ा अधिक डेटा लगता है।")
    val savedTitle get() = t("Saved stories", "सहेजी गई खबरें")
    val readMore get() = t("Read full story", "पूरी खबर पढ़ें")

    // ---- story card
    val viaGoogle get() = t(" (Google News)", " (गूगल न्यूज़)")
    val seenPrefix get() = t("seen ", "देखा ")
    fun moreSources(n: Int) = t("+$n " + pl(n, "source", "sources"), "+$n स्रोत")
    val actOriginal get() = t("Original", "मूल खबर")
    val actSave get() = t("Save", "सहेजें")
    val actSaved get() = t("Saved", "सहेजा गया")
    val actShare get() = t("Share", "शेयर")

    // ---- story detail
    val summaryPublisher get() = t("Summary: from the publisher's feed", "सारांश: प्रकाशक के फ़ीड विवरण से")
    val summaryPage get() = t("Summary: the publisher's own description of the article", "सारांश: लेख के बारे में प्रकाशक का अपना विवरण")
    val summaryAi get() = t("Summary: AI condensed the publisher's description into Hindi", "सारांश: प्रकाशक के विवरण से AI द्वारा हिंदी में संक्षेप")
    val headlineOnly get() = t("Headline only — open the original for details.", "केवल शीर्षक उपलब्ध — पूरी जानकारी के लिए मूल खबर खोलें।")
    val singleSource get() = t("Only one source has reported this so far; no independent confirmation yet.",
        "अभी केवल एक स्रोत ने रिपोर्ट किया है; स्वतंत्र पुष्टि उपलब्ध नहीं।")
    val allegationNote get() = t("This contains allegations. An allegation is not an established fact; it belongs to whoever made it.",
        "इसमें आरोप शामिल हैं। आरोप सिद्ध तथ्य नहीं है; बयान उसी व्यक्ति/संगठन का है जिसने आरोप लगाया।")
    val legalMentioned get() = t("Mentioned in report: ", "रिपोर्ट में उल्लेख: ")
    val legalNote get() = t("Based on the source's wording. An allegation, FIR, arrest or investigation is not a conviction; courts decide the final status.",
        "यह स्रोत के शब्दों पर आधारित है। आरोप, एफआईआर, गिरफ्तारी या जांच दोषसिद्धि नहीं है; अंतिम स्थिति अदालत तय करती है।")
    val related get() = t("Related", "संबंधित")
    val priorityReasons get() = t("Why it is prioritised", "प्राथमिकता के कारण")
    fun sourcesHeader(reports: Int, publishers: Int) = t("Sources ($reports " + pl(reports, "report", "reports") + ", $publishers " + pl(publishers, "publisher", "publishers") + ")", "स्रोत ($reports रिपोर्ट, $publishers प्रकाशक)")
    val openOriginal get() = t("Open original", "मूल खबर खोलें")
    val copyLinks get() = t("Copy headline & links", "शीर्षक व लिंक कॉपी करें")
    val shareStory get() = t("Share", "शेयर करें")
    fun firstSeen(s: String) = t("First seen: $s", "पहली बार देखा: $s")
    fun via(v: String) = t(" — via $v", " — $v के माध्यम से")
    val quotePost get() = t("This post quotes another post", "यह पोस्ट किसी और की पोस्ट को उद्धृत करती है")
    val replyPost get() = t("This post is a reply", "यह किसी पोस्ट का जवाब है")
    val pubNotGiven get() = t("Published: not given by the source", "प्रकाशन समय: स्रोत ने नहीं दिया")
    fun pubSuspect(s: String) = t("Published (per source, doubtful): $s", "प्रकाशन समय (स्रोत के अनुसार, संदिग्ध): $s")
    fun pubAssumed(s: String) = t("Published: $s (IST assumed)", "प्रकाशित: $s (समय-क्षेत्र IST माना गया)")
    fun published(s: String) = t("Published: $s", "प्रकाशित: $s")
    fun updated(s: String) = t("Updated: $s", "अपडेट: $s")
    fun seenByApp(s: String) = t("Seen by News Adda: $s", "न्यूज़ अड्डा ने देखा: $s")
    val openPost get() = t("Open post", "पोस्ट खोलें")
    val openReport get() = t("Open this report", "यह रिपोर्ट खोलें")

    // ---- lists
    val allUp get() = t("All UP news", "सभी यूपी खबरें")
    val politicsOnly get() = t("UP politics", "यूपी राजनीति")
    fun newStories(n: Int) = t("$n new " + pl(n, "story", "stories"), "$n नई खबरें आई हैं")
    val markSeen get() = t("Mark seen", "देख लिया")
    val showNew get() = t("Show", "देखें")
    val emptyHome get() = t("No news yet", "अभी कोई खबर नहीं")
    val emptyHomeBody get() = t("Tap “Update now” or pull down.", "ऊपर “अभी अपडेट करें” दबाएं या नीचे खींचें।")
    val breakingEmpty get() = t("No breaking news in the last 24 hours", "पिछले 24 घंटों में कोई ब्रेकिंग खबर नहीं")
    val breakingEmptyBody get() = t(
        "Breaking is set by signals such as resignations, arrests, defections, tickets, the Election Commission or courts, and by several publishers reporting together. Each story shows its reasons.",
        "ब्रेकिंग का निशान इस्तीफा, गिरफ्तारी, दलबदल, टिकट, चुनाव आयोग, अदालत जैसे संकेतों और कई स्रोतों की एक साथ रिपोर्ट से तय होता है — हर खबर में कारण दिखते हैं।")
    val leaderEmpty get() = t("No recent news for this leader", "इस नेता की कोई हालिया खबर नहीं")
    val allLeaders get() = t("All leaders", "सभी नेता")
    fun following(n: Int) = t("Following ($n)", "अनुसरित ($n)")
    val follow get() = t("Follow", "अनुसरण करें")
    val unfollow get() = t("Unfollow", "अनुसरण हटाएं")
    val followHint get() = t("Pick a leader and tap “Follow” to see their news together here and in notifications.",
        "किसी नेता को चुनकर “अनुसरण करें” दबाएं — फिर उनकी खबरें यहां एक साथ और सूचनाओं में आएंगी।")
    val controversyEmpty get() = t("No recent political controversies", "कोई हालिया राजनीतिक विवाद नहीं")
    val controversyNote get() = t("Allegations and statements are claims by the person or party concerned, not established facts. Check each story's sources and confirmation status.",
        "आरोप और बयान संबंधित व्यक्ति/दल के दावे हैं, सिद्ध तथ्य नहीं। हर खबर में स्रोत और पुष्टि की स्थिति देखें।")
    val all get() = t("All", "सभी")
    val selectionEmpty get() = t("No recent news for this selection", "इस चयन की कोई हालिया खबर नहीं")
    val seatsNote get() = t("Legal status only repeats the report's wording. An allegation or a case is not a conviction.",
        "कानूनी स्थिति केवल रिपोर्ट के शब्दों के अनुसार दिखाई जाती है। आरोप या मुकदमा दोषसिद्धि नहीं है।")
    val statementsEmpty get() = t("No recent statements", "कोई हालिया बयान नहीं")
    val xUnavailable get() = t("X (Twitter) posts are not available — the backend has no X API key. Below are leaders' statements reported in the news.",
        "X (ट्विटर) पोस्ट अभी उपलब्ध नहीं हैं — बैकएंड में X API कुंजी सेट नहीं है। नीचे समाचारों में छपे नेताओं के बयान हैं।")
    val districtPick get() = t("Choose a district. ★ = your favourite districts.", "जिला चुनें। ★ = आपके पसंदीदा जिले।")
    fun storyCount(n: Int) = t("$n " + pl(n, "story", "stories"), "$n खबरें")
    val allDistricts get() = t("← All districts", "← सभी जिले")
    val makeFavourite get() = t("Add to favourites", "पसंदीदा बनाएं")
    val removeFavourite get() = t("Remove favourite", "पसंदीदा से हटाएं")
    fun districtEmpty(name: String) = t("No recent news from $name", "$name की कोई हालिया खबर नहीं")
    val savedEmpty get() = t("No saved stories", "कोई सहेजी गई खबर नहीं")
    val savedEmptyBody get() = t("Tap “Save” on any story; it stays here permanently.", "किसी खबर पर “सहेजें” दबाएं; वह यहां हमेशा रहेगी।")

    // ---- search
    val searchPlaceholder get() = t("Leader, party, district, word… (Hindi or English)", "नेता, दल, जिला, शब्द… (हिंदी या English)")
    val anyTime get() = t("Any time", "कभी भी")
    val today get() = t("Today", "आज")
    val last24h get() = t("24 hours", "24 घंटे")
    val last3d get() = t("3 days", "3 दिन")
    val allParties get() = t("All parties", "सभी दल")
    val allCategories get() = t("All categories", "सभी श्रेणियां")
    val allPeoplePlaces get() = t("All leaders/districts", "सभी नेता/जिले")
    val allSources get() = t("All sources", "सभी स्रोत")
    fun results(n: Int) = t("$n " + pl(n, "result", "results"), "$n परिणाम")
    val searchStart get() = t("Type or choose a filter to search", "खोजने के लिए लिखें या फ़िल्टर चुनें")
    val noResults get() = t("No results", "कोई परिणाम नहीं")
    val searchScope get() = t("Search covers the last 3 days of news saved on this phone.", "खोज फ़ोन पर सहेजी गई पिछले 3 दिनों की खबरों में होती है।")

    // ---- banners
    val offline get() = t("No internet connection", "इंटरनेट उपलब्ध नहीं")
    val timeout get() = t("The server did not respond in time", "सर्वर ने समय पर जवाब नहीं दिया")
    fun serverError(d: String) = t("Server error ($d)", "सर्वर से त्रुटि ($d)")
    val badData get() = t("The server sent invalid data", "सर्वर से गलत डेटा मिला")
    val badUrl get() = t("The backend address is invalid — check Settings", "बैकएंड पता गलत है — सेटिंग्स देखें")
    val showingCached get() = t(" — showing saved stories", " — सहेजी गई खबरें दिखाई जा रही हैं")
    val retry get() = t("Retry", "फिर कोशिश")
    fun feedStale(ago: String) = t("The server feed was built $ago — collection may be delayed.", "सर्वर की फ़ीड $ago बनी थी — संग्रह में देरी हो सकती है।")
    fun sourcesFailing(n: Int) = t(pl(n, "1 source has an error", "$n sources have errors") + "; news is still coming from the rest.", "$n स्रोत अभी त्रुटि में हैं; बाकी से खबरें आ रही हैं।")
    val view get() = t("View", "देखें")
    fun updateReady(v: String) = t("Version $v is downloaded", "नया संस्करण $v डाउनलोड हो चुका है")
    val install get() = t("Install", "इंस्टॉल करें")
    fun updateAvailable(v: String) = t("Version $v is available", "नया संस्करण $v उपलब्ध है")
    val download get() = t("Download", "डाउनलोड करें")
    fun downloading(p: Int) = t("Downloading new version… $p%", "नया संस्करण डाउनलोड हो रहा है… $p%")

    // ---- settings
    val language get() = t("Language", "भाषा")
    val languageNote get() = t("Changes menus and labels only. News headlines and summaries always stay in Hindi.",
        "केवल मेन्यू और लेबल बदलते हैं। खबरों के शीर्षक और सारांश हमेशा हिंदी में रहते हैं।")
    val refreshInterval get() = t("News refresh interval", "खबरें अपडेट करने का अंतराल")
    fun minutes(n: Int) = t("$n min", "$n मिनट")
    fun refreshNote(n: Int) = t(
        "While the app is open it refreshes every $n min, and immediately when you open or return to it. The server (GitHub Actions) collects news roughly every 5–15 minutes, so new stories cannot arrive faster than that even at 1 minute.",
        "ऐप खुला रहने पर हर $n मिनट में, ऐप खोलने और वापस आने पर तुरंत अपडेट होता है। खबरें सर्वर (GitHub Actions) पर लगभग हर 5–15 मिनट में इकट्ठा होती हैं, इसलिए 1 मिनट चुनने पर भी नई खबरें उसी गति से आएंगी।")
    fun lastSuccess(a: String, b: String) = t("Last successful update: $a · Server feed built: $b", "अंतिम सफल अपडेट: $a · सर्वर फ़ीड बनी: $b")
    val notifications get() = t("Breaking news notifications", "ब्रेकिंग न्यूज़ सूचनाएं")
    val notificationsOn get() = t("Notifications on", "सूचनाएं चालू")
    val notifyAll get() = t("All breaking news (off = only followed leaders / favourite districts)", "सभी ब्रेकिंग खबरें (बंद = केवल अनुसरित नेता/पसंदीदा जिले)")
    val allowNotifications get() = t("Allow notifications", "सूचना की अनुमति दें")
    val backgroundNote get() = t("Android runs background checks about every 15 minutes or later; battery saving can delay them further.",
        "बैकग्राउंड जांच Android के नियमों के कारण लगभग हर 15 मिनट या उससे देर से होती है; बैटरी बचत में फ़ोन इसे और टाल सकता है।")
    val appUpdates get() = t("App updates", "ऐप अपडेट")
    fun currentVersion(v: String, c: Int) = t("Current version: $v ($c)", "वर्तमान संस्करण: $v ($c)")
    val debugNoUpdates get() = t("This is a debug build — update checks are off.", "यह डीबग बिल्ड है — अपडेट जांच बंद है।")
    fun everyHours(h: Int) = t("Every $h h", "हर $h घंटे")
    val autoDownload get() = t("Download new versions automatically", "नया संस्करण अपने-आप डाउनलोड करें")
    val updatesNote get() = t("Checked when the app opens and in the background at the chosen interval. Android always asks you to confirm an install.",
        "ऐप खुलते ही और चुने गए अंतराल पर बैकग्राउंड में जांच होती है। इंस्टॉल के लिए Android हमेशा आपकी पुष्टि मांगता है।")
    fun lastCheck(s: String) = t("Last check: $s", "अंतिम जांच: $s")
    val checking get() = t("Checking…", "जांच हो रही है…")
    val checkNow get() = t("Check for updates now", "अभी अपडेट जांचें")
    val allVersions get() = t("All versions", "सभी संस्करण")
    fun newVersionAvailable(v: String) = t("Version $v is available", "नया संस्करण $v उपलब्ध है")
    fun downloadingPct(p: Int) = t("Downloading… $p%", "डाउनलोड हो रहा है… $p%")
    val followedLeaders get() = t("Followed leaders", "अनुसरित नेता")
    val listAfterFirstUpdate get() = t("The list appears after the first update.", "सूची पहली बार अपडेट होने पर दिखेगी।")
    val favouriteDistricts get() = t("Favourite districts", "पसंदीदा जिले")
    fun newsSources(n: Int) = t("News sources ($n working)", "समाचार स्रोत ($n काम कर रहे)")
    val sourcesNote get() = t("Status comes from the server's latest real check. Turning a source off hides its stories on this phone.",
        "स्थिति सर्वर की पिछली वास्तविक जांच से आती है। किसी स्रोत को बंद करने पर उसकी खबरें इस फ़ोन पर छिप जाती हैं।")
    fun sourceStatus(code: String) = when (code) {
        "ok" -> t("Working", "काम कर रहा है")
        "error" -> t("Error", "त्रुटि")
        "unavailable" -> t("Unavailable", "उपलब्ध नहीं")
        "disabled" -> t("Off", "बंद")
        else -> t("Not checked yet", "अभी जांचा नहीं गया")
    }
    fun sourceError(code: String) = when (code) {
        "no_public_feed" -> t("No public feed; covered via Google News where possible", "कोई सार्वजनिक फ़ीड नहीं; जहां संभव हो गूगल न्यूज़ से")
        "x_token_missing" -> t("X API key not set", "X API कुंजी सेट नहीं है")
        "timeout" -> t("Timed out", "समय सीमा समाप्त")
        else -> code
    }
    fun lastOk(s: String) = t("last OK: $s", "अंतिम सफल: $s")
    fun itemCount(n: Int) = t("$n " + pl(n, "story", "stories"), "$n खबरें")
    val textSize get() = t("Text size", "अक्षर आकार")
    val textSizes get() = if (hindi) listOf("छोटा", "सामान्य", "बड़ा", "बहुत बड़ा") else listOf("Small", "Normal", "Large", "Extra large")
    val backendAddress get() = t("Backend (feed) address", "बैकएंड (फ़ीड) पता")
    val saveBtn get() = t("Save", "सहेजें")
    val defaultBtn get() = t("Default", "डिफ़ॉल्ट")
    val backendNote get() = t("If you run your own server, enter https://your-server/feed.json — then refresh down to 1 minute is possible.",
        "अपना सर्वर चलाने पर यहां https://आपका-सर्वर/feed.json डालें — तब 1 मिनट तक की ताज़गी संभव है।")
    val data get() = t("Data", "डेटा")
    val clearCache get() = t("Clear saved cache", "सहेजा गया कैश साफ़ करें")
    val clearCacheNote get() = t("Saved (bookmarked) stories are kept.", "सहेजी गई खबरें (बुकमार्क) नहीं हटेंगी।")
    val about get() = t("About News Adda", "न्यूज़ अड्डा के बारे में")
    val aboutText get() = t(
        "Only headlines, short summaries and the original publisher's link are shown; read the full story on the publisher's site. Nothing is invented — if a source does not give a time or detail, it shows as \"not available\". Labels such as \"Breaking\" and \"Allegation\" come from keyword rules, show their reasons, and can be wrong.",
        "केवल शीर्षक, संक्षिप्त सारांश और मूल प्रकाशक का लिंक दिखाया जाता है; पूरी खबर प्रकाशक की साइट पर पढ़ें। कोई खबर, समय या बयान गढ़ा नहीं जाता — जो स्रोत नहीं देता, वह \"उपलब्ध नहीं\" दिखता है। \"ब्रेकिंग\", \"आरोप\" आदि निशान शब्द-आधारित नियमों से लगते हैं, उनके कारण दिखते हैं; ये गलत भी हो सकते हैं।")
    val allowInstall get() = t("Allow installs, then come back and tap “Install” again", "इंस्टॉल की अनुमति दें, फिर वापस आकर दोबारा \"इंस्टॉल करें\" दबाएं")

    // ---- update manager
    fun checkFailed(m: String) = t("Update check failed: $m", "अपडेट जांच विफल: $m")
    fun onLatest(v: String) = t("You are on the latest version ($v)", "आप नवीनतम संस्करण ($v) पर हैं")
    fun downloadFailed(m: String) = t("Download failed: $m", "डाउनलोड विफल: $m")
    val shaMismatch get() = t("SHA-256 did not match — file deleted", "SHA-256 मेल नहीं खाया — फ़ाइल हटा दी गई")
    val apkUnreadable get() = t("Could not read the APK", "APK पढ़ा नहीं जा सका")
    val wrongPackage get() = t("The APK belongs to another app", "APK किसी दूसरे ऐप का है")
    val wrongVersion get() = t("The APK version is not the expected one", "APK का संस्करण अपेक्षित नहीं है")
    fun installerFailed(m: String) = t("Could not open the installer: $m", "इंस्टॉलर नहीं खुल सका: $m")

    // ---- actions & notifications
    val noBrowser get() = t("No browser found to open the link", "लिंक खोलने के लिए कोई ब्राउज़र नहीं मिला")
    val copied get() = t("Headline and links copied", "शीर्षक और लिंक कॉपी हुए")
    val shareChooser get() = t("Share story", "खबर शेयर करें")
    fun viaSuffix(v: String) = t(" (via $v)", " ($v के माध्यम से)")
    val chBreaking get() = t("Breaking news", "ब्रेकिंग न्यूज़")
    val chBreakingDesc get() = t("Breaking news for followed leaders and districts", "अनुसरित नेताओं और जिलों की ब्रेकिंग खबरें")
    val chUpdates get() = t("App updates", "ऐप अपडेट")
    val chUpdatesDesc get() = t("When a new version of News Adda is available", "न्यूज़ अड्डा का नया संस्करण उपलब्ध होने पर")
    val nBreaking get() = t("Breaking", "ब्रेकिंग")
    val nNew get() = t("New story", "नई खबर")
    fun nSummary(n: Int) = t("$n new breaking " + pl(n, "story", "stories"), "$n नई ब्रेकिंग खबरें")
    val nOpenApp get() = t("Open News Adda to read them", "न्यूज़ अड्डा खोलकर देखें")
    fun nUpdateTitle(v: String) = t("News Adda $v is available", "न्यूज़ अड्डा $v उपलब्ध")
    val nUpdateReady get() = t("Downloaded — tap to install", "डाउनलोड हो चुका है — इंस्टॉल करने के लिए टैप करें")
    val nUpdateTap get() = t("Tap to update", "अपडेट करने के लिए टैप करें")
}

val LocalStr = staticCompositionLocalOf { Str(false) }
