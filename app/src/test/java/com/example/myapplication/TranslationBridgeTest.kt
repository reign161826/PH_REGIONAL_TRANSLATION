package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.Normalizer

class TranslationBridgeTest {

    private fun getManualTranslation(text: String): String? {
        val mapping = mapOf(
            // Chinese (Pinyin)
            "ni hao" to "Hello",
            "nihao" to "Hello",
            "ni hao ma" to "How are you",
            "xie xie" to "Thank you",
            "xiexie" to "Thank you",
            "shei shei" to "Thank you",
            "bu ke qi" to "You're welcome",
            "zai jian" to "Goodbye",
            "dui bu qi" to "I'm sorry",
            "mei guan xi" to "It's okay",
            "wo ai ni" to "I love you",
            "ping an" to "Peace",

            // Japanese (Romaji)
            "konnichiwa" to "Hello",
            "konichiwa" to "Hello",
            "ohayou" to "Good morning",
            "konbanwa" to "Good evening",
            "oyasumi" to "Good night",
            "sayonara" to "Goodbye",
            "arigato" to "Thank you",
            "arigatou" to "Thank you",
            "arigatou gozaimasu" to "Thank you very much",
            "sumimasen" to "Excuse me/Sorry",
            "gomen" to "Sorry",
            "gomenasai" to "I'm sorry",
            "gomennasai" to "I'm sorry",
            "itadakimasu" to "Let's eat",
            "gochisousama" to "Thanks for the food",
            "ai shiteru" to "I love you",
            "daijoubu" to "I'm okay",
            "ganbatte" to "Good luck",
            "moshi moshi" to "Hello",

            // Korean (Romanization)
            "annyeong" to "Hello",
            "annyeonghaseyo" to "Hello",
            "kamsahamnida" to "Thank you",
            "gomawo" to "Thank you",
            "mianhae" to "Sorry",
            "mian" to "Sorry",
            "joesonghabnida" to "I'm sorry",
            "gwaenchanha" to "It's okay",
            "gwenchana" to "It's okay",
            "saranghae" to "I love you",
            "hajima" to "Don't do it",
            "daebak" to "Awesome",
            "jalga" to "Goodbye",
            "jinjja" to "Really?",
            "kajja" to "Let's go",

            // Spanish
            "hola" to "Hello",
            "gracias" to "Thank you",
            "de nada" to "You're welcome",
            "por favor" to "Please",
            "adios" to "Goodbye",
            "lo siento" to "I'm sorry",
            "que tal" to "How are you",
            "como estas" to "How are you",

            // French
            "bonjour" to "Hello",
            "salut" to "Hi/Bye",
            "merci" to "Thank you",
            "s'il vous plait" to "Please",
            "au revoir" to "Goodbye",
            "pardon" to "Sorry",
            "desole" to "Sorry",
            "c'est la vie" to "That's life",

            // Indonesian / Malay
            "apa kabar" to "How are you",
            "terima kasih" to "Thank you",
            "sama sama" to "You're welcome",
            "selamat pagi" to "Good morning",
            "selamat siang" to "Good afternoon",
            "maaf" to "Sorry",

            // Vietnamese / Thai / Others
            "xin chao" to "Hello",
            "cam on" to "Thank you",
            "sawasdee" to "Hello",
            "khop khun" to "Thank you",
            "namaste" to "Hello",
            "salam" to "Hello",
            "aloha" to "Hello/Goodbye",
            "ciao" to "Hello/Goodbye"
        )
        
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        val alphabetOnly = normalized.lowercase()
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z]"), "")
            .trim()
            
        if (alphabetOnly.isEmpty()) return null

        for ((key, value) in mapping) {
            val cleanKey = key.lowercase().replace(Regex("[^a-z]"), "")
            if (cleanKey == alphabetOnly) return value
        }
            
        return null
    }

    @Test
    fun testNativeScriptDetection() {
        val koreanSample = "안녕하세요"
        val japaneseSample = "こんにちは"
        val chineseSample = "谢谢"
        
        val detectScript = { res: String ->
            when {
                res.any { it.code in 0xAC00..0xD7AF } -> "ko"
                res.any { it.code in 0x3040..0x309F || it.code in 0x30A0..0x30FF } -> "ja"
                res.any { it.code in 0x4E00..0x9FFF } -> "zh"
                else -> null
            }
        }

        assertEquals("ko", detectScript(koreanSample))
        assertEquals("ja", detectScript(japaneseSample))
        assertEquals("zh", detectScript(chineseSample))
        assertNull(detectScript("Hello"))
    }

    @Test
    fun testManualMappingsRomanizedAndPunctuation() {
        assertEquals("Hello", getManualTranslation("Konnichiwa!"))
        assertEquals("Hello", getManualTranslation("ni hao..."))
        assertEquals("Thank you", getManualTranslation("Kamsahamnida~"))
        assertEquals("Thank you very much", getManualTranslation("Arigatou Gozaimasu!!!"))
        assertEquals("I love you", getManualTranslation("Saranghae ❤"))
        assertEquals("How are you", getManualTranslation("¿Cómo estás?"))
    }

    @Test
    fun testPunctuationAwareTokenization() {
        val text = "hello, world!"
        val tokens = text.split(Regex("(?<=\\s)|(?=\\s)|(?<=[\\p{Punct}])|(?=[[\\p{Punct}]])"))
        
        val reconstructed = tokens.joinToString("")
        assertEquals(text, reconstructed)
        
        val hasComma = tokens.contains(",")
        val hasExclamation = tokens.contains("!")
        assertEquals(true, hasComma)
        assertEquals(true, hasExclamation)
    }
}
