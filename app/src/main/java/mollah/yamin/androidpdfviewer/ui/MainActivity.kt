package mollah.yamin.androidpdfviewer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import mollah.yamin.androidpdfviewer.R
import mollah.yamin.androidpdfviewer.databinding.MainActivityBinding
import java.io.FileDescriptor
import java.io.IOException

private const val TAG = "MainActivity"
private const val LAST_OPENED_URI_KEY =
    "mollah.yamin.androidpdfviewer.pref.LAST_OPENED_URI_KEY"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding
    private lateinit var pdfFileChooserLauncher: ActivityResultLauncher<Intent>
    private var pdfRenderer: PdfRenderer? = null
    private lateinit var linearLayoutManager: LinearLayoutManager

    override fun onStop() {
        super.onStop()
        try {
            closeRenderer()
        } catch (ioException: IOException) {
            Log.d(TAG, "Exception closing document", ioException)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        linearLayoutManager = LinearLayoutManager(this)

        pdfFileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val docUri = result?.data?.data
            docUri?.let {
                /**
                 * Upon getting a document uri returned, we can use
                 * [ContentResolver.takePersistableUriPermission] in order to persist the
                 * permission across restarts.
                 *
                 * This may not be necessary for your app. If the permission is not
                 * persisted, access to the uri is granted until the receiving Activity is
                 * finished. You can extend the lifetime of the permission grant by passing
                 * it along to another Android component. This is done by including the uri
                 * in the data field or the ClipData object of the Intent used to launch that
                 * component. Additionally, you need to add FLAG_GRANT_READ_URI_PERMISSION
                 * and/or FLAG_GRANT_WRITE_URI_PERMISSION to the Intent.
                 *
                 * This app takes the persistable URI permission grant to demonstrate how, and
                 * to allow us to reopen the last opened document when the app starts.
                 */
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                openDocument(it)
            }
        }

        binding.btnOpenPdf.setOnClickListener {
            openDocumentPicker()
        }

        binding.recyclerView.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_SETTLING || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    binding.pageCounter.apply {
                        alpha = 1f
                        visibility = View.VISIBLE
                    }
                } else {
                    binding.pageCounter.animate()
                        .alpha(0f)
                        .setDuration(300L)
                        .setListener(object: AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                binding.pageCounter.visibility = View.GONE
                            }
                        })
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (pdfRenderer != null && pdfRenderer!!.pageCount > 0) {
                    val lastFullyVisibleItem = linearLayoutManager.findLastCompletelyVisibleItemPosition() + 1
                    val lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition() + 1
                    val pageIndex = if (lastFullyVisibleItem > 0) {
                        lastFullyVisibleItem
                    } else {
                        lastVisibleItem
                    }
                    binding.pageCounter.text = "$pageIndex of ${pdfRenderer!!.pageCount}"
                } else {
                    binding.pageCounter.visibility = View.INVISIBLE
                }
            }
        })

        getSharedPreferences(TAG, Context.MODE_PRIVATE).let { sharedPreferences ->
            if (sharedPreferences.contains(LAST_OPENED_URI_KEY)) {
                val documentUri =
                    sharedPreferences.getString(LAST_OPENED_URI_KEY, null)?.toUri() ?: return@let
                openDocument(documentUri)
            }
        }
    }

    private fun openDocumentPicker() {
        val nIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            /**
             * It's possible to limit the types of files by mime-type. Since this
             * app displays pages from a PDF file, we'll specify `application/pdf`
             * in `type`.
             * See [Intent.setType] for more details.
             */
            type = "application/pdf"

            /**
             * Because we'll want to use [ContentResolver.openFileDescriptor] to read
             * the data of whatever file is picked, we set [Intent.CATEGORY_OPENABLE]
             * to ensure this will succeed.
             */
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pdfFileChooserLauncher.launch(nIntent)
        overridePendingTransition(R.anim.slide_from_right, R.anim.slide_to_left)
    }

    private fun openDocument(documentUri: Uri) {
        /**
         * Save the document to [SharedPreferences]. We're able to do this, and use the
         * uri saved indefinitely, because we called [ContentResolver.takePersistableUriPermission]
         * up in [onActivityResult].
         */
        getSharedPreferences(TAG, Context.MODE_PRIVATE).edit {
            putString(LAST_OPENED_URI_KEY, documentUri.toString())
        }

        // Document is open, so get rid of the call to action view.
        binding.imageView.visibility = View.GONE
        binding.btnOpenPdf.visibility = View.GONE

        binding.recyclerView.visibility = View.VISIBLE

        try {
            openRenderer(this, documentUri)
            pdfRenderer?.let {
                val pdfViewRecyclerAdapter = PdfViewRecyclerAdapter(pdfRenderer = it)
                binding.recyclerView.apply {
                    layoutManager = linearLayoutManager
                    adapter = pdfViewRecyclerAdapter
                }
            }
        } catch (ioException: IOException) {
            Log.d(TAG, "Exception opening document", ioException)
        }
    }

    /**
     * Sets up a [PdfRenderer] and related resources.
     */
    @Throws(IOException::class)
    private fun openRenderer(context: Context?, documentUri: Uri) {
        if (context == null) return

        /**
         * It may be tempting to use `use` here, but [PdfRenderer] expects to take ownership
         * of the [FileDescriptor], and, if we did use `use`, it would be auto-closed at the
         * end of the block, preventing us from rendering additional pages.
         */
        val fileDescriptor = context.contentResolver.openFileDescriptor(documentUri, "r") ?: return

        // This is the PdfRenderer we use to render the PDF.
        pdfRenderer = PdfRenderer(fileDescriptor)
    }

    /**
     * Closes the [PdfRenderer] and related resources.
     *
     * @throws IOException When the PDF file cannot be closed.
     */
    @Throws(IOException::class)
    private fun closeRenderer() {
        pdfRenderer?.close()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> {
                openDocumentPicker()
                return true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}