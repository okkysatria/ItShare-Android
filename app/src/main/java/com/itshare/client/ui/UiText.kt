package com.itshare.client.ui

fun uiText(language: String, english: String, indonesian: String): String =
    if (language.equals("id", ignoreCase = true)) indonesian else english
