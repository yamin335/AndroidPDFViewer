package mollah.yamin.androidpdfviewer.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import mollah.yamin.androidpdfviewer.databinding.PdfViewListItemBinding

class PdfViewRecyclerAdapter constructor(
    private val pdfRenderer: PdfRenderer
): RecyclerView.Adapter<PdfViewRecyclerAdapter.ViewHolder>() {
    private var currentPage: PdfRenderer.Page? = null
    private var cache: Array<Bitmap?> = Array(pdfRenderer.pageCount){null}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding: PdfViewListItemBinding = PdfViewListItemBinding.inflate(LayoutInflater.from(parent.context))
        return ViewHolder(binding)
    }

    inner class ViewHolder (private val binding: PdfViewListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        /**
         * Shows the specified page of PDF to the screen.
         *
         * The way [PdfRenderer] works is that it allows for "opening" a page with the method
         * [PdfRenderer.openPage], which takes a (0 based) page number to open. This returns
         * a [PdfRenderer.Page] object, which represents the content of this page.
         *
         * There are two ways to render the content of a [PdfRenderer.Page].
         * [PdfRenderer.Page.RENDER_MODE_FOR_PRINT] and [PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY].
         * Since we're displaying the data on the screen of the device, we'll use the later.
         *
         * @param position The page index.
         */

        fun bind(position: Int) {
            // Important: the destination bitmap must be ARGB (not RGB).
            if (cache[position] == null) {
                currentPage = pdfRenderer.openPage(position)
                cache[position] = Bitmap.createBitmap(currentPage!!.width, currentPage!!.height, Bitmap.Config.ARGB_8888)
                currentPage?.render(cache[position]!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                currentPage?.close()
            }

            Glide.with(binding.root.context)
                .load(cache[position])
                .into(binding.page)

            if (position + 1 < pdfRenderer.pageCount && cache[position + 1] == null) {
                loadNextPage(position + 1)
            }
        }
    }

    private fun loadNextPage(position: Int) {
        currentPage = pdfRenderer.openPage(position)
        cache[position] = Bitmap.createBitmap(currentPage!!.width, currentPage!!.height, Bitmap.Config.ARGB_8888)
        currentPage?.render(cache[position]!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        currentPage?.close()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount(): Int {
        return pdfRenderer.pageCount
    }
}
