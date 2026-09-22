package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, TextToSpeech.OnInitListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var inputText: EditText
    private lateinit var outputText: TextView
    private lateinit var suggestionText: TextView
    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner

    private var tts: TextToSpeech? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val SPEECH_REQUEST_CODE = 100
    private val RECORD_AUDIO_REQUEST_CODE = 101
    private val CAMERA_REQUEST_CODE = 102
    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private var translationRunnable: Runnable? = null
    private var hideSuggestionRunnable: Runnable? = null
    private var isDialogShowing = false
    private var isProcessingIntermediate = false
    private lateinit var onnxTranslator: OnnxTranslator

    private var cuyononDictionary = mutableMapOf<String, String>()
    private var filipinoToCuyonon = mutableMapOf<String, String>()
    
    private var englishWords = mutableSetOf<String>()
    private var filipinoWords = mutableSetOf<String>()
    private var cuyononWords = mutableSetOf<String>()

    private var enAssets = HashMap<String, String>()
    private var filAssets = HashMap<String, String>()
    private var cuAssets = HashMap<String, String>()

    private val languageIdentifier = LanguageIdentification.getClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_drawer)

        loadDictionaryFromCsv()
        listenToVerifiedWords()
        onnxTranslator = OnnxTranslator(this)

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)
        navView.setNavigationItemSelectedListener(this)

        val btnMenu: ImageButton = findViewById(R.id.btnMenu)
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        val btnSwitch: ImageView = findViewById(R.id.btnSwitch)

        // Set default selection: English (2) to Cuyonon (1) based on the array
        spinnerSource.setSelection(2) // English
        spinnerTarget.setSelection(1) // Cuyonon

        val languageListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (spinnerSource.selectedItemPosition == spinnerTarget.selectedItemPosition) {
                    // Prevent same language selection by switching the other spinner
                    val otherPos = (position + 1) % 3
                    if (parent == spinnerSource) {
                        spinnerTarget.setSelection(otherPos)
                    } else {
                        spinnerSource.setSelection(otherPos)
                    }
                }
                
                // Re-translate and update suggestions if there's text
                val text = inputText.text.toString()
                if (text.trim().isNotEmpty()) {
                    // Cancel any pending auto-translation to prioritize this manual language change
                    translationRunnable?.let { handler.removeCallbacks(it) }
                    
                    // Manual translation trigger for manual language selection
                    performTranslation(text.trim(), isManualTrigger = false)
                    updateSuggestion(text)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerSource.onItemSelectedListener = languageListener
        spinnerTarget.onItemSelectedListener = languageListener

        btnSwitch.setOnClickListener {
            val sourcePos = spinnerSource.selectedItemPosition
            val targetPos = spinnerTarget.selectedItemPosition
            
            val currentInput = inputText.text.toString()
            val currentOutput = outputText.text.toString()

            // Swap text content (if output has placeholder, treat as empty)
            val newIn = if (currentOutput == "Translated text here...") "" else currentOutput
            inputText.setText(newIn)
            
            // Swap spinners (this will also trigger the language change listener)
            spinnerSource.setSelection(targetPos)
            spinnerTarget.setSelection(sourcePos)

            // Temporarily put the old input into the output box
            if (currentInput.isNotEmpty()) {
                outputText.text = currentInput
            } else if (newIn.isEmpty()) {
                outputText.text = "Translated text here..."
            }
            
            // Ensure a translation is triggered for the new input
            if (newIn.trim().isNotEmpty()) {
                performTranslation(newIn.trim(), isManualTrigger = false)
            }
        }

        inputText = findViewById(R.id.inputText)
        outputText = findViewById(R.id.outputText)
        suggestionText = findViewById(R.id.suggestionText)
        val btnClearInput: ImageButton = findViewById(R.id.btnClearInput)

        // Hide suggestion when focus is lost
        inputText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) suggestionText.text = ""
        }

        // Accept suggestion when tapping the input area
        inputText.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val suggestion = suggestionText.text.toString()
                if (suggestion.isNotEmpty() && suggestion.length > inputText.text.length) {
                    inputText.setText(suggestion)
                    inputText.setSelection(suggestion.length)
                    suggestionText.text = ""
                    return@setOnTouchListener true
                }
            }
            false
        }

        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProcessingIntermediate) return
                
                val text = s.toString()
                updateSuggestion(text)
                btnClearInput.visibility = if (text.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

                // Hide suggestion if user stops typing (shorter 1.5s timeout)
                hideSuggestionRunnable?.let { handler.removeCallbacks(it) }
                hideSuggestionRunnable = Runnable {
                    suggestionText.text = ""
                }
                handler.postDelayed(hideSuggestionRunnable!!, 1500)

                translationRunnable?.let { handler.removeCallbacks( it) }
                translationRunnable = Runnable {
                    val trimmedText = text.trim()
                    if (trimmedText.isNotEmpty()) {
                        // Manual typing now bypasses detection and respects your spinner settings
                        performTranslation(trimmedText, isManualTrigger = true)
                    } else {
                        outputText.text = ""
                    }
                }
                handler.postDelayed(translationRunnable!!, 1500) // Increased debounce for auto-translation
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        inputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val text = inputText.text.toString().trim()
                if (text.isNotEmpty()) {
                    identifyAndTranslateIfNeeded(text, isManualTrigger = true)
                }
                true
            } else {
                false
            }
        }

        val btnScan: ImageButton = findViewById(R.id.btnScan)
        val btnSpeak: ImageButton = findViewById(R.id.btnSpeak)
        val btnSpeakInput: ImageButton = findViewById(R.id.btnSpeakInput)
        val btnCopy: ImageButton = findViewById(R.id.btnCopy)
        val btnMic: ImageButton = findViewById(R.id.btnMic)
        val btnCopyInput: ImageButton = findViewById(R.id.btnCopyInput)

        btnClearInput.setOnClickListener {
            inputText.setText("")
            outputText.text = ""
            suggestionText.text = ""
        }

        btnScan.setOnClickListener {
            checkCameraPermissionAndScan()
        }

        btnSpeak.setOnClickListener {
            val text = outputText.text.toString()
            if (text.isNotEmpty() && text != "Translated text here...") {
                speakText(text, isTarget = true)
            }
        }

        btnSpeakInput.setOnClickListener {
            val text = inputText.text.toString()
            if (text.isNotEmpty()) {
                speakText(text, isTarget = false)
            }
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Translated Text", outputText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnCopyInput.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Input Text", inputText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnMic.setOnClickListener {
            checkPermissionAndListen()
        }

        tts = TextToSpeech(this, this)

        // Initialize asset caches to prevent UI lag on sequential recordings
        try {
            val normalizeName = { fileName: String ->
                val dotIndex = fileName.lastIndexOf('.')
                val name = if (dotIndex != -1) fileName.substring(0, dotIndex) else fileName
                name.lowercase()
                    .replace("copy of ", "")
                    .replace("copy", "")
                    .replace(Regex("\\d+$"), "") // Remove numeric suffix like 'ito2'
                    .replace(Regex("\\(.*?\\)"), "") // Remove parenthetical info like '(cow)'
                    .replace(Regex("[\\p{Punct}\\s]"), "") // Remove punctuation and spaces
                    .trim()
            }
            assets.list("recordings/en")?.forEach { enAssets[normalizeName(it)] = it }
            assets.list("recordings/fil")?.forEach { filAssets[normalizeName(it)] = it }
            assets.list("recordings/cu")?.forEach { cuAssets[normalizeName(it)] = it }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        handleIntent(intent)
        checkShowGuidelines()
        checkForUpdates(isManualCheck = false) // Check silently on startup
    }

    private fun checkShowGuidelines() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val dontShowAgain = prefs.getBoolean("dont_show_guidelines", false)

        if (!dontShowAgain) {
            // Post with a small delay so that the activity window is fully active and loaded after splash screen
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    showGuidelinesDialog()
                }
            }, 600)
        }
    }

    private fun showGuidelinesDialog() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_guidelines)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Set dialog width to 90% of screen width to prevent "squished" look
        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        val btnClose = dialog.findViewById<ImageButton>(R.id.btn_close_dialog)
        val cbDontShow = dialog.findViewById<android.widget.CheckBox>(R.id.cb_dont_show_again)

        btnClose.setOnClickListener {
            if (cbDontShow.isChecked) {
                getSharedPreferences("app_settings", MODE_PRIVATE)
                    .edit()
                    .putBoolean("dont_show_guidelines", true)
                    .apply()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        } else {
            Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        } else {
            listen()
        }
    }

    private fun checkCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        } else {
            dispatchTakePictureIntent()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                listen()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        
        // Use default locale but add a wide range of supported foreign languages
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        intent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", 
            arrayOf("zh-CN", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "it-IT", "ru-RU", "hi-IN", "ar-SA", "fil-PH", "th-TH", "vi-VN", "id-ID", "ms-MY"))
        
        // Request multiple results to increase accuracy for foreign scripts
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 15)
        
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in any language")
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                photoFile = try {
                    createImageFile()
                } catch (ex: IOException) {
                    null
                }
                photoFile?.also {
                    photoUri = FileProvider.getUriForFile(
                        this,
                        "com.example.myapplication.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    startActivityForResult(takePictureIntent, CAMERA_REQUEST_CODE)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                identifyAndTranslateIfNeeded(results, isManualTrigger = true)
            }
        } else if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            photoFile?.let {
                val bitmap = BitmapFactory.decodeFile(it.absolutePath)
                recognizeTextFromImage(bitmap)
            }
        }
    }

    private fun identifyAndTranslateIfNeeded(text: String, isManualTrigger: Boolean = true) {
        val list = ArrayList<String>()
        list.add(text)
        identifyAndTranslateIfNeeded(list, isManualTrigger)
    }

    private fun identifyAndTranslateIfNeeded(results: ArrayList<String>, isManualTrigger: Boolean = true) {
        if (results.isEmpty()) return
        
        isProcessingIntermediate = true
        
        val timeoutHandler = Handler(Looper.getMainLooper())
        var hasFinished = false
        val timeoutRunnable = Runnable {
            if (!hasFinished) {
                hasFinished = true
                isProcessingIntermediate = false
                updateInputText(results[0], forceEnglishSource = false, isManualTrigger = isManualTrigger)
            }
        }

        // 1. Script Detection (Unambiguous) - Check all candidates
        var detectedByScript: String? = null
        var bestCandidateIndex = -1
        
        for (i in results.indices) {
            val res = results[i]
            val script = when {
                res.any { it.code in 0xAC00..0xD7AF } -> "ko" // Hangul
                res.any { it.code in 0x3040..0x309F || it.code in 0x30A0..0x30FF } -> "ja" // Hiragana/Katakana
                res.any { it.code in 0x4E00..0x9FFF } -> "zh" // Kanji/Hanzi
                else -> null
            }
            if (script != null) {
                detectedByScript = script
                bestCandidateIndex = i
                break
            }
        }

        if (detectedByScript != null) {
            hasFinished = true
            timeoutHandler.removeCallbacks(timeoutRunnable)
            
            // Reorder to put the script-matched result first
            val orderedResults = ArrayList(results)
            val bestRes = orderedResults.removeAt(bestCandidateIndex)
            orderedResults.add(0, bestRes)
            
            processDetectedLanguages(listOf(detectedByScript), orderedResults, timeoutHandler, timeoutRunnable, isManualTrigger)
            return
        }

        // 2. Quick check for manual mappings (Pinyin/Romaji)
        for (res in results) {
            getManualTranslation(res)?.let { manual ->
                hasFinished = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                // Set language to English for the bridge mapping
                setSpinnerSelection(spinnerSource, "English")
                updateInputText(manual, forceEnglishSource = true, isManualTrigger = isManualTrigger)
                return
            }
        }
        
        timeoutHandler.postDelayed(timeoutRunnable, 8000)

        // 3. ML Kit Language ID (Probabilistic)
        languageIdentifier.identifyLanguage(results[0])
            .addOnSuccessListener { languageCode ->
                if (hasFinished) return@addOnSuccessListener
                
                if (languageCode == "und") {
                    languageIdentifier.identifyPossibleLanguages(results[0])
                        .addOnSuccessListener { languages ->
                            if (hasFinished) return@addOnSuccessListener
                            processDetectedLanguages(languages.map { it.languageTag }, results, timeoutHandler, timeoutRunnable, isManualTrigger)
                        }
                        .addOnFailureListener {
                            if (!hasFinished) {
                                hasFinished = true
                                isProcessingIntermediate = false
                                timeoutHandler.removeCallbacks(timeoutRunnable)
                                // Do not update input text here, wait for translation or timeout
                            }
                        }
                } else {
                    processDetectedLanguages(listOf(languageCode), results, timeoutHandler, timeoutRunnable, isManualTrigger)
                }
            }
            .addOnFailureListener {
                if (!hasFinished) {
                    hasFinished = true
                    isProcessingIntermediate = false
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    // Do not update input text here
                }
            }
    }

    private fun processDetectedLanguages(languageCodes: List<String>, results: ArrayList<String>, timeoutHandler: Handler, timeoutRunnable: Runnable, isManualTrigger: Boolean) {
        val mlKitLanguages = TranslateLanguage.getAllLanguages()
        var bestForeignLang: String? = null

        for (code in languageCodes) {
            val normalizedCode = code.split("-")[0]
            val isEnglish = normalizedCode == "en"
            val isFilipino = normalizedCode == "fil" || normalizedCode == "tl"
            
            if (!isEnglish && !isFilipino && mlKitLanguages.contains(normalizedCode)) {
                bestForeignLang = normalizedCode
                break
            } else if (isEnglish || isFilipino) {
                // If English or Filipino is detected, prefer direct input unless a strong foreign match exists
                if (normalizedCode == "en") {
                    updateInputText(results[0], forceEnglishSource = true, isManualTrigger = isManualTrigger)
                } else {
                    setSpinnerSelection(spinnerSource, "Filipino")
                    updateInputText(results[0], isManualTrigger = isManualTrigger)
                }
                timeoutHandler.removeCallbacks(timeoutRunnable)
                return
            }
        }

        if (bestForeignLang != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            findBestTranslation(results, bestForeignLang, isManualTrigger)
        } else {
            isProcessingIntermediate = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            // Only update if it's already English or if we're sure it's Filipino
            val topResult = results[0]
            val isFilipino = languageCodes.any { it.startsWith("fil") || it.startsWith("tl") }
            if (isFilipino) {
                setSpinnerSelection(spinnerSource, "Filipino")
            } else {
                setSpinnerSelection(spinnerSource, "English")
            }
            updateInputText(topResult, isManualTrigger = isManualTrigger)
        }
    }

    private fun findBestTranslation(candidates: ArrayList<String>, sourceLangCode: String, isManualTrigger: Boolean) {
        val displayLang = try {
            Locale.forLanguageTag(sourceLangCode).displayLanguage
        } catch (e: Exception) {
            sourceLangCode
        }

        val timeoutHandler = Handler(Looper.getMainLooper())
        var isProcessFinished = false
        val timeoutRunnable = Runnable {
            if (!isProcessFinished) {
                isProcessFinished = true
                isProcessingIntermediate = false
                Toast.makeText(this, "Translation timed out (Possible slow internet)", Toast.LENGTH_LONG).show()
                updateInputText(candidates[0], isManualTrigger = isManualTrigger)
            }
        }
        // Increase timeout for model download
        timeoutHandler.postDelayed(timeoutRunnable, 25000)

        Toast.makeText(this, "Detected $displayLang. Translating to English...", Toast.LENGTH_SHORT).show()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)
        
        val model = TranslateRemoteModel.Builder(sourceLangCode).build()

        RemoteModelManager.getInstance().isModelDownloaded(model)
            .addOnSuccessListener { isDownloaded ->
                if (!isDownloaded) {
                    Toast.makeText(this, "Downloading $displayLang translation model. Please wait...", Toast.LENGTH_LONG).show()
                }

                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        if (!isProcessFinished) {
                            isProcessFinished = true
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            tryTranslate(candidates, 0, sourceLangCode, translator, isManualTrigger)
                        } else {
                            translator.close()
                        }
                    }
                    .addOnFailureListener {
                        if (!isProcessFinished) {
                            isProcessFinished = true
                            isProcessingIntermediate = false
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            Toast.makeText(this, "Failed to download $displayLang model", Toast.LENGTH_SHORT).show()
                            updateInputText(candidates[0], isManualTrigger = isManualTrigger)
                            translator.close()
                        }
                    }
            }
            .addOnFailureListener {
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        if (!isProcessFinished) {
                            isProcessFinished = true
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            tryTranslate(candidates, 0, sourceLangCode, translator, isManualTrigger)
                        } else {
                            translator.close()
                        }
                    }
                    .addOnFailureListener {
                        if (!isProcessFinished) {
                            isProcessFinished = true
                            isProcessingIntermediate = false
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            updateInputText(candidates[0], isManualTrigger = isManualTrigger)
                            translator.close()
                        }
                    }
            }
    }

    private fun tryTranslate(candidates: List<String>, index: Int, sourceLangCode: String, translator: com.google.mlkit.nl.translate.Translator, isManualTrigger: Boolean) {
        if (index >= candidates.size || index >= 10) {
            isProcessingIntermediate = false
            // If all translation attempts fail, fall back to English if possible, otherwise original
            updateInputText(candidates[0], isManualTrigger = isManualTrigger)
            translator.close()
            return
        }

        val text = candidates[index]
        if (text.isBlank()) {
            tryTranslate(candidates, index + 1, sourceLangCode, translator, isManualTrigger)
            return
        }

        translator.translate(text)
            .addOnSuccessListener { translatedText ->
                val cleanedTranslated = translatedText.trim()
                val cleanedSource = text.trim()
                
                // If translation happened and is different from source
                if (cleanedTranslated.isNotBlank() && !cleanedTranslated.equals(cleanedSource, ignoreCase = true)) {
                    isProcessingIntermediate = false
                    updateInputText(translatedText, forceEnglishSource = true, isManualTrigger = isManualTrigger)
                    translator.close()
                } else {
                    // Check manual mapping
                    val manual = getManualTranslation(text)
                    if (manual != null) {
                        isProcessingIntermediate = false
                        updateInputText(manual, forceEnglishSource = true, isManualTrigger = isManualTrigger)
                        translator.close()
                    } else {
                        // If this candidate failed to translate, try next
                        tryTranslate(candidates, index + 1, sourceLangCode, translator, isManualTrigger)
                    }
                }
            }
            .addOnFailureListener {
                tryTranslate(candidates, index + 1, sourceLangCode, translator, isManualTrigger)
            }
    }

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
            "sumimasen" to "Excuse me",
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
            "jinjja" to "Really",
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
            "salut" to "Hello",
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
            "aloha" to "Hello",
            "ciao" to "Hello"
        )
        
        // Clean text: remove accents, then remove anything not a letter
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val alphabetOnly = normalized.lowercase()
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z]"), "")
            .trim()
            
        if (alphabetOnly.isEmpty()) return null

        // Try matching against keys (also stripped of spaces/punctuation)
        for ((key, value) in mapping) {
            val cleanKey = key.lowercase().replace(Regex("[^a-z]"), "")
            if (cleanKey == alphabetOnly) return value
        }
            
        return null
    }

    private fun updateInputText(text: String, forceEnglishSource: Boolean = false, isManualTrigger: Boolean = true) {
        if (text.isBlank()) return
        
        if (forceEnglishSource) {
            setSpinnerSelection(spinnerSource, "English")
        }
        
        // Prevent recursive triggers and stop any pending translations
        isProcessingIntermediate = true
        translationRunnable?.let { handler.removeCallbacks(it) }
        
        val currentText = inputText.text.toString()
        if (!currentText.equals(text, ignoreCase = true)) {
            inputText.setText(text)
            // Move cursor to end
            inputText.setSelection(text.length)
        }

        isProcessingIntermediate = false
        performTranslation(text, isManualTrigger = isManualTrigger)
    }

    private fun isKnownElsewhere(word: String): Boolean {
        val lower = word.lowercase().trim()
        return englishWords.contains(lower) || filipinoWords.contains(lower) || cuyononWords.contains(lower)
    }

    private fun performTranslation(text: String, isManualTrigger: Boolean = true) {
        val sourceLang = spinnerSource.selectedItem.toString()
        val targetLang = spinnerTarget.selectedItem.toString()
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // 1. Try CSV / Dictionary First (Improved matching)
        val offlineResult = translateOffline(trimmed, sourceLang, targetLang)
        
        // Check if any word or phrase was actually found in the dictionary, rather than just returning the original text.
        // translateOffline returns the original text if no part of it matches.
        var dictHasMatch = false
        val dictMap: Map<String, String>? = when {
            targetLang == "Cuyonon" -> if (sourceLang == "English") cuyononDictionary else filipinoToCuyonon
            sourceLang == "Cuyonon" -> {
                if (targetLang == "English") {
                    cuyononDictionary.entries.associate { it.value to it.key }
                } else {
                    filipinoToCuyonon.entries.associate { it.value to it.key }
                }
            }
            else -> null
        }
        if (dictMap != null) {
            val wordsList = lower.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < wordsList.size) {
                for (len in Math.min(wordsList.size - i, 5) downTo 1) {
                    val phrase = wordsList.subList(i, i + len).joinToString(" ")
                    if (dictMap.containsKey(phrase)) {
                        dictHasMatch = true
                        break
                    }
                }
                if (dictHasMatch) break
                i++
            }
        }

        if (dictHasMatch) {
            outputText.text = offlineResult
            saveToHistory(trimmed, offlineResult)
            return
        }

        // 2. Check Auto-correct / Add word logic for single words early if entirely unknown in dictionary
        val wordList = when (sourceLang) {
            "English" -> englishWords
            "Filipino" -> filipinoWords
            "Cuyonon" -> cuyononWords
            else -> emptySet()
        }

        if (trimmed.isNotEmpty() && !trimmed.contains(" ") && !isDialogShowing && isManualTrigger) {
            // Check if the word is NOT in the source language's dictionary
            if (!wordList.contains(lower)) {
                val closest = findClosestWord(lower, wordList)
                if (closest != null) {
                    showCorrectionDialog(lower, closest, sourceLang)
                    return
                } else if (!isKnownElsewhere(lower) && !onnxTranslator.knowsWord(lower, sourceLang, targetLang)) {
                    showAddWordDialog(lower, sourceLang)
                    return
                }
            }
        }

        // 3. Try ONNX Model (Second Priority)
        val onnxResult = onnxTranslator.translate(trimmed, sourceLang, targetLang)
        if (onnxResult.isNotEmpty() && !onnxResult.startsWith("Error:")) {
            outputText.text = onnxResult
            saveToHistory(trimmed, onnxResult)
            return
        }

        // 4. Final Fallback
        outputText.text = offlineResult
        saveToHistory(trimmed, offlineResult)
    }



    private fun recognizeTextFromImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val resultText = visionText.text
                if (resultText.isNotEmpty()) {
                    identifyAndTranslateIfNeeded(resultText, isManualTrigger = true)
                } else {
                    Toast.makeText(this, "No text found in image", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Text recognition failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun speakText(text: String, isTarget: Boolean = true) {
        val lang = if (isTarget) spinnerTarget.selectedItem.toString() else spinnerSource.selectedItem.toString()
        
        if (lang == "English" || lang == "Filipino" || lang == "Cuyonon") {
            val langFolder = when (lang) {
                "Filipino" -> "fil"
                "Cuyonon" -> "cu"
                else -> "en"
            }
            val btn = if (isTarget) findViewById<ImageButton>(R.id.btnSpeak) else findViewById<ImageButton>(R.id.btnSpeakInput)
            
            // 1. Try to play as a single phrase recording first
            val success = playSingleWordRecording(text, langFolder) {
                runOnUiThread {
                    btn.setImageResource(R.drawable.ic_speak)
                }
            }
            
            if (success) {
                btn.setImageResource(R.drawable.ic_speak_active)
                return
            }

            // 2. Fallback to sequential recordings if full phrase not found
            val words = text.trim().split(Regex("\\s+|(?=[\\p{Punct}])|(?<=[\\p{Punct}])")).filter { it.isNotBlank() }
            if (words.isNotEmpty()) {
                playSequentialRecordings(words, 0, isTarget)
                return
            }
        }

        val locale = when (lang) {
            "English" -> Locale.US
            "Filipino" -> Locale("fil", "PH")
            else -> Locale.US
        }
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun playSequentialRecordings(words: List<String>, index: Int, isTarget: Boolean) {
        if (index >= words.size) {
            // Finished playing all words, reset icon
            val btn = if (isTarget) findViewById<ImageButton>(R.id.btnSpeak) else findViewById<ImageButton>(R.id.btnSpeakInput)
            btn.setImageResource(R.drawable.ic_speak)
            return
        }

        val btn = if (isTarget) findViewById<ImageButton>(R.id.btnSpeak) else findViewById<ImageButton>(R.id.btnSpeakInput)
        btn.setImageResource(R.drawable.ic_speak_active)

        val lang = if (isTarget) spinnerTarget.selectedItem.toString() else spinnerSource.selectedItem.toString()
        val langFolder = when (lang) {
            "Filipino" -> "fil"
            "Cuyonon" -> "cu"
            else -> "en"
        }

        // Greedy lookahead: Try to match combinations of up to 4 words (e.g., "tag pira")
        for (length in 4 downTo 2) {
            if (index + length <= words.size) {
                val combinedPhrase = words.subList(index, index + length).joinToString(" ").trim()
                // Check if this combined phrase has an exact audio file mapping
                val cleanedPhrase = combinedPhrase.lowercase().replace(Regex("[\\p{Punct}\\s]"), "")
                val assetMap = when (langFolder) {
                    "fil" -> filAssets
                    "cu" -> cuAssets
                    else -> enAssets
                }
                if (assetMap.containsKey(cleanedPhrase)) {
                    if (playSingleWordRecording(combinedPhrase, langFolder) { playSequentialRecordings(words, index + length, isTarget) }) {
                        return
                    }
                }
            }
        }

        // Fallback to single word if no multi-word combination matches
        val word = words[index].trim()
        if (word.isEmpty() || Regex("[\\p{Punct}]+").matches(word)) {
            // Skip empty items or punctuation marks entirely
            playSequentialRecordings(words, index + 1, isTarget)
            return
        }

        if (!playSingleWordRecording(word, langFolder) { playSequentialRecordings(words, index + 1, isTarget) }) {
            // If word has no recording, use TTS for this single word, then move to next
            // Note: Cuyonon has no native TTS, so we use English as a best-effort fallback
            val locale = if (lang == "Filipino") Locale("fil", "PH") else Locale.US
            tts?.language = locale
            
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "word_tts_${System.currentTimeMillis()}")
            
            // Set listener before speaking to catch the completion
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { playSequentialRecordings(words, index + 1, isTarget) }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { playSequentialRecordings(words, index + 1, isTarget) }
                }
            })

            tts?.speak(word, TextToSpeech.QUEUE_ADD, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
        }
    }

    private fun playSingleWordRecording(word: String, langFolder: String, onComplete: () -> Unit): Boolean {
        // Normalize the word by removing all punctuation and spaces to match our normalized cache keys
        val cleaned = word.trim().lowercase().replace(Regex("[\\p{Punct}\\s]"), "")
        if (cleaned.isEmpty()) return false

        try {
            val assetMap = when (langFolder) {
                "fil" -> filAssets
                "cu" -> cuAssets
                else -> enAssets
            }
            
            val actualFileName = assetMap[cleaned]
            
            if (actualFileName != null) {
                mediaPlayer?.release()
                mediaPlayer = android.media.MediaPlayer()
                val descriptor = assets.openFd("recordings/$langFolder/$actualFileName")
                mediaPlayer?.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                descriptor.close()
                mediaPlayer?.setOnCompletionListener { onComplete() }
                mediaPlayer?.prepare()
                mediaPlayer?.start()
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    override fun onDestroy() {
        tts?.let {
            it.stop()
            it.shutdown()
        }
        mediaPlayer?.release()
        languageIdentifier.close()
        onnxTranslator.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            if (it.getBooleanExtra("checkUpdate", false)) {
                checkForUpdates(isManualCheck = true)
            }

            val sourceText = it.getStringExtra("sourceText")
            val targetText = it.getStringExtra("targetText")
            val sourceLang = it.getStringExtra("sourceLang")
            val targetLang = it.getStringExtra("targetLang")

            if (sourceText != null && targetText != null && sourceLang != null && targetLang != null) {
                inputText.setText(sourceText)
                outputText.text = targetText
                
                // Set spinners to matching languages
                setSpinnerSelection(spinnerSource, sourceLang)
                setSpinnerSelection(spinnerTarget, targetLang)
            }
        }
    }

    private fun setSpinnerSelection(spinner: Spinner, value: String) {
        for (i in 0 until spinner.count) {
            if (spinner.getItemAtPosition(i).toString().equals(value, ignoreCase = true)) {
                if (spinner.selectedItemPosition != i) {
                    spinner.setSelection(i)
                }
                break
            }
        }
    }

    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    private fun findClosestWord(word: String, wordList: Set<String>): String? {
        if (wordList.isEmpty()) return null
        var closest: String? = null
        var minDistance = Int.MAX_VALUE
        for (w in wordList) {
            val distance = calculateLevenshteinDistance(word, w)
            if (distance < minDistance) {
                minDistance = distance
                closest = w
            }
        }
        return if (minDistance > 0 && minDistance <= 2) closest else null
    }

    private fun showCorrectionDialog(wrongWord: String, suggestion: String, lang: String) {
        if (isFinishing || isDestroyed) return
        isDialogShowing = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Typo Detected")
            .setMessage("It looks like you typed '$wrongWord'. Did you mean '$suggestion'?")
            .setPositiveButton("Yes, use '$suggestion'") { _, _ ->
                inputText.setText(suggestion)
                inputText.setSelection(suggestion.length)
                isDialogShowing = false
            }
            .setNegativeButton("No, suggest '$wrongWord'") { _, _ ->
                isDialogShowing = false
                showAddWordDialog(wrongWord, lang)
            }
            .setNeutralButton("Ignore") { _, _ -> isDialogShowing = false }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun showAddWordDialog(word: String, lang: String) {
        if (isFinishing || isDestroyed) return
        isDialogShowing = true

        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_suggest_word)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        val labelSourceLanguage = dialog.findViewById<TextView>(R.id.label_source_language)
        val labelTranslation1 = dialog.findViewById<TextView>(R.id.label_translation_1)
        val labelTranslation2 = dialog.findViewById<TextView>(R.id.label_translation_2)
        val editWord = dialog.findViewById<EditText>(R.id.edit_word)
        val editTranslation1 = dialog.findViewById<EditText>(R.id.edit_translation_1)
        val editTranslation2 = dialog.findViewById<EditText>(R.id.edit_translation_2)
        val editDescription = dialog.findViewById<EditText>(R.id.edit_description)
        val btnSubmit = dialog.findViewById<android.widget.Button>(R.id.btn_submit_suggestion)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btn_close_dialog)

        // Set the source language text dynamically
        labelSourceLanguage.text = getString(R.string.label_language_source, lang)

        // Set specific target labels based on source language
        val targets = when (lang) {
            "English" -> Pair("Filipino Translation (Optional)", "Cuyonon Translation (Optional)")
            "Filipino" -> Pair("English Translation (Optional)", "Cuyonon Translation (Optional)")
            "Cuyonon" -> Pair("English Translation (Optional)", "Filipino Translation (Optional)")
            else -> Pair("Translation 1 (Optional)", "Translation 2 (Optional)")
        }
        labelTranslation1.text = targets.first
        labelTranslation2.text = targets.second

        editWord.setText(word)

        btnSubmit.setOnClickListener {
            val suggestedWord = editWord.text.toString().trim()
            val translation1 = editTranslation1.text.toString().trim()
            val translation2 = editTranslation2.text.toString().trim()
            val description = editDescription.text.toString().trim()

            if (suggestedWord.isEmpty()) {
                Toast.makeText(this, "Please fill in the word", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendWordToDeveloper(lang, suggestedWord, translation1, translation2, description)
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener { isDialogShowing = false }
        dialog.show()
    }

    private fun sendWordToDeveloper(sourceLang: String, word: String, t1: String, t2: String, description: String) {
        val db = Firebase.firestore
        val suggestion = hashMapOf(
            "sourceLang" to sourceLang,
            "word" to word.trim(),
            "translation1" to t1,
            "translation2" to t2,
            "description" to description,
            "timestamp" to System.currentTimeMillis(),
            "status" to "pending"
        )

        db.collection("suggestions")
            .add(suggestion)
            .addOnSuccessListener {
                Toast.makeText(this, "Success! '$word' sent to Moderator.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Connection Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateSuggestion(text: String) {
        if (text.isEmpty()) {
            suggestionText.text = ""
            return
        }

        val sourceLang = spinnerSource.selectedItem.toString()
        val wordList = when (sourceLang) {
            "English" -> englishWords
            "Filipino" -> filipinoWords
            "Cuyonon" -> cuyononWords
            else -> emptySet<String>()
        }

        val lowerText = text.lowercase()
        // Find the first word/phrase that starts with the input but isn't identical
        val suggestion = wordList.find { it.startsWith(lowerText) && it != lowerText }

        if (suggestion != null) {
            // Match the case: use the user's typed text + the remaining part of the suggestion
            val result = text + suggestion.substring(text.length)
            suggestionText.text = result
        } else {
            suggestionText.text = ""
        }
    }

    private fun loadDictionaryFromCsv() {
        // Load original wordlist
        loadCsvFile("wordlist.csv", isTriple = true)
        // Load new English-Cuyonon
        loadCsvFile("en-cuy.csv", isTriple = false, sourceIsEnglish = true)
        // Load new Tagalog-Cuyonon
        loadCsvFile("tag-cuy.csv", isTriple = false, sourceIsEnglish = false)
    }

    private fun loadCsvFile(fileName: String, isTriple: Boolean, sourceIsEnglish: Boolean = true) {
        try {
            val inputStream = assets.open(fileName)
            val reader = inputStream.bufferedReader()
            val lines = reader.readLines()
            if (lines.isEmpty()) return

            lines.drop(1).forEach { line ->
                // Simple regex to handle CSV with quotes and commas
                val tokens = line.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
                if (isTriple && tokens.size >= 3) {
                    val english = tokens[0].trim().replace("\"", "").lowercase()
                    val filipino = tokens[1].trim().replace("\"", "").lowercase()
                    val cuyonon = tokens[2].trim().replace("\"", "").lowercase()

                    if (english.isNotEmpty()) {
                        englishWords.add(english)
                        if (cuyonon.isNotEmpty()) cuyononDictionary[english] = cuyonon
                    }
                    if (filipino.isNotEmpty()) {
                        filipinoWords.add(filipino)
                        if (cuyonon.isNotEmpty()) filipinoToCuyonon[filipino] = cuyonon
                    }
                    if (cuyonon.isNotEmpty()) cuyononWords.add(cuyonon)
                } else if (!isTriple && tokens.size >= 2) {
                    val source = tokens[0].trim().replace("\"", "").lowercase()
                    val cuyonon = tokens[1].trim().replace("\"", "").lowercase()

                    if (source.isNotEmpty() && cuyonon.isNotEmpty()) {
                        if (sourceIsEnglish) {
                            englishWords.add(source)
                            cuyononDictionary[source] = cuyonon
                        } else {
                            filipinoWords.add(source)
                            filipinoToCuyonon[source] = cuyonon
                        }
                        cuyononWords.add(cuyonon)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun listenToVerifiedWords() {
        val db = Firebase.firestore
        db.collection("verified_words").addSnapshotListener { snapshots, e ->
            if (e != null) return@addSnapshotListener
            
            snapshots?.forEach { doc ->
                val word = doc.getString("word")?.lowercase()?.trim() ?: ""
                val sourceLang = doc.getString("sourceLang") ?: ""
                val t1 = doc.getString("translation1")?.trim() ?: ""
                val t2 = doc.getString("translation2")?.trim() ?: ""
                
                if (word.isNotEmpty()) {
                    when (sourceLang) {
                        "English" -> {
                            englishWords.add(word)
                            if (t2.isNotEmpty()) cuyononDictionary[word] = t2 // Cuyonon is t2 for English
                            // If we had an English-to-Filipino map, we'd put t1 there
                        }
                        "Filipino" -> {
                            filipinoWords.add(word)
                            if (t2.isNotEmpty()) filipinoToCuyonon[word] = t2 // Cuyonon is t2 for Filipino
                        }
                        "Cuyonon" -> {
                            cuyononWords.add(word)
                            if (t1.isNotEmpty()) {
                                // Add to a reverse map if needed, 
                                // currently translateOffline handles Cuyonon via reverse lookup of English/Filipino maps
                                // So we should actually add the translations to the primary maps
                                cuyononDictionary[t1] = word // t1 is English
                                filipinoToCuyonon[t2] = word // t2 is Filipino
                            }
                        }
                    }
                }
            }
        }
    }

    private fun translateOffline(text: String, source: String, target: String): String {
        val lowerText = text.lowercase().trim()

        // Get the appropriate dictionary map
        val dict: Map<String, String>? = when {
            target == "Cuyonon" -> if (source == "English") cuyononDictionary else filipinoToCuyonon
            source == "Cuyonon" -> {
                if (target == "English") {
                    cuyononDictionary.entries.associate { it.value to it.key }
                } else {
                    filipinoToCuyonon.entries.associate { it.value to it.key }
                }
            }
            else -> null // Fallback to hardcoded English-Filipino below
        }

        val wordList = when {
            source == "English" && target == "Cuyonon" -> englishWords
            source == "Filipino" && target == "Cuyonon" -> filipinoWords
            source == "Cuyonon" -> cuyononWords
            else -> emptySet()
        }

        if (dict != null) {
            // 1. Longest Phrase Match (Improved Algorithm)
            val words = lowerText.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val result = mutableListOf<String>()
            var i = 0
            while (i < words.size) {
                var foundMatch = false
                // Try to find the longest phrase starting at index i
                for (len in Math.min(words.size - i, 5) downTo 1) {
                    val phrase = words.subList(i, i + len).joinToString(" ")
                    if (dict.containsKey(phrase)) {
                        result.add(dict[phrase]!!)
                        i += len
                        foundMatch = true
                        break
                    }
                }
                
                if (!foundMatch) {
                    result.add(words[i]) // Keep original if no exact match
                    i++
                }
            }
            
            val combined = result.joinToString(" ").trim()
            if (combined.isNotEmpty()) {
                return combined
            }
        }

        // Fallback to old hardcoded logic for English-Filipino
        val staticDictionary = mapOf(
            "English-Filipino" to mapOf(
                "hello" to "kumusta",
                "good morning" to "magandang umaga",
                "good afternoon" to "magandang hapon",
                "good evening" to "magandang gabi",
                "thank you" to "salamat",
                "thank you very much" to "maraming salamat",
                "goodbye" to "paalam",
                "how much" to "magkano",
                "where is" to "nasaan ang",
                "water" to "tubig",
                "food" to "pagkain",
                "eat" to "kain",
                "yes" to "oo",
                "no" to "hindi",
                "i love you" to "mahal kita",
                "help" to "tulong",
                "friend" to "kaibigan",
                "beautiful" to "maganda",
                "happy" to "masaya",
                "sorry" to "patawad",
                "what" to "ano",
                "this" to "ito",
                "what is this" to "ano ito"
            ),
            "Filipino-English" to mapOf(
                "kumusta" to "hello",
                "magandang umaga" to "good morning",
                "magandang hapon" to "good afternoon",
                "magandang gabi" to "good evening",
                "salamat" to "thank you",
                "maraming salamat" to "thank you very much",
                "paalam" to "goodbye",
                "magkano" to "how much",
                "nasaan ang" to "where is",
                "tubig" to "water",
                "pagkain" to "food",
                "kain" to "eat",
                "oo" to "yes",
                "hindi" to "no",
                "mahal kita" to "i love you",
                "tulong" to "help",
                "kaibigan" to "friend",
                "maganda" to "beautiful",
                "masaya" to "happy",
                "patawad" to "sorry",
                "ano" to "what",
                "ito" to "this",
                "ano ito" to "what is this?"
            )
        )

        val key = "$source-$target"
        val langDict = staticDictionary[key] ?: return text
        
        // Try exact match or word-by-word for static dict
        langDict[lowerText]?.let { return it }
        return translateByWords(lowerText, langDict)
    }

    private fun translateByWords(text: String, dict: Map<String, String>, wordList: Set<String> = emptySet()): String {
        // Handle common punctuation and spaces
        val tokens = text.split(Regex("(?<=\\s)|(?=\\s)|(?<=[\\p{Punct}])|(?=[[\\p{Punct}]])"))
        if (tokens.size > 1) {
            val translatedParts = tokens.map { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty() || Regex("[\\p{Punct}]").matches(trimmed)) {
                    part
                } else {
                    val lower = trimmed.lowercase()
                    // 1. Exact match for the word
                    var translation = dict[lower]
                    
                    // 2. Fuzzy match for the word if not found and wordList is provided
                    if (translation == null && wordList.isNotEmpty()) {
                        findClosestWord(lower, wordList)?.let { closest ->
                            translation = dict[closest]
                        }
                    }

                    if (translation != null) {
                        if (part.isNotEmpty() && part.first().isUpperCase()) {
                            translation!!.replaceFirstChar { it.uppercase() }
                        } else {
                            translation!!
                        }
                    } else {
                        part
                    }
                }
            }
            return translatedParts.joinToString("").replace(Regex("\\s+"), " ").trim()
        }
        return text
    }

    private fun saveToHistory(source: String, target: String) {
        if (source.isBlank() || target.isBlank() || target == "Translated text here...") return

        val prefs = getSharedPreferences("translation_history", MODE_PRIVATE)
        val historyJson = prefs.getString("history_list", "[]")
        val historyArray = JSONArray(historyJson)

        val newItem = JSONObject().apply {
            put("sourceText", source)
            put("targetText", target)
            put("sourceLang", spinnerSource.selectedItem.toString())
            put("targetLang", spinnerTarget.selectedItem.toString())
            put("timestamp", System.currentTimeMillis())
        }

        // Add to the beginning of the list
        val newList = JSONArray()
        newList.put(newItem)
        for (i in 0 until historyArray.length()) {
            if (i < 49) { // Keep last 50 items
                newList.put(historyArray.get(i))
            }
        }

        prefs.edit().putString("history_list", newList.toString()).apply()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
            }
            R.id.nav_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
            }
            R.id.nav_basic_phrases -> {
                startActivity(Intent(this, BasicPhrasesActivity::class.java))
            }
            R.id.nav_how_to_use -> {
                startActivity(Intent(this, HowToUseActivity::class.java))
            }
            R.id.nav_update -> {
                checkForUpdates(isManualCheck = true) // Show feedback when clicked
            }
            R.id.nav_home -> {
                // Already on Home
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun checkForUpdates(isManualCheck: Boolean) {
        val versionUrl = "https://raw.githubusercontent.com/reign161826/trans_app/master/version.json"
        
        if (isManualCheck) {
            runOnUiThread {
                Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
            }
        }

        thread {
            try {
                val url = URL(versionUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                val remoteVersionCode = json.getInt("versionCode")
                val downloadUrl = json.getString("downloadUrl")
                val releaseNotes = json.optString("releaseNotes", "New version available!")

                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode
                }

                runOnUiThread {
                    if (remoteVersionCode > currentVersionCode) {
                        showUpdateDialog(downloadUrl, releaseNotes)
                    } else if (isManualCheck) {
                        Toast.makeText(this, "App is up to date", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isManualCheck) {
                    runOnUiThread {
                        Toast.makeText(this, "Update check failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(downloadUrl: String, notes: String) {
        if (isFinishing || isDestroyed) return
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_update)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        val textReleaseNotes = dialog.findViewById<TextView>(R.id.text_release_notes)
        val btnLater = dialog.findViewById<android.widget.Button>(R.id.btn_later)
        val btnDownload = dialog.findViewById<android.widget.Button>(R.id.btn_download)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btn_close_dialog)

        textReleaseNotes.text = notes

        btnDownload.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
            startActivity(intent)
            dialog.dismiss()
        }

        btnLater.setOnClickListener {
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
