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

    fun generate(file: File): String {
        return try {
            PDDocument.load(file).use { doc ->
                // 1. Try PDF metadata title
                val meta = doc.documentInformation?.title?.trim()
                val baseTitle = if (!meta.isNullOrBlank()
                    && meta.length in 4..70
                    && !meta.equals("untitled", ignoreCase = true)
                    && !meta.equals("unknown", ignoreCase = true)
                    && !meta.startsWith("Microsoft")) meta
                else null

                // 2. Extract text from first 2 pages for category + title
                val stripper = PDFTextStripper().apply { startPage = 1; endPage = 2 }
                val rawText = runCatching { stripper.getText(doc).take(4000) }.getOrDefault("")
                val lowerText = rawText.lowercase()

                // 3. Detect best-matching category
                val emoji = detectEmoji(lowerText, file.nameWithoutExtension.lowercase())

                // 4. Pick best title text
                val titleText = baseTitle
                    ?: extractTitleLine(rawText)
                    ?: cleanFileName(file.nameWithoutExtension)

                "$emoji $titleText"
            }
        } catch (_: Exception) {
            val emoji = detectEmoji("", file.nameWithoutExtension.lowercase())
            "$emoji ${cleanFileName(file.nameWithoutExtension)}"
        }
    }

    private fun detectEmoji(bodyText: String, fileNameLower: String): String {
        val combined = "$bodyText $fileNameLower"
        var bestScore = 0
        var bestEmoji = "📄"
        for ((keywords, label) in categories) {
            val score = keywords.count { combined.contains(it) }
            if (score > bestScore) {
                bestScore = score
                bestEmoji = label.first
            }
        }
        return bestEmoji
    }

    private fun extractTitleLine(text: String): String? =
        text.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.length in 6..72
                && line.count { it.isLetter() } >= 5
                && !line.startsWith("http")
                && !line.all { !it.isLetter() }
                && line.count { it.isUpperCase() } < line.length * 0.8  // skip ALL-CAPS noise
            }
            ?.take(60)

    private fun cleanFileName(name: String): String =
        name.replace(Regex("[_\\-\\.]+"), " ")
            .split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .take(60).ifBlank { "PDF Document" }
}
