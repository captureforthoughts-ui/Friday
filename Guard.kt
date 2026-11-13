package app.friday.phone

/**
 * What Friday must never touch on the phone. This is enforced in code, not left to the voice model:
 * banking, payment, investing, crypto, password-manager and authenticator apps, sign-in and one-time-code screens,
 * and password fields. Anything that sends, pays, buys, deletes or posts has to be read back and confirmed first.
 */
object Guard {
    /** Matched against an app's package name and its visible name (lower case, "contains"). */
    val blockedWords = listOf(
        "bank", "banking", "wallet", "pay", "paisa", "gpay", "paylah", "paynow", "nets", "atome", "finance", "fintech",
        "invest", "trading", "broker", "securities", "stock", "crypto", "coin", "binance", "bitcoin", "ethereum",
        "credit", "debit", "loan", "mortgage", "insurance", "tax", "cpf", "singpass", "corppass", "myinfo",
        "dbs", "posb", "ocbc", "uob", "citi", "maybank", "hsbc", "standardchartered", "scb", "gxs", "trust",
        "revolut", "wise", "venmo", "cashapp", "zelle", "paypal", "stripe", "moomoo", "futu", "tigerbrokers",
        "syfe", "stashaway", "endowus", "interactivebrokers", "robinhood", "webull",
        "authenticator", "authy", "otp", "1password", "lastpass", "bitwarden", "dashlane", "keepass", "keeper",
        "passwordmanager", "password manager", "vault",
    )

    /** Words that mean the screen in front is a sign-in, payment or code screen, whatever app it is in. */
    private val sensitiveScreen = Regex(
        "(internet banking|online banking|mobile banking|enter (your )?(pin|password|passcode)|card number|cvv|cvc|" +
            "security code|one-time (password|pin|code)|verification code|otp|log ?in to your bank|account number|" +
            "card details|expiry date|pay now|confirm payment|place order|total to pay|scan to pay)",
        RegexOption.IGNORE_CASE,
    )

    /** Buttons that do something you can't easily undo: these need a spoken yes first. */
    val risky = Regex(
        "\\b(send|post|pay|buy|purchase|order|checkout|check out|delete|remove|confirm|submit|subscribe|transfer|" +
            "donate|tip|call|book|reserve|install|uninstall|sign out|log out|logout|erase|reset|clear all|publish|share)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun blockedReason(packageName: String, label: String, extra: List<String>): String? {
        val p = packageName.lowercase()
        val l = label.lowercase()
        val squashed = l.replace(Regex("[^a-z0-9]"), "")     // "Standard Chartered" -> "standardchartered"
        for (w in blockedWords + extra.map { it.lowercase().trim() }.filter { it.length >= 3 }) {
            val ws = w.replace(Regex("[^a-z0-9]"), "")
            if (ws.isEmpty()) continue
            if (p.contains(w) || l.contains(w) || squashed.contains(ws)) return "it looks like a banking, payment or security app ('$w')"
        }
        for (w in listOf("standard chartered", "chartered", "charles schwab", "fidelity", "vanguard", "etoro", "plus500", "gcash", "maya", "touch n go", "touchngo", "tng", "dana", "ovo", "gopay", "linepay", "kakaopay", "alipay", "wechatpay", "apple pay", "samsung pay")) {
            val ws = w.replace(Regex("[^a-z0-9]"), "")
            if (squashed.contains(ws) || p.contains(ws)) return "it looks like a banking, payment or security app ('$w')"
        }
        return null
    }

    fun sensitiveScreenReason(allText: String): String? =
        if (sensitiveScreen.containsMatchIn(allText)) "this looks like a sign-in, payment or code screen" else null

    fun isRisky(label: String): Boolean = risky.containsMatchIn(label.replace('_', ' ').replace('-', ' '))
}
