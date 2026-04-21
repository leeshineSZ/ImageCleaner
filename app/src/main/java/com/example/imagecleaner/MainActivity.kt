package com.example.imagecleaner

import android.Manifest
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
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

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val deleted = pendingDeleteImages
            adapter.removeImages(deleted)
            Toast.makeText(this, "Deleted ${deleted.size} images", Toast.LENGTH_SHORT).show()
            updateStatus()
            tvStatus.text = "${adapter.itemCount} non-people images remaining"
        } else {
            Toast.makeText(this, "Delete cancelled", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteImages = emptyList()
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

    private fun deleteSelected(images: List<ImageData>) {
        pendingDeleteImages = images

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: use system delete confirmation dialog
            try {
                val uris = images.map { it.uri }
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Android 10 and below: direct delete
            lifecycleScope.launch {
                var deleted = 0
                for (image in images) {
                    val success = withContext(Dispatchers.IO) {
                        tryDeleteImage(image)
                    }
                    if (success) deleted++
                }
                adapter.removeImages(images)
                Toast.makeText(this@MainActivity, "Deleted $deleted images", Toast.LENGTH_SHORT).show()
                updateStatus()
                tvStatus.text = "${adapter.itemCount} non-people images remaining"
            }
        }
    }

    private fun tryDeleteImage(image: ImageData): Boolean {
        return try {
            contentResolver.delete(image.uri, null, null) > 0
        } catch (e: RecoverableSecurityException) {
            // Android 10: need user consent
            try {
                val intentSender = e.userAction.actionIntent.intentSender
                deleteLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
                false
            } catch (e2: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
