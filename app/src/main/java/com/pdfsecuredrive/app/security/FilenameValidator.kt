package com.pdfsecuredrive.app.security

object FilenameValidator {

    private val PATH_TRAVERSAL = Regex(
        """(\.\.[\\/]|%2e%2e[\\/]|%252e%252e|\.\.%2f|\.\.%5c)""",
        RegexOption.IGNORE_CASE
    )
    private val XSS_PATTERNS = Regex(
        """(<script|alert\s*\(|javascript:|on\w+\s*=|data:text/html)""",
        RegexOption.IGNORE_CASE
    )
    private val SHELL_INJECTION = Regex("""[|;&`]|\$\(|\$\{|>\s*/""")
    private val NULL_BYTE = Regex("""\x00|%00""")
    private val DOUBLE_EXTENSION = Regex(
        """\.(exe|bat|cmd|sh|ps1|vbs|js|jar|apk|dex|so|dll|msi|dmg|elf|bin)\.""",
        RegexOption.IGNORE_CASE
    )
    private val WINDOWS_RESERVED = Regex(
        """^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)""",
        RegexOption.IGNORE_CASE
    )
    private val CONTROL_CHARS = Regex("""[\x00-\x1f\x7f]""")

    data class ValidationResult(val isValid: Boolean, val threats: List<Threat>)

    fun validate(filename: String): ValidationResult {
        val threats = mutableListOf<Threat>()

        if (filename.isBlank()) {
            threats.add(Threat("Empty Filename", "Filename is blank", RiskLevel.HIGH, "Structural"))
            return ValidationResult(false, threats)
        }
        if (filename.length > 255) threats.add(
            Threat("Filename Too Long", "Exceeds 255 chars — possible buffer overflow attempt", RiskLevel.HIGH, "Buffer Overflow")
        )
        if (PATH_TRAVERSAL.containsMatchIn(filename)) threats.add(
            Threat("Path Traversal", "Contains ../ — directory escape attempt", RiskLevel.CRITICAL, "Directory Traversal")
        )
        if (XSS_PATTERNS.containsMatchIn(filename)) threats.add(
            Threat("XSS in Filename", "Scripting payload embedded in filename", RiskLevel.HIGH, "Scripting")
        )
        if (SHELL_INJECTION.containsMatchIn(filename)) threats.add(
            Threat("Shell Injection", "Shell metacharacters in filename", RiskLevel.HIGH, "Injection")
        )
        if (NULL_BYTE.containsMatchIn(filename)) threats.add(
            Threat("Null Byte Injection", "Null byte detected — extension spoofing", RiskLevel.CRITICAL, "Injection")
        )
        if (DOUBLE_EXTENSION.containsMatchIn(filename)) threats.add(
            Threat("Hidden Executable Extension", "Executable extension hidden inside filename", RiskLevel.CRITICAL, "Masquerading")
        )
        if (WINDOWS_RESERVED.containsMatchIn(filename)) threats.add(
            Threat("Reserved Device Name", "Matches Windows reserved device name", RiskLevel.MEDIUM, "Filesystem")
        )
        if (CONTROL_CHARS.containsMatchIn(filename)) threats.add(
            Threat("Control Characters", "Non-printable control chars in filename", RiskLevel.HIGH, "Injection")
        )
        if (!filename.lowercase().endsWith(".pdf")) threats.add(
            Threat("Invalid Extension", "File does not end with .pdf", RiskLevel.MEDIUM, "Masquerading")
        )

        return ValidationResult(threats.isEmpty(), threats)
    }
}
