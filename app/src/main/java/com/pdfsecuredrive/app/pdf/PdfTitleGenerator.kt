package com.pdfsecuredrive.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfTitleGenerator {

    // keywords → (emoji, category name)
    private val categories = listOf(
        listOf("bug bounty","vulnerability","pentest","penetration test","exploit","payload","ctf","xss","sql injection","rce","csrf","ssrf","recon","reconnaissance","hackerone","bugcrowd","owasp") to ("🐛" to "Bug Bounty"),
        listOf("security","cybersecurity","hacking","malware","ransomware","phishing","firewall","encryption","cipher","threat","incident response","zero day","cve","intrusion","siem","soc") to ("🔐" to "Security"),
        listOf("invoice","billing","payment","receipt","purchase order","quotation","tax","vat","gst","balance sheet","profit","loss","revenue","expense","bank statement","transaction") to ("💰" to "Finance"),
        listOf("contract","agreement","terms and conditions","legal","clause","arbitration","litigation","attorney","counsel","jurisdiction","nda","non-disclosure","liability","indemnity") to ("⚖️" to "Legal"),
        listOf("resume","curriculum vitae"," cv ","work experience","employment history","skills","objective","references","career","job application","cover letter") to ("👤" to "Resume"),
        listOf("research","study","paper","journal","abstract","hypothesis","methodology","literature review","conclusion","references","doi","ieee","acm","springer") to ("📚" to "Research"),
        listOf("report","findings","recommendation","executive summary","kpi","performance","quarterly","annual report","audit","assessment","review") to ("📊" to "Report"),
        listOf("manual","guide","tutorial","how to","documentation","readme","installation","setup","configuration","user guide","step by step","getting started") to ("📖" to "Guide"),
        listOf("medical","health","patient","diagnosis","treatment","prescription","hospital","clinic","doctor","medicine","dosage","symptom","laboratory","clinical") to ("🏥" to "Medical"),
        listOf("programming","software","algorithm","function","class","api","library","framework","javascript","python","kotlin","java","android","github","deployment") to ("💻" to "Code"),
        listOf("machine learning","artificial intelligence","neural network","deep learning","dataset","model training","accuracy","classification","nlp","transformer","llm","gpt") to ("🤖" to "AI/ML"),
        listOf("travel","itinerary","flight","hotel","booking","visa","passport","destination","tour","vacation","airline","reservation") to ("✈️" to "Travel"),
        listOf("project","proposal","milestone","timeline","deliverable","scope","requirement","stakeholder","roadmap","sprint","agile","scrum","backlog") to ("🗂️" to "Project"),
        listOf("policy","procedure","compliance","regulation","gdpr","hipaa","pci","iso 27001","framework","governance","risk management") to ("📋" to "Policy"),
        listOf("certificate","certification","award","completion","diploma","degree","credential","accreditation","licensed") to ("🏆" to "Certificate"),
        listOf("analytics","statistics","chart","visualization","dashboard","insight","trend","dataset","regression","correlation") to ("📈" to "Analytics"),
        listOf("product","catalog","brochure","specification","features","pricing","model","brand","launch","marketing","promotion") to ("🛍️" to "Product"),
        listOf("training","course","lesson","module","quiz","exam","syllabus","learning","workshop","seminar","lecture","classroom") to ("🎓" to "Training"),
    )

    // Generic metadata titles that authoring tools leave behind — never use these.
    private val TEMPLATE_TITLES = listOf(
        "untitled", "unknown", "document", "document1", "presentation",
        "slide 1", "powerpoint presentation", "blank", "title", "new document"
    )
    private val DATE_LIKE   = Regex("""^[\d\s./,:-]+$""")
    private val EMAIL_LIKE   = Regex("""\S+@\S+\.\S+""")
    private val PAGE_LIKE    = Regex("""^(page|p\.?)\s*\d+""", RegexOption.IGNORE_CASE)

    fun generate(file: File): String {
        return try {
            PDDocument.load(file).use { doc ->
                // 1. Try PDF metadata title (normalised)
                val baseTitle = normalizeMeta(doc.documentInformation?.title)

                // 2. Extract text from first 2 pages for category + title
                val stripper = PDFTextStripper().apply { startPage = 1; endPage = 2 }
                val rawText = runCatching { stripper.getText(doc).take(4000) }.getOrDefault("")

                // 3. Detect best-matching category emoji
                val emoji = detectEmoji(rawText.lowercase(), file.nameWithoutExtension.lowercase())

                // 4. Pick best title text: metadata → best scored line → cleaned filename
                val titleText = baseTitle
                    ?: extractTitleLine(rawText)
                    ?: cleanFileName(file.nameWithoutExtension)

                "$emoji ${titleText.trim()}"
            }
        } catch (_: Exception) {
            val emoji = detectEmoji("", file.nameWithoutExtension.lowercase())
            "$emoji ${cleanFileName(file.nameWithoutExtension)}"
        }
    }

    /** Collapse whitespace, strip control chars, reject generic/template metadata titles. */
    private fun normalizeMeta(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(Regex("[\\x00-\\x1f]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length !in 4..80) return null
        val lower = cleaned.lowercase()
        if (TEMPLATE_TITLES.any { lower == it }) return null
        if (lower.startsWith("microsoft word - ") || lower.startsWith("microsoft powerpoint - ")) {
            // strip the "Microsoft Word - " prefix some PDFs carry, keep the real name
            val real = cleaned.substringAfter(" - ", "").trim()
            return real.takeIf { it.length in 4..80 }
        }
        if (lower.startsWith("microsoft")) return null
        return cleaned
    }

    /** Word-boundary keyword matching; body hits weigh more than filename hits; ties → total keyword length. */
    private fun detectEmoji(bodyText: String, fileNameLower: String): String {
        var bestScore = 0
        var bestLen = 0
        var bestEmoji = "📄"
        for ((keywords, label) in categories) {
            var score = 0
            var len = 0
            for (kw in keywords) {
                val k = kw.trim()
                if (k.isEmpty()) continue
                if (containsWord(bodyText, k)) { score += 2; len += k.length }
                else if (containsWord(fileNameLower, k)) { score += 1; len += k.length }
            }
            if (score > bestScore || (score == bestScore && score > 0 && len > bestLen)) {
                bestScore = score; bestLen = len; bestEmoji = label.first
            }
        }
        return bestEmoji
    }

    /** True if [needle] appears in [haystack] on word boundaries (avoids "class" matching "classroom"). */
    private fun containsWord(haystack: String, needle: String): Boolean {
        if (needle.contains(' ')) return haystack.contains(needle)   // phrases: plain contains
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else haystack[i - 1]
            val afterIdx = i + needle.length
            val after = if (afterIdx >= haystack.length) ' ' else haystack[afterIdx]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }

    /**
     * Score the first ~25 non-empty lines and return the best title candidate.
     * Prefers: near the top, 2+ words, Title-Case over ALL-CAPS, not a date/email/url/page marker.
     */
    private fun extractTitleLine(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(25)
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        lines.forEachIndexed { idx, line ->
            if (line.length !in 6..90) return@forEachIndexed
            val letters = line.count { it.isLetter() }
            if (letters < 5) return@forEachIndexed
            if (line.startsWith("http", true)) return@forEachIndexed
            if (DATE_LIKE.matches(line)) return@forEachIndexed
            if (EMAIL_LIKE.containsMatchIn(line)) return@forEachIndexed
            if (PAGE_LIKE.containsMatchIn(line)) return@forEachIndexed
            val words = line.split(Regex("\\s+")).filter { it.any { c -> c.isLetter() } }
            if (words.size < 2) return@forEachIndexed

            var score = 0
            score += (25 - idx) * 2                                  // earlier lines score higher
            score += minOf(words.size, 10)                           // a few words is good
            val upper = line.count { it.isUpperCase() }
            if (upper >= line.length * 0.8) score -= 12              // penalise ALL-CAPS banners
            if (line.endsWith(".")) score -= 4                       // sentences are usually body text
            if (line.length in 18..70) score += 6                    // ideal title length
            if (score > bestScore) { bestScore = score; best = line }
        }
        return best?.let { if (it.length > 80) it.take(80).trim() else it }
    }

    private fun cleanFileName(name: String): String =
        name.replace(Regex("[_\\-.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { w -> if (w.length <= 4 && w.all { it.isUpperCase() }) w else w.replaceFirstChar { c -> c.uppercaseChar() } }
            .take(70).ifBlank { "PDF Document" }
}
