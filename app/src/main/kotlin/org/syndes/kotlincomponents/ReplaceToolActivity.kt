package org.syndes.kotlincomponents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.text.RegexOption
import android.view.WindowManager

class ReplaceToolActivity : AppCompatActivity() {

    private val REQUEST_TREE = 42

    private var pickedTreeUri: Uri? = null
    private var pickedRoot: DocumentFile? = null

    private lateinit var etFind: EditText
    private lateinit var etReplace: EditText
    private lateinit var etParams: EditText
    private lateinit var btnChoose: Button
    private lateinit var btnReplace: Button
    private lateinit var tvStatus: TextView
    private lateinit var cbPreviewOnly: CheckBox

    // overlay views
    private var overlay: FrameLayout? = null

    // preview / data classes
    private data class MatchPreview(val lineNumber: Int, val lineText: String, val replacedLine: String)
    private data class FilePreview(val fileName: String?, val uri: Uri, val matches: List<MatchPreview>)
    private data class ReplaceResult(val filesModified: Int, val totalReplacements: Int, val previews: List<FilePreview> = emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        
        setContentView(R.layout.activity_replace_tool)

        etFind = findViewById(R.id.etFind)
        etReplace = findViewById(R.id.etReplace)
        etParams = findViewById(R.id.etParams)
        btnChoose = findViewById(R.id.btnChoose)
        btnReplace = findViewById(R.id.btnReplace)
        tvStatus = findViewById(R.id.tvStatus)
        cbPreviewOnly = findViewById(R.id.cbPreviewOnly)

        btnChoose.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_TREE)
        }

        btnReplace.setOnClickListener {
            val find = etFind.text.toString()
            val replace = etReplace.text.toString()
            val params = etParams.text.toString()
            if (find.isEmpty()) {
                Toast.makeText(this, "Поле поиска не может быть пустым", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickedRoot == null) {
                Toast.makeText(this, "Пожалуйста, выберите папку с помощью кнопки 'Выбрать папку' (SAF)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tokens = params.split(Regex("\\s+")).filter { it.isNotBlank() }
            val recursive = tokens.any { it == "-r" }
            val ignoreCase = tokens.any { it == "-i" }
            val regexMode = tokens.any { it == "-e" }
            val wholeWord = tokens.any { it == "-w" }
            val showLineNumbers = tokens.any { it == "-n" }
            val dryRun = tokens.any { it == "--dry-run" }
            val previewOnly = cbPreviewOnly.isChecked

            showOverlay()
            tvStatus.text = "Сканирование..."
            lifecycleScope.launchWhenStarted {
                val scanResult = withContext(Dispatchers.IO) {
                    processReplace(
                        pickedRoot!!, find, replace, recursive, ignoreCase,
                        regexMode, wholeWord, showLineNumbers,
                        dryRun = dryRun || previewOnly,
                        collectPreview = previewOnly
                    )
                }
                hideOverlay()

                if (previewOnly) {
                    showPreviewDialog(
                        scanResult, find, replace, recursive, ignoreCase,
                        regexMode, wholeWord, showLineNumbers
                    )
                } else {
                    if (dryRun) {
                        val msg = "Пробный запуск (dry-run). Файлов будет изменено: ${scanResult.filesModified}, Замен: ${scanResult.totalReplacements}"
                        tvStatus.text = msg
                        Toast.makeText(this@ReplaceToolActivity, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val msg = "Готово. Файлов изменено: ${scanResult.filesModified}, Замен: ${scanResult.totalReplacements}"
                        tvStatus.text = msg
                        Toast.makeText(this@ReplaceToolActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                pickedTreeUri = uri
                pickedRoot = DocumentFile.fromTreeUri(this, uri)
                tvStatus.text = "Выбрано: ${pickedRoot?.name ?: uri.path}"
            }
        }
    }

    private fun processReplace(
        root: DocumentFile,
        find: String,
        replace: String,
        recursive: Boolean,
        ignoreCase: Boolean,
        regexMode: Boolean,
        wholeWord: Boolean,
        showLineNumbers: Boolean,
        dryRun: Boolean,
        collectPreview: Boolean,
        previewLimitPerFile: Int = 5
    ): ReplaceResult {
        var filesModified = 0
        var totalReplacements = 0
        val previews = mutableListOf<FilePreview>()

        fun buildPattern(): Regex {
            val base = if (regexMode) find else Regex.escape(find)
            val wrapped = if (wholeWord) "\\b(?:$base)\\b" else base
            val options = mutableSetOf<RegexOption>()
            if (ignoreCase) options.add(RegexOption.IGNORE_CASE)
            return if (options.isEmpty()) Regex(wrapped) else Regex(wrapped, options)
        }

        val pattern = try {
            buildPattern()
        } catch (e: Exception) {
            // Некорректное регулярное выражение, прерываем выполнение
            return ReplaceResult(0, 0, emptyList())
        }

        // 1. Сначала определяем поддержку по расширению (чтобы корректно обрабатывать mime-типы)
        fun isProbablyTextByExt(name: String?): Boolean {
            if (name == null) return false
            val lower = name.lowercase()
            val textExt = listOf(
                // Основные текстовые и разметка
                ".txt", ".md", ".xml", ".xhtml", ".html", ".htm", ".csv",
                // Конфигурации и данные
                ".json", ".properties", ".yml", ".yaml", ".toml", ".ini", ".conf", ".cfg",
                // Специфичные форматы (из вашего запроса)
                ".gradle", ".oml", ".syd", ".ft", ".fst",
                // Языки программирования (JVM, C-семейство, Go, Rust)
                ".java", ".kt", ".kts", ".groovy",
                ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp",
                ".go", ".rs",
                // Скриптовые и другие языки
                ".py", ".lua", ".wren", ".janet",
                ".js", ".ts", ".jsx", ".tsx",
                ".php", ".rb", ".pl", ".swift", ".dart", ".r", ".sql",
                // Стили и оболочки
                ".css", ".scss", ".sass", ".less",
                ".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1",
                // Логи и прочее
                ".log"
            )
            return textExt.any { lower.endsWith(it) }
        }

        // 2. Обновленная проверка MIME: если система отдает octet-stream, но расширение текстовое — не пропускаем!
        fun shouldSkipByMime(mime: String?, fileName: String?): Boolean {
            if (mime == null) return false
            val lower = mime.lowercase()
            if (lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/")) return true
            if (lower.contains("zip") || lower.contains("font")) return true
            
            // Критически важно для .ft, .oml, .syd и других кастомных расширений
            if (lower.contains("octet-stream")) {
                return !isProbablyTextByExt(fileName)
            }
            return false
        }

        fun processFile(doc: DocumentFile) {
            try {
                val mime = doc.type
                if (shouldSkipByMime(mime, doc.name)) return
                if (!doc.isFile) return

                val input = contentResolver.openInputStream(doc.uri) ?: return
                val data = readAllBytesSafely(input) ?: return
                
                // detect null bytes -> likely binary
                if (data.contains(0.toByte())) {
                    return
                }
                
                val text = try {
                    String(data, Charset.forName("UTF-8"))
                } catch (e: Exception) {
                    try {
                        String(data, Charset.defaultCharset())
                    } catch (ex: Exception) {
                        return
                    }
                }

                val fileMatches = mutableListOf<MatchPreview>()
                if (showLineNumbers || collectPreview) {
                    val lines = text.split("\n")
                    for ((idx, line) in lines.withIndex()) {
                        val found = pattern.findAll(line).toList()
                        if (found.isNotEmpty()) {
                            val replacedLine = pattern.replace(line, replace)
                            fileMatches.add(MatchPreview(idx + 1, line, replacedLine))
                            if (fileMatches.size >= previewLimitPerFile && collectPreview) break
                        }
                    }
                }

                val foundCount = pattern.findAll(text).count()
                if (foundCount == 0 && fileMatches.isEmpty()) return

                if (collectPreview) {
                    previews.add(FilePreview(doc.name, doc.uri, fileMatches.take(previewLimitPerFile)))
                }

                if (!dryRun && !collectPreview) {
                    val replaced = pattern.replace(text, replace)
                    if (replaced != text) {
                        contentResolver.openOutputStream(doc.uri, "rwt")?.use { it.write(replaced.toByteArray(Charsets.UTF_8)) }
                        filesModified += 1
                        totalReplacements += foundCount
                    }
                } else {
                    totalReplacements += foundCount
                }
            } catch (e: Exception) {
                // Ошибка обработки файла, молча пропускаем во избежание сбоев
            }
        }

        fun traverse(dir: DocumentFile) {
            val children = dir.listFiles() ?: return // Защита от null при потере прав
            for (child in children) {
                if (child.isDirectory) {
                    if (recursive) traverse(child)
                } else {
                    if (child.type == null && !isProbablyTextByExt(child.name)) {
                        val peek = try { contentResolver.openInputStream(child.uri)?.use { readPeekBytes(it, 512) } } catch (e: Exception) { null }
                        if (peek == null || peek.contains(0.toByte())) continue
                    }
                    processFile(child)
                }
            }
        }

        traverse(root)
        return ReplaceResult(filesModified, totalReplacements, previews)
    }

    private fun readAllBytesSafely(input: InputStream): ByteArray? {
        return try {
            BufferedInputStream(input).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readPeekBytes(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        val n = input.read(buffer)
        return if (n == -1) ByteArray(0) else buffer.copyOf(n)
    }

    private fun showOverlay() {
        runOnUiThread {
            if (overlay != null) return@runOnUiThread
            val root = window.decorView as? FrameLayout ?: return@runOnUiThread
            overlay = FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                isClickable = true
                isFocusable = true
            }

            val tv = TextView(this).apply {
                text = "(\u00A0\u00A0\u00A0\u00A0\u00A0)"
                textSize = 36f
                setTextColor(0xFFFFBF00.toInt())
                gravity = Gravity.CENTER
            }

            val working = TextView(this).apply {
                text = "выполняется..."
                textSize = 14f
                setTextColor(0xFFFFBF00.toInt())
                val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = 60
                layoutParams = lp
            }

            val centerLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            centerLp.gravity = Gravity.CENTER
            root.addView(overlay)
            overlay?.addView(tv, centerLp)
            overlay?.addView(working)

            val anim = RotateAnimation(0f, 360f, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f)
            anim.duration = 1200
            anim.repeatCount = RotateAnimation.INFINITE
            anim.interpolator = LinearInterpolator()
            tv.startAnimation(anim)
        }
    }

    private fun hideOverlay() {
        runOnUiThread {
            overlay?.let {
                (window.decorView as? FrameLayout)?.removeView(it)
                overlay = null
            }
        }
    }

    private fun showPreviewDialog(
        scanResult: ReplaceResult,
        find: String,
        replace: String,
        recursive: Boolean,
        ignoreCase: Boolean,
        regexMode: Boolean,
        wholeWord: Boolean,
        showLineNumbers: Boolean
    ) {
        runOnUiThread {
            val previews = scanResult.previews
            val totalShown = previews.sumOf { it.matches.size }
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Предпросмотр: ${previews.size} файлов, $totalShown совпадений (показаны примеры)")

            val scroll = ScrollView(this)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                setBackgroundColor(0xFF0A0A0A.toInt())
            }

            if (previews.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "Совпадений не найдено."
                    setTextColor(0xFFFFBF00.toInt())
                }
                container.addView(empty)
            } else {
                for (filePreview in previews) {
                    val fileTitle = TextView(this).apply {
                        text = filePreview.fileName ?: filePreview.uri.toString()
                        setTextColor(0xFFFFBF00.toInt())
                        textSize = 14f
                    }
                    container.addView(fileTitle)

                    for (m in filePreview.matches) {
                        val tv = TextView(this).apply {
                            val beforeEsc = m.lineText.replace("\t", "    ")
                            val afterEsc = m.replacedLine.replace("\t", "    ")
                            text = if (showLineNumbers) "строка ${m.lineNumber}: $beforeEsc\n→ $afterEsc" else "$beforeEsc\n→ $afterEsc"
                            setTextColor(0xFFFFBF00.toInt())
                            textSize = 12f
                            setPadding(0, 6, 0, 10)
                        }
                        container.addView(tv)
                    }

                    val sep = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(0x55FFBF00.toInt())
                    }
                    container.addView(sep)
                }
            }

            scroll.addView(container)
            builder.setView(scroll)

            builder.setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            builder.setPositiveButton("Применить") { dialog, _ ->
                dialog.dismiss()
                showOverlay()
                tvStatus.text = "Применение..."
                lifecycleScope.launchWhenStarted {
                    val applyResult = withContext(Dispatchers.IO) {
                        processReplace(
                            pickedRoot!!, find, replace, recursive, ignoreCase,
                            regexMode, wholeWord, showLineNumbers,
                            dryRun = false,
                            collectPreview = false
                        )
                    }
                    hideOverlay()
                    val msg = "Применено. Файлов изменено: ${applyResult.filesModified}, Замен: ${applyResult.totalReplacements}"
                    tvStatus.text = msg
                    Toast.makeText(this@ReplaceToolActivity, msg, Toast.LENGTH_LONG).show()
                }
            }

            val dialog = builder.create()
            dialog.show()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFBF00.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFFBF00.toInt())
        }
    }
}
