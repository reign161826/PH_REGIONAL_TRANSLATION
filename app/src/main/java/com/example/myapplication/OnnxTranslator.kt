package com.example.myapplication

import android.content.Context
import ai.onnxruntime.*
import java.nio.LongBuffer
import java.util.*
import org.json.JSONObject

class OnnxTranslator(private val context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    
    private var currentPair: String? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private val vocab = mutableMapOf<String, Long>()
    private val invertedVocab = mutableMapOf<Long, String>()

    // Model constants (shared across these MarianMT models)
    private val decoderStartTokenId = 57372L
    private val eosTokenId = 0L
    private val padTokenId = 57372L
    private val maxSequenceLength = 64

    private fun loadModel(pair: String) {
        if (currentPair == pair) return

        // Close old sessions
        encoderSession?.close()
        decoderSession?.close()
        vocab.clear()
        invertedVocab.clear()

        val folder = when (pair) {
            "en-cu" -> "onnx/en_cu"
            "en-tl" -> "onnx/en_tl"
            "tg-cu" -> "onnx/tg_cu"
            else -> throw IllegalArgumentException("Unsupported language pair: $pair")
        }


        val opts = OrtSession.SessionOptions()
        encoderSession = env.createSession(getModelPath("$folder/encoder_model.onnx"), opts)
        decoderSession = env.createSession(getModelPath("$folder/decoder_model.onnx"), opts)

        loadVocab("$folder/vocab.json")
        currentPair = pair
    }

    private fun getModelPath(assetName: String): String {
        val fileName = assetName.replace("/", "_")
        val file = java.io.File(context.cacheDir, fileName)
        if (!file.exists()) {
            context.assets.open(assetName).use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file.absolutePath
    }

    private fun loadVocab(vocabPath: String) {
        try {
            val jsonString = context.assets.open(vocabPath).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = json.getLong(key)
                vocab[key] = id
                invertedVocab[id] = key
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getPair(sourceLang: String, targetLang: String): String? {
        return when {
            (sourceLang == "English" && targetLang == "Cuyonon") || 
            (sourceLang == "Cuyonon" && targetLang == "English") -> "en-cu"
            (sourceLang == "English" && targetLang == "Tagalog") || 
            (sourceLang == "English" && targetLang == "Filipino") ||
            ((targetLang == "Tagalog" || targetLang == "Filipino") && sourceLang == "English") -> "en-tl"
            (sourceLang == "Filipino" && targetLang == "Cuyonon") ||
            (sourceLang == "Tagalog" && targetLang == "Cuyonon") ||
            (sourceLang == "Cuyonon" && (targetLang == "Filipino" || targetLang == "Tagalog")) -> "tg-cu"
            else -> null
        }
    }



    /**
     * Checks if the model's internal vocabulary contains this word.
     */
    fun knowsWord(word: String, sourceLang: String, targetLang: String): Boolean {
        val pair = getPair(sourceLang, targetLang) ?: return false
        try {
            loadModel(pair)
            val lower = word.lowercase().trim()
            // In MarianMT models, whole words are often stored with a special prefix 
            return vocab.containsKey(lower) || vocab.containsKey("\u2581$lower")
        } catch (e: Exception) {
            return false
        }
    }

    fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return ""

        val pair = getPair(sourceLang, targetLang) ?: return ""

        return try {
            loadModel(pair)
            
            val inputIds = tokenize(text)
            val inputShape = longArrayOf(1, inputIds.size.toLong())
            val attentionMask = LongArray(inputIds.size) { 1L }
            
            val encoderInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), inputShape)
            val encoderMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), inputShape)
            
            val encoderInputs = mutableMapOf<String, OnnxTensor>()
            encoderInputs["input_ids"] = encoderInputTensor
            encoderInputs["attention_mask"] = encoderMaskTensor
            
            val encoderOutputs = encoderSession!!.run(encoderInputs)
            val lastHiddenState = encoderOutputs.get(0) as OnnxTensor
            
            var currentTokens = mutableListOf(decoderStartTokenId)
            
            for (i in 0 until maxSequenceLength) {
                val decoderInputShape = longArrayOf(1, currentTokens.size.toLong())
                val decoderInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(currentTokens.toLongArray()), decoderInputShape)
                
                val decoderInputs = mutableMapOf<String, OnnxTensor>()
                decoderInputs["input_ids"] = decoderInputTensor
                decoderInputs["encoder_hidden_states"] = lastHiddenState
                decoderInputs["encoder_attention_mask"] = encoderMaskTensor
                
                val decoderOutputs: OrtSession.Result = try {
                    decoderSession!!.run(decoderInputs)
                } catch (e: Exception) {
                    decoderInputs.remove("encoder_attention_mask")
                    decoderInputs["attention_mask"] = encoderMaskTensor
                    decoderSession!!.run(decoderInputs)
                }

                val logits = decoderOutputs.get(0) as OnnxTensor
                val nextToken = getArgMaxFromLogits(logits)
                
                decoderOutputs.close()
                decoderInputTensor.close()

                if (nextToken == eosTokenId) {
                    break
                }
                currentTokens.add(nextToken)
            }
            
            encoderInputTensor.close()
            encoderMaskTensor.close()
            encoderOutputs.close()
            
            detokenize(currentTokens)
        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }

    private fun tokenize(text: String): LongArray {
        val words = text.lowercase().split(Regex("\\s+"))
        val tokens = mutableListOf<Long>()
        for (word in words) {
            val spmWord = "\u2581" + word
            tokens.add(vocab[spmWord] ?: vocab[word] ?: 1L) 
        }
        tokens.add(eosTokenId)
        return tokens.toLongArray()
    }

    private fun detokenize(tokens: List<Long>): String {
        val result = StringBuilder()
        for (token in tokens) {
            if (token == decoderStartTokenId || token == eosTokenId || token == padTokenId) continue
            val word = invertedVocab[token] ?: ""
            result.append(word.replace("\u2581", " "))
        }
        return result.toString().trim()
    }

    private fun getArgMaxFromLogits(logits: OnnxTensor): Long {
        val floatBuffer = logits.floatBuffer
        val shape = logits.info.shape
        val seqLen = shape[1].toInt()
        val vocabSize = shape[2].toInt()
        val lastTokenPos = (seqLen - 1) * vocabSize
        var maxIdx = -1
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val value = floatBuffer.get(lastTokenPos + i)
            if (value > maxVal) {
                maxVal = value
                maxIdx = i
            }
        }
        return maxIdx.toLong()
    }

    override fun close() {
        encoderSession?.close()
        decoderSession?.close()
        env.close()
    }
}
