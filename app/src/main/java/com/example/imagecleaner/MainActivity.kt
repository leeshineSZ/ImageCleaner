package com.example.imagecleaner

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_DELETE = 1001
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDelete: Button
    private lateinit var btnSelectAll: Button
    private lateinit var adapter: ImageAdapter
    private lateinit var analyzer: ImageAnalyzer

    private var pendingDeleteImages: List<ImageData> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScanning()
        else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)

        analyzer = ImageAnalyzer(this)

        adapter = ImageAdapter(mutableListOf()) { updateStatus() }
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        btnDelete.setOnClickListener { confirmAndDelete() }
        btnSelectAll.setOnClickListener {
            if (adapter.getSelectedImages().size == adapter.itemCount) {
                adapter.clearSelection()
                btnSelectAll.text = "Select All"
            } else {
                adapter.selectAll()
                btnSelectAll.text = "Clear"
            }
        }

        checkPermissionAndStart()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE) {
            if (resultCode == RESULT_OK) {
                adapter.removeImages(pendingDeleteImages)
                Toast.makeText(this, "Deleted ${pendingDeleteImages.size} images", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Delete cancelled", Toast.LENGTH_SHORT).show()
            }
            pendingDeleteImages = emptyList()
            updateStatus()
            tvStatus.text = "${adapter.itemCount} non-people images remaining"
        }
    }

    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun startScanning() {
        lifecycleScope.launch {
            tvStatus.text = "Loading images..."
            progressBar.isIndeterminate = true

            val allImages = withContext(Dispatchers.IO) {
                analyzer.queryAllImages()
            }

            if (allImages.isEmpty()) {
                tvStatus.text = "No images found"
                progressBar.visibility = ProgressBar.GONE
                return@launch
            }

            tvStatus.text = "Scanning 0/${allImages.size} for faces..."
            progressBar.isIndeterminate = false
            progressBar.max = allImages.size

            val nonPeopleImages = mutableListOf<ImageData>()

            for ((index, image) in allImages.withIndex()) {
                val hasFace = withContext(Dispatchers.IO) {
                    try {
                        analyzer.containsFace(image.uri)
                    } catch (e: Exception) {
                        false
                    }
                }

                if (!hasFace) {
                    nonPeopleImages.add(image)
                    withContext(Dispatchers.Main) {
                        adapter.images.add(image)
                        adapter.notifyItemInserted(adapter.images.size - 1)
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.progress = index + 1
                    tvStatus.text = "Scanning ${index + 1}/${allImages.size} — Found ${nonPeopleImages.size} non-people"
                }
            }

            progressBar.visibility = ProgressBar.GONE
            tvStatus.text = "Done: ${nonPeopleImages.size} non-people images (oldest first)"
            updateStatus()
        }
    }

    private fun updateStatus() {
        val selected = adapter.getSelectedImages().size
        val total = adapter.itemCount
        btnDelete.text = if (selected > 0) "Delete ($selected)" else "Delete"
        btnSelectAll.text = if (selected == total && total > 0) "Clear" else "Select All"
    }

    private fun confirmAndDelete() {
        val selected = adapter.getSelectedImages()
        if (selected.isEmpty()) {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete ${selected.size} images?")
            .setMessage("This will permanently delete ${selected.size} images from your device.")
            .setPositiveButton("Delete") { _, _ -> deleteSelected(selected) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun deleteSelected(images: List<ImageData>) {
        pendingDeleteImages = images

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(
                    contentResolver,
                    images.map { it.uri }
                )
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    REQUEST_DELETE,
                    null, 0, 0, 0
                )
            } catch (e: Exception) {
                val msg = "Delete failed [${e.javaClass.simpleName}]: ${e.message}"
                tvStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            lifecycleScope.launch {
                var deleted = 0
                val errors = mutableListOf<String>()
                for (image in images) {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val rows = contentResolver.delete(image.uri, null, null)
                            if (rows > 0) "ok" else "fail: rows=0"
                        } catch (e: Exception) {
                            "fail: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                    if (result == "ok") {
                        deleted++
                    } else {
                        errors.add("${image.displayName}: $result")
                    }
                }
                adapter.removeImages(images)
                if (errors.isEmpty()) {
                    tvStatus.text = "Deleted $deleted images. ${adapter.itemCount} remaining"
                } else {
                    tvStatus.text = "Deleted $deleted/${images.size}. Errors: ${errors.first()}"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete result: $deleted/${images.size}")
                        .setMessage(errors.joinToString("\n"))
                        .setPositiveButton("OK", null)
                        .show()
                }
                updateStatus()
            }
        }
    }
}
