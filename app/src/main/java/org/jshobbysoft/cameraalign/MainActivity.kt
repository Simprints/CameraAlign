package org.jshobbysoft.cameraalign

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.snackbar.Snackbar
import org.jshobbysoft.cameraalign.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageUri: Uri? = null
    private var imageCapture: ImageCapture? = null
    private var cameraSel = CameraSelector.DEFAULT_BACK_CAMERA
    private var camera: Camera? = null
    private var vflipState = 1
    private var hflipState = 1
    private var imageName = ""

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
            ).apply {
                // For older Android versions, we also need WRITE_EXTERNAL_STORAGE.
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val greenScreenEffectTarget =
            prefs.getString("greenScreenEffectTargetKey", "transparentColorPreview")

        // Request camera permissions or start camera
        if (allPermissionsGranted()) {
            startCamera(cameraSel, greenScreenEffectTarget)
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        if (intent.getStringExtra("image") != null) {
            setImageFromIntent()
        } else {
            // 1) Load saved background URI from preferences
            val backgroundUriString = prefs.getString("background_uri_key", "")
            if (backgroundUriString.isNullOrEmpty()) {
                // If no saved URI, set fallback resource
                setBasisImage(null)
            } else {
                // Load from saved URI
                setBasisImage(Uri.parse(backgroundUriString))
            }
        }

        // 2) Set transparency (alpha) for the basis image
        val transparencyValue =
            prefs.getString("textTransparencyKey", "125")!!.toFloat()
        val transparencyValueFloat = transparencyValue / 255
        viewBinding.basisImage.alpha = transparencyValueFloat

        // 3) Visibility
        viewBinding.viewFinder.visibility = View.VISIBLE
        viewBinding.basisImage.visibility = View.VISIBLE
        viewBinding.transparentView.visibility = View.INVISIBLE


        // 4) Button to load a new picture from gallery
        viewBinding.buttonLoadPicture.setOnClickListener {
            val gallery =
                Intent(Intent.ACTION_OPEN_DOCUMENT, MediaStore.Images.Media.INTERNAL_CONTENT_URI)
            resultLauncher.launch(gallery)
        }

        // 5) Button to capture photo
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }

        // 6) Rotate button
        viewBinding.imageRotateButton.setOnClickListener {
            viewBinding.basisImage.rotation += 90
        }

        // 7) Vertical flip button
        viewBinding.imageVflipButton.setOnClickListener {
            if (vflipState == 1) {
                viewBinding.basisImage.scaleX = -1f
                vflipState = -1
            } else {
                viewBinding.basisImage.scaleX = 1f
                vflipState = 1
            }
        }

        // 8) Horizontal flip button
        viewBinding.imageHflipButton.setOnClickListener {
            if (hflipState == 1) {
                viewBinding.basisImage.scaleY = -1f
                hflipState = -1
            } else {
                viewBinding.basisImage.scaleY = 1f
                hflipState = 1
            }
        }

        // 9) Camera (front/back) toggle
        viewBinding.cameraFlipButton.setOnClickListener {
            cameraSel = if (cameraSel == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera(cameraSel, greenScreenEffectTarget)
        }

        // 10) Zoom seek bar
        viewBinding.zoomSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                camera?.cameraControl?.setLinearZoom(progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    /**
     * Helper function to set the basisImage from a given [Uri].
     * - If [uri] is null or empty, fall back to a default resource.
     * - Otherwise, set the image and attempt to read EXIF zoom data.
     */
    private fun setBasisImage(uri: Uri?) {
        if (uri == null) {
            // Fallback if the URI is empty or invalid
            viewBinding.basisImage.setImageResource(R.drawable.ic_launcher_background)
            return
        }

        try {
            // Attempt to load URI
            viewBinding.basisImage.setImageURI(uri)

            // Read EXIF data and apply saved zoom
            val readOnlyMode = "r"
            contentResolver.openFileDescriptor(uri, readOnlyMode).use { pfd ->
                if (pfd != null) {
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val zoomStateString = exif.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)
                    if (!zoomStateString.isNullOrEmpty()) {
                        val zoomValue = zoomStateString.toFloat()
                        viewBinding.zoomSeekBar.progress = zoomValue.toInt()
                        camera?.cameraControl?.setLinearZoom(zoomValue / 100f)
                    }
                }
            }
        } catch (e: Exception) {
            // If something goes wrong, revert to fallback and warn user
            viewBinding.basisImage.setImageResource(R.drawable.ic_launcher_background)
            Toast.makeText(
                this,
                "Background file does not exist or cannot be opened. Please choose a different file.",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
        }
    }

    /**
     * Reads the "image" extra from the Intent and calls [setBasisImage] if present.
     */
    private fun setImageFromIntent() {
        val imageType = intent.getStringExtra("image")
        val id = intent.getStringExtra("id")

        imageName = "$id-$imageType"

        when (imageType) {
            "left_ear" -> {
                viewBinding.basisImage.setImageResource(R.drawable.ear_left)
            }

            "right_ear" -> {
                viewBinding.basisImage.setImageResource(R.drawable.ear_right)
            }

            "left_foot" -> {
                viewBinding.basisImage.setImageResource(R.drawable.foot_left)
            }

            "right_foot" -> {
                viewBinding.basisImage.setImageResource(R.drawable.foot_right)
            }

            "left_hand" -> {
                viewBinding.basisImage.setImageResource(R.drawable.hand_left)
            }

            "right_hand" -> {
                viewBinding.basisImage.setImageResource(R.drawable.hand_right)
            }

            "head" -> {
                viewBinding.basisImage.setImageResource(R.drawable.head)
            }

            else -> setBasisImage(null)
        }

        viewBinding.imageHflipButton.visibility = View.GONE
        viewBinding.imageVflipButton.visibility = View.GONE
        viewBinding.imageRotateButton.visibility = View.GONE
        viewBinding.buttonLoadPicture.visibility = View.GONE
    }

    /**
     * Callback from image picker (Gallery).
     * Once we get the Uri, we set the basisImage and store the Uri to preferences.
     */
    private var resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                imageUri = data?.data
                imageUri?.let { uri ->
                    val contentResolver = applicationContext.contentResolver
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    // Update the basis image via the single function
                    setBasisImage(uri)

                    // Store the new background URI in shared prefs
                    val prefs =
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    prefs.edit().putString("background_uri_key", uri.toString()).apply()
                }
            }
        }

    private fun startCamera(cameraSelector: CameraSelector, gSET: String?) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            // Decide if we need to do real-time analysis for transparency
            if (gSET == "transparentColorPreview") {
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    runOnUiThread {
                        val rawPreview = viewBinding.viewFinder.bitmap
                        val bitmapTransparency = toTransparency(rawPreview)
                        viewBinding.transparentView.setImageBitmap(bitmapTransparency)
                    }
                    imageProxy.close()
                }

                imageCapture = ImageCapture.Builder().build()

                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, imageAnalysis
                    )
                } catch (exc: Exception) {
                    // Log or handle binding error
                }
            } else {
                imageCapture = ImageCapture.Builder().build()

                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                    )
                } catch (exc: Exception) {
                    // Log or handle binding error
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Create time-stamped name and MediaStore entry
        val name = "$imageName-${
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
        }"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        // Capture the picture
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    // Handle error
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Save zoom in exif data
                    val resolver = applicationContext.contentResolver
                    val readOnlyMode = "rw"
                    output.savedUri?.let { savedUri ->
                        resolver.openFileDescriptor(savedUri, readOnlyMode).use { pfd ->
                            if (pfd != null) {
                                val zS = viewBinding.zoomSeekBar.progress
                                val exif = ExifInterface(pfd.fileDescriptor)
                                exif.setAttribute(
                                    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
                                    zS.toString()
                                )
                                exif.saveAttributes()
                            }
                        }
                        val photoPath = getPath(applicationContext, savedUri)
                        val msg = "Photo capture succeeded: $photoPath"
                        val sb = Snackbar.make(viewBinding.root, msg, Snackbar.LENGTH_LONG)
                        val sbView: View = sb.view
                        val textView: TextView =
                            sbView.findViewById(com.google.android.material.R.id.snackbar_text)
                        textView.maxLines = 4
                        sb.show()
                    }
                }
            }
        )
    }

    // Utility functions for path resolution
    fun getPath(context: Context?, uri: Uri): String? {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            when {
                isExternalStorageDocument(uri) -> {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":")
                    val type = split[0]
                    if ("primary".equals(type, true)) {
                        return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                    }
                }

                isDownloadsDocument(uri) -> {
                    val id = DocumentsContract.getDocumentId(uri)
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), id.toLong()
                    )
                    return getDataColumn(context!!, contentUri, null, null)
                }

                isMediaDocument(uri) -> {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":")
                    val type = split[0]
                    var contentUri: Uri? = null
                    contentUri = when (type) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> null
                    }
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(split[1] as String?)
                    return getDataColumn(context!!, contentUri, selection, selectionArgs)
                }

                else -> {
                    // LocalStorageProvider - the path is the docId itself
                    return DocumentsContract.getDocumentId(uri)
                }
            }
        } else if ("content".equals(uri.scheme, true)) {
            // Return the remote address
            return if (isGooglePhotosUri(uri)) {
                uri.lastPathSegment
            } else {
                getDataColumn(context!!, uri, null, null)
            }
        } else if ("file".equals(uri.scheme, true)) {
            return uri.path
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri) =
        "com.android.externalstorage.documents" == uri.authority

    private fun isDownloadsDocument(uri: Uri) =
        "com.android.providers.downloads.documents" == uri.authority

    private fun isMediaDocument(uri: Uri) =
        "com.android.providers.media.documents" == uri.authority

    private fun isGooglePhotosUri(uri: Uri) =
        "com.google.android.apps.photos.content" == uri.authority

    private fun getDataColumn(
        context: Context, uri: Uri?, selection: String?,
        selectionArgs: Array<String?>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            cursor =
                context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * Convert matching (red, green, blue) pixels to transparency in a bitmap.
     */
    private fun toTransparency(bmpOriginal: Bitmap?): Bitmap {
        val width = bmpOriginal?.width ?: 100
        val height = bmpOriginal?.height ?: 100
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        val transparentRed = prefs.getString("textRedKey", "0")?.toInt() ?: 0
        val transparentGreen = prefs.getString("textGreenKey", "0")?.toInt() ?: 0
        val transparentBlue = prefs.getString("textBlueKey", "0")?.toInt() ?: 0

        val bmpTransparent = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val allPixels = IntArray(width * height)
        bmpOriginal?.getPixels(allPixels, 0, width, 0, 0, width, height)

        allPixels.forEachIndexed { index, pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            if (r == transparentRed && g == transparentGreen && b == transparentBlue) {
                // make transparent
                allPixels[index] = Color.argb(0, r, g, b)
            }
        }
        bmpTransparent.setPixels(allPixels, 0, width, 0, 0, width, height)
        return bmpTransparent
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val greenScreenEffectTarget =
            prefs.getString("greenScreenEffectTargetKey", "transparentColorPreview")
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera(cameraSel, greenScreenEffectTarget)
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.menu_settings, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val settingsIntent = Intent(this, SettingsActivity::class.java)
                startActivity(settingsIntent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

