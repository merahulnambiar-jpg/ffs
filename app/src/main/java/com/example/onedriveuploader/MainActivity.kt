package com.example.onedriveuploader

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.onedriveuploader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authManager: AuthManager
    private var selectedUris: List<Uri> = emptyList()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
            binding.btnUpload.isEnabled = true
            log("Selected ${uris.size} file(s):")
            uris.forEach { log(" - ${queryFileName(it)}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(applicationContext)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { authManager.init() }
                binding.statusText.text = "Ready. Please sign in."
            } catch (e: Exception) {
                binding.statusText.text = "MSAL init failed: ${e.message}"
            }
        }

        binding.btnSignIn.setOnClickListener {
            lifecycleScope.launch {
                try {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    val token = withContext(Dispatchers.IO) { authManager.signIn(this@MainActivity) }
                    binding.statusText.text = "Signed in."
                    log("Sign-in successful. Token acquired (${token.take(12)}...)")
                } catch (e: Exception) {
                    binding.statusText.text = "Sign-in failed"
                    log("Sign-in error: ${e.message}")
                } finally {
                    binding.progressBar.visibility = android.view.View.GONE
                }
            }
        }

        binding.btnPickFiles.setOnClickListener {
            // application/zip and text/csv are the standard MIME types for these extensions
            filePickerLauncher.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values"))
        }

        binding.btnUpload.setOnClickListener {
            uploadSelectedFiles()
        }
    }

    private fun uploadSelectedFiles() {
        val folderPath = binding.folderPathInput.text.toString().trim()
        if (folderPath.isEmpty()) {
            log("Please enter a destination folder path.")
            return
        }
        if (selectedUris.isEmpty()) {
            log("No files selected.")
            return
        }

        lifecycleScope.launch {
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.btnUpload.isEnabled = false
            try {
                val token = withContext(Dispatchers.IO) { authManager.getAccessToken(this@MainActivity) }
                val graphClient = GraphUploadClient(token)

                withContext(Dispatchers.IO) {
                    graphClient.ensureFolderExists(folderPath) { msg -> runOnUiThread { log(msg) } }
                }

                for (uri in selectedUris) {
                    val tempFile = copyUriToTempFile(uri)
                    withContext(Dispatchers.IO) {
                        graphClient.uploadFile(tempFile, folderPath) { msg -> runOnUiThread { log(msg) } }
                    }
                    tempFile.delete()
                }

                log("All uploads complete.")
            } catch (e: Exception) {
                log("Upload error: ${e.message}")
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnUpload.isEnabled = true
            }
        }
    }

    /** Copies a content:// Uri to a real temp File so OkHttp/Graph upload can read it with a known length. */
    private suspend fun copyUriToTempFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = queryFileName(uri) ?: "upload_${System.currentTimeMillis()}"
        val tempFile = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    }

    private fun queryFileName(uri: Uri): String? {
        val resolver: ContentResolver = contentResolver
        var name: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun log(message: String) {
        binding.logText.append("$message\n")
    }
}
