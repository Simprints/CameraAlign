package org.jshobbysoft.cameraalign

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import org.jshobbysoft.cameraalign.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var cameraSel = CameraSelector.DEFAULT_BACK_CAMERA
    private var imageCapture: ImageCapture? = null
    private var imageUri: Uri? = null

    // Flip states
    private var vflipState = 1
    private var hflipState = 1

    // If we come in via an Intent that specifies an image type (like "left_ear", etc.)
    private var imageName = ""

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
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

        // Camera permissions check
        if (allPermissionsGranted()) {
            startCamera(cameraSel)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Handle incoming intent (if any)
        if (intent.getStringExtra("image") != null) {
            setImageFromIntent()
        } else {
            // Load saved background if available
            loadSavedBackground()
        }

        // Apply transparency to the basis image
        applySavedTransparency()

        // Show/hide appropriate views
        initializeDefaultVisibility()

        // Set up UI listeners (buttons, seek bars, etc.)
        initUIListeners()

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    /**
     * Sets up various click/seek listeners for UI elements.
     */
    private fun initUIListeners() {
        // Load from gallery
        viewBinding.buttonLoadPicture.setOnClickListener {
            val galleryIntent = Intent(
                Intent.ACTION_OPEN_DOCUMENT, MediaStore.Images.Media.INTERNAL_CONTENT_URI
            )
            resultLauncher.launch(galleryIntent)
        }

        // Take photo
        viewBinding.imageCaptureButton.setOnClickListener {
            takePhoto()
        }

        // Rotate image
        viewBinding.cameraFlipButton.setOnClickListener {
            viewBinding.basisImage.rotation += 90f
        }

        // Vertical flip button
        viewBinding.imageVflipButton.setOnClickListener {
            // Flip across the vertical axis => scaleX toggles -1 / 1
            vflipState = toggleFlip(vflipState, true)
        }

        // Horizontal flip button
        viewBinding.imageHflipButton.setOnClickListener {
            // Flip across the horizontal axis => scaleY toggles -1 / 1
            hflipState = toggleFlip(hflipState, false)
        }

        // Front/back camera toggle
        viewBinding.imageRotateButton.setOnClickListener {
            cameraSel = if (cameraSel == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera(cameraSel)
        }

        // Zoom seek bar
        viewBinding.zoomSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                camera?.cameraControl?.setLinearZoom(progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Simple helper to toggle the scale factor on the X or Y axis.
     */
    private fun toggleFlip(flipState: Int, isVerticalAxis: Boolean): Int {
        val newState = if (flipState == 1) -1 else 1
        val scale = if (newState == 1) 1f else -1f
        if (isVerticalAxis) {
            viewBinding.basisImage.scaleX = scale
        } else {
            viewBinding.basisImage.scaleY = scale
        }
        return newState
    }

    /**
     * Check if all required permissions are granted.
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Called when user has accepted/denied the permissions we requested.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera(cameraSel)
            } else {
                Toast.makeText(
                    this, "Permissions not granted by the user.", Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /**
     * Initialize camera with the specified CameraSelector.
     */
    private fun startCamera(cameraSelector: CameraSelector) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                // Handle error if needed
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Captures a photo using the current [imageCapture] configuration
     * and saves it to MediaStore.
     */
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Create time-stamped name and MediaStore entry
        val name = "$imageName-${
            SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        }"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Capture the picture
        imageCapture.takePicture(outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    // Handle error if needed
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Save zoom in Exif
                    output.savedUri?.let { savedUri ->
                        cameraExecutor.execute { saveExifZoom(savedUri) }
                        showCaptureToast()
                    }
                }
            })
    }

    /**
     * Write the zoom level from zoomSeekBar to Exif data.
     */
    private fun saveExifZoom(savedUri: Uri) {
        val readWriteMode = "rw"
        contentResolver.openFileDescriptor(savedUri, readWriteMode).use { pfd ->
            if (pfd != null) {
                val exif = ExifInterface(pfd.fileDescriptor)
                val zoomValue = viewBinding.zoomSeekBar.progress
                exif.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, zoomValue.toString())
                exif.saveAttributes()
            }
        }
    }

    /**
     * Show a Snackbar indicating successful capture, including the file path.
     */
    private fun showCaptureToast() {
        runOnUiThread {
            val toast =
                Toast.makeText(applicationContext, "Photo capture succeeded", Toast.LENGTH_LONG)
            toast.setGravity(Gravity.TOP, 0, 0)
            toast.show()
        }
    }

    /**
     * Sets the basisImage from an Intent extra (e.g., "left_ear", "right_foot", etc.),
     * or uses a default resource if unknown.
     */
    private fun setImageFromIntent() {
        val imageType = intent.getStringExtra("image")
        val id = intent.getStringExtra("id")
        imageName = "$id-$imageType"

        when (imageType) {
            "left_ear" -> viewBinding.basisImage.setImageResource(R.drawable.ear_left)
            "right_ear" -> viewBinding.basisImage.setImageResource(R.drawable.ear_right)
            "left_foot" -> viewBinding.basisImage.setImageResource(R.drawable.foot_left)
            "right_foot" -> viewBinding.basisImage.setImageResource(R.drawable.foot_right)
            "left_hand" -> viewBinding.basisImage.setImageResource(R.drawable.hand_left)
            "right_hand" -> viewBinding.basisImage.setImageResource(R.drawable.hand_right)
            "head" -> viewBinding.basisImage.setImageResource(R.drawable.head)
            else -> setBasisImage(null)
        }

        val basisImageConstraint = when (imageType) {
            "left_ear" -> 0.75f
            "right_ear" -> 0.75f
            "left_foot" -> 0.90f
            "right_foot" -> 0.90f
            "left_hand" -> 0.95f
            "right_hand" -> 0.95f
            "head" -> 0.75f
            else -> 0.75f
        }

        val view = findViewById<View>(R.id.basisImage)
        val params = view.layoutParams as ConstraintLayout.LayoutParams

        params.matchConstraintPercentWidth = basisImageConstraint

        view.layoutParams = params
        view.requestLayout()

        // Hide UI elements that aren't needed in this mode
        viewBinding.imageHflipButton.visibility = View.GONE
        viewBinding.imageVflipButton.visibility = View.GONE
        viewBinding.cameraFlipButton.visibility = View.GONE
        viewBinding.buttonLoadPicture.visibility = View.GONE
        viewBinding.zoomSeekBar.visibility = View.GONE
    }

    /**
     * Load the user-selected background from shared preferences, if any.
     */
    private fun loadSavedBackground() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val backgroundUriString = prefs.getString("background_uri_key", "")
        if (backgroundUriString.isNullOrEmpty()) {
            setBasisImage(null)
        } else {
            setBasisImage(Uri.parse(backgroundUriString))
        }
    }

    /**
     * Helper function to set the basisImage from a given [Uri].
     * - If [uri] is null, fall back to a default resource.
     * - Otherwise, set the image and apply any saved zoom from EXIF.
     */
    private fun setBasisImage(uri: Uri?) {
        if (uri == null) {
            // Fallback if the URI is empty
            viewBinding.basisImage.setImageResource(R.drawable.ic_launcher_background)
            return
        }

        try {
            // Attempt to load URI
            viewBinding.basisImage.setImageURI(uri)

            // Read EXIF data (zoom) and apply it
            val readOnlyMode = "r"
            contentResolver.openFileDescriptor(uri, readOnlyMode).use { pfd ->
                pfd?.fileDescriptor?.let { fd ->
                    val exif = ExifInterface(fd)
                    exif.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)?.toFloat()
                        ?.let { zoom ->
                            viewBinding.zoomSeekBar.progress = zoom.toInt()
                            camera?.cameraControl?.setLinearZoom(zoom / 100f)
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
     * Apply the saved transparency (alpha) to the basis image, from shared preferences.
     */
    private fun applySavedTransparency() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val transparencyValue = prefs.getString("textTransparencyKey", "125")!!.toFloat()
        viewBinding.basisImage.alpha = transparencyValue / 255
    }

    /**
     * Initialize the default visibility of certain views.
     */
    private fun initializeDefaultVisibility() {
        viewBinding.viewFinder.visibility = View.VISIBLE
        viewBinding.basisImage.visibility = View.VISIBLE
        viewBinding.transparentView.visibility = View.INVISIBLE
    }

    /**
     * Callback from image picker (Gallery). Once we get the Uri,
     * we set the basisImage and store the Uri in preferences.
     */
    private var resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                imageUri = data?.data
                imageUri?.let { uri ->
                    val contentResolver = applicationContext.contentResolver
                    val takeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    setBasisImage(uri)

                    // Store new background URI in shared prefs
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                    prefs.edit().putString("background_uri_key", uri.toString()).apply()
                }
            }
        }

    // Menu / settings handling
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
