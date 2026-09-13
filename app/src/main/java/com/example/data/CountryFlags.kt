package com.example.data

object CountryFlags {

    private val countryFlagMap = mapOf(
        "сша" to "🇺🇸",
        "usa" to "🇺🇸",
        "united states" to "🇺🇸",
        "америка" to "🇺🇸",
        "великобритания" to "🇬🇧",
        "англия" to "🇬🇧",
        "united kingdom" to "🇬🇧",
        "uk" to "🇬🇧",
        "россия" to "🇷🇺",
        "рф" to "🇷🇺",
        "ссср" to "🇷🇺",
        "russia" to "🇷🇺",
        "япония" to "🇯🇵",
        "japan" to "🇯🇵",
        "южная корея" to "🇰🇷",
        "корея" to "🇰🇷",
        "korea" to "🇰🇷",
        "франция" to "🇫🇷",
        "france" to "🇫🇷",
        "германия" to "🇩🇪",
        "germany" to "🇩🇪",
        "италия" to "🇮🇹",
        "italy" to "🇮🇹",
        "испания" to "🇪🇸",
        "spain" to "🇪🇸",
        "канада" to "🇨🇦",
        "canada" to "🇨🇦",
        "австралия" to "🇦🇺",
        "australia" to "🇦🇺",
        "китай" to "🇨🇳",
        "china" to "🇨🇳",
        "гонконг" to "🇭🇰",
        "тайвань" to "🇹🇼",
        "индия" to "🇮🇳",
        "india" to "🇮🇳",
        "турция" to "🇹🇷",
        "turkey" to "🇹🇷",
        "швеция" to "🇸🇪",
        "sweden" to "🇸🇪",
        "дания" to "🇩🇰",
        "denmark" to "🇩🇰",
        "норвегия" to "🇳🇴",
        "norway" to "🇳🇴",
        "финляндия" to "🇫🇮",
        "finland" to "🇫🇮",
        "польша" to "🇵🇱",
        "poland" to "🇵🇱",
        "украина" to "🇺🇦",
        "ukraine" to "🇺🇦",
        "бразилия" to "🇧🇷",
        "brazil" to "🇧🇷",
        "мексика" to "🇲🇽",
        "mexico" to "🇲🇽",
        "ирландия" to "🇮🇪",
        "ireland" to "🇮🇪",
        "новая зеландия" to "🇳🇿",
        "нидерланды" to "🇳🇱",
        "netherlands" to "🇳🇱",
        "бельгия" to "🇧🇪",
        "belgium" to "🇧🇪",
        "австрия" to "🇦🇹",
        "austria" to "🇦🇹",
        "швейцария" to "🇨🇭",
        "switzerland" to "🇨🇭",
        "чехия" to "🇨🇿",
        "czech" to "🇨🇿",
        "таиланд" to "🇹🇭",
        "thailand" to "🇹🇭",
        "израиль" to "🇮🇱",
        "israel" to "🇮🇱",
        "аргентина" to "🇦🇷",
        "argentina" to "🇦🇷",
        "исландия" to "🇮🇸",
        "iceland" to "🇮🇸",
        "греция" to "🇬🇷",
        "greece" to "🇬🇷",
        "колумбия" to "🇨🇴",
        "чилий" to "🇨🇱",
        "чили" to "🇨🇱",
        "юар" to "🇿🇦",
        "португалия" to "🇵🇹",
        "portugal" to "🇵🇹",
        "венгрия" to "🇭🇺",
        "hungary" to "🇭🇺",
        "румыния" to "🇷🇴",
        "сингапур" to "🇸🇬",
        "индонезия" to "🇮🇩",
        "филиппины" to "🇵🇭"
    )

    fun getFlag(countryName: String): String {
        val clean = countryName.trim().lowercase()
        return countryFlagMap[clean] ?: ""
    }

    fun formatWithFlags(countriesRaw: String): String {
        if (countriesRaw.isBlank()) return ""
        val parts = countriesRaw.split(",", "/", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return countriesRaw

        return parts.joinToString(", ") { country ->
            val flag = getFlag(country)
            if (flag.isNotEmpty()) "$flag $country" else country
        }
    }
}
