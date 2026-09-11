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

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, TextToSpeech.OnInitListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var inputText: EditText
    private lateinit var outputText: TextView
    private lateinit var suggestionText: TextView
    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner

    private var tts: TextToSpeech? = null
    private val SPEECH_REQUEST_CODE = 100
    private val RECORD_AUDIO_REQUEST_CODE = 101
    private val CAMERA_REQUEST_CODE = 102
    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private var translationRunnable: Runnable? = null
    private var hideSuggestionRunnable: Runnable? = null
    private var isDialogShowing = false
    private lateinit var onnxTranslator: OnnxTranslator

    private var cuyononDictionary = mutableMapOf<String, String>()
    private var filipinoToCuyonon = mutableMapOf<String, String>()
    
    private var englishWords = mutableSetOf<String>()
    private var filipinoWords = mutableSetOf<String>()
    private var cuyononWords = mutableSetOf<String>()

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
                    performTranslation(text.trim())
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
            spinnerSource.setSelection(targetPos)
            spinnerTarget.setSelection(sourcePos)
        }

        inputText = findViewById(R.id.inputText)
        outputText = findViewById(R.id.outputText)
        suggestionText = findViewById(R.id.suggestionText)

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
                val text = s.toString()
                updateSuggestion(text)
                btnClearInput.visibility = if (text.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

                // Hide suggestion if user stops typing (shorter 1.5s timeout)
                hideSuggestionRunnable?.let { handler.removeCallbacks(it) }
                hideSuggestionRunnable = Runnable {
                    suggestionText.text = ""
                }
                handler.postDelayed(hideSuggestionRunnable!!, 1500)

                translationRunnable?.let { handler.removeCallbacks(it) }
                translationRunnable = Runnable {
                    val trimmedText = text.trim()
                    if (trimmedText.isNotEmpty()) {
                        performTranslation(trimmedText, isManualTrigger = false)
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
                    performTranslation(text, isManualTrigger = true)
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
        val btnClearInput: ImageButton = findViewById(R.id.btnClearInput)

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
                btnSpeak.setImageResource(R.drawable.ic_speak_active)
                handler.postDelayed({
                    btnSpeak.setImageResource(R.drawable.ic_speak)
                }, 2000)
            }
        }

        btnSpeakInput.setOnClickListener {
            val text = inputText.text.toString()
            if (text.isNotEmpty()) {
                speakText(text, isTarget = false)
                btnSpeakInput.setImageResource(R.drawable.ic_speak_active)
                handler.postDelayed({
                    btnSpeakInput.setImageResource(R.drawable.ic_speak)
                }, 2000)
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

        handleIntent(intent)
        checkShowGuidelines()
    }

    private fun checkShowGuidelines() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val dontShowAgain = prefs.getBoolean("dont_show_guidelines", false)

        if (!dontShowAgain) {
            showGuidelinesDialog()
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
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
            val result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!result.isNullOrEmpty()) {
                inputText.setText(result[0])
                performTranslation(result[0], isManualTrigger = true)
            }
        } else if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            photoFile?.let {
                val bitmap = BitmapFactory.decodeFile(it.absolutePath)
                recognizeTextFromImage(bitmap)
            }
        }
    }

    private fun recognizeTextFromImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val resultText = visionText.text
                if (resultText.isNotEmpty()) {
                    inputText.setText(resultText)
                    performTranslation(resultText, isManualTrigger = true)
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
        val locale = when (lang) {
            "English" -> Locale.US
            "Filipino" -> Locale("fil", "PH")
            else -> Locale.US
        }
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        tts?.let {
            it.stop()
            it.shutdown()
        }
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
                spinner.setSelection(i)
                break
            }
        }
    }

    private fun performTranslation(text: String, isManualTrigger: Boolean = true) {
        val sourceLang = spinnerSource.selectedItem.toString()
        val targetLang = spinnerTarget.selectedItem.toString()
        
        // Auto-correct / Add word logic for single words
        val trimmed = text.trim()
        val suggestion = suggestionText.text.toString()
        
        val lower = trimmed.lowercase()
        val wordList = when (sourceLang) {
            "English" -> englishWords
            "Filipino" -> filipinoWords
            "Cuyonon" -> cuyononWords
            else -> emptySet()
        }

        if (wordList.contains(lower)) {
            val translated = translateOffline(text, sourceLang, targetLang)
            outputText.text = translated
            saveToHistory(text, translated)
            return
        }

        // Only show dialogs if it's a single word, not already covered by an autocomplete suggestion,
        // and if it's either a manual trigger (like clicking a list item) or a long enough pause.
        if (trimmed.isNotEmpty() && !trimmed.contains(" ") && !isDialogShowing && suggestion.isEmpty()) {
            val closest = findClosestWord(lower, wordList)
            if (closest != null) {
                showCorrectionDialog(lower, closest, sourceLang)
            } else {
                // Show "Add to Dictionary" when the user stops typing or triggers it manually
                showAddWordDialog(lower, sourceLang)
            }
        }

        // Try ONNX Model first for supported pairs
        val onnxResult = onnxTranslator.translate(text, sourceLang, targetLang)
        if (onnxResult.isNotEmpty() && !onnxResult.startsWith("Error:")) {
            outputText.text = onnxResult
            saveToHistory(text, onnxResult)
            return
        }

        val translated = translateOffline(text, sourceLang, targetLang)
        outputText.text = translated
        saveToHistory(text, translated)
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

        val editWord = dialog.findViewById<EditText>(R.id.edit_word)
        val editTranslation1 = dialog.findViewById<EditText>(R.id.edit_translation_1)
        val editTranslation2 = dialog.findViewById<EditText>(R.id.edit_translation_2)
        val editDescription = dialog.findViewById<EditText>(R.id.edit_description)
        val btnSubmit = dialog.findViewById<android.widget.Button>(R.id.btn_submit_suggestion)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btn_close_dialog)

        editWord.setText(word)

        btnSubmit.setOnClickListener {
            val suggestedWord = editWord.text.toString().trim()
            val translation1 = editTranslation1.text.toString().trim()
            val translation2 = editTranslation2.text.toString().trim()
            val description = editDescription.text.toString().trim()

            if (suggestedWord.isEmpty() || (translation1.isEmpty() && translation2.isEmpty())) {
                Toast.makeText(this, "Please fill in the word and at least one translation", Toast.LENGTH_SHORT).show()
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
        try {
            val inputStream = assets.open("wordlist.csv")
            val reader = inputStream.bufferedReader()
            reader.useLines { lines ->
                lines.toList().drop(1).forEach { line ->
                    val tokens = line.split(",")
                    if (tokens.size >= 3) {
                        val english = tokens[0].trim().lowercase()
                        val filipino = tokens[1].trim().lowercase()
                        val cuyonon = tokens[2].trim().lowercase()

                        if (english.isNotEmpty()) {
                            englishWords.add(english)
                            if (cuyonon.isNotEmpty()) cuyononDictionary[english] = cuyonon
                        }
                        if (filipino.isNotEmpty()) {
                            filipinoWords.add(filipino)
                            if (cuyonon.isNotEmpty()) filipinoToCuyonon[filipino] = cuyonon
                        }
                        if (cuyonon.isNotEmpty()) {
                            cuyononWords.add(cuyonon)
                        }
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

        if (target == "Cuyonon") {
            if (source == "English") {
                return cuyononDictionary[lowerText] ?: translateByWords(lowerText, cuyononDictionary)
            } else if (source == "Filipino") {
                return filipinoToCuyonon[lowerText] ?: translateByWords(lowerText, filipinoToCuyonon)
            }
        } else if (source == "Cuyonon") {
            // Reverse lookup for Cuyonon to English/Filipino
            val targetMap = if (target == "English") {
                cuyononDictionary.entries.associate { it.value to it.key }
            } else {
                filipinoToCuyonon.entries.associate { it.value to it.key }
            }
            return targetMap[lowerText] ?: translateByWords(lowerText, targetMap)
        }

        // Fallback to old hardcoded logic for English-Filipino
        val dictionary = mapOf(
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
        val langDict = dictionary[key] ?: return text
        
        // 1. Try to find exact match
        langDict[lowerText]?.let { return it }
        
        // 2. Word-by-word fallback
        return translateByWords(lowerText, langDict)
    }

    private fun translateByWords(text: String, dict: Map<String, String>): String {
        val words = text.split("\\s+".toRegex())
        if (words.size > 1) {
            val translatedWords = words.map { word ->
                dict[word] ?: word
            }
            return translatedWords.joinToString(" ")
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
            R.id.nav_home -> {
                // Already on Home
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
