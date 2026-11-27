package com.example.swipecull

/** Languages offered for captions: supported by both the speech model and on-device translation. */
val CAPTION_LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English", "tl" to "Filipino (Tagalog)", "es" to "Spanish", "fr" to "French",
    "de" to "German", "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian",
    "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic",
    "hi" to "Hindi", "id" to "Indonesian", "ms" to "Malay", "vi" to "Vietnamese",
    "th" to "Thai", "tr" to "Turkish", "nl" to "Dutch", "pl" to "Polish",
    "uk" to "Ukrainian", "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian",
    "fi" to "Finnish", "cs" to "Czech", "el" to "Greek", "he" to "Hebrew",
    "hu" to "Hungarian", "ro" to "Romanian", "bn" to "Bengali", "ta" to "Tamil",
    "ur" to "Urdu", "fa" to "Persian",
)

fun languageName(code: String): String = CAPTION_LANGUAGES.firstOrNull { it.first == code }?.second ?: code
